package org.wwz.ai.domain.agent.runtime.tool;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.serializer.JSONSerializer;
import com.alibaba.fastjson.serializer.SerializeWriter;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.alibaba.fastjson.serializer.ValueFilter;
import org.apache.commons.lang3.StringUtils;

/**
 * 工具结果序列化：
 * 把工具结果压成主智能体 transcript 里的 observation 字符串。
 *
 * <ul>
 *   <li>失败 + dict detail → {@code {"tool_ok":false,"error":...,"detail":...}}</li>
 *   <li>失败 + 仅 error → {@code Error: ...}</li>
 *   <li>成功 + String data → 原样字符串</li>
 *   <li>成功 + 其他 data → {@code json.dumps(data)}</li>
 * </ul>
 *
 * 不负责 artifactKey / {@code $$$}；那由 {@code ToolArtifactFormatter} 在截断后追加。
 */
public final class ToolObservationSerializer {

    /** LLM 工具字符串长度上限。 */
    public static final int DEFAULT_LLM_TOOL_STRING_CAP = 96_000;

    /** 截断提示：必须给出可执行的续读方式，否则模型只能盲目重试。 */
    public static final String TRUNCATION_NOTICE =
            "\n...[output truncated at the context cap. Narrow the query, or read the file in smaller "
                    + "windows with workspace_read(start_line, line_count)]...";

    /**
     * 序列化缓冲的额外余量。限制的是「写出去的上限」，缓冲区允许略大于 cap，
     * 这样单个被截断的超长字符串仍能写进缓冲区，不会只留下一个很短的半截 JSON。
     */
    private static final int JSON_SERIALIZE_HEADROOM = 8_192;

    private ToolObservationSerializer() {
    }

    /**
     * 成功路径：String 原样，其余 JSON 序列化。
     */
    public static String serializeSuccess(Object data) {
        return serializeSuccess(data, DEFAULT_LLM_TOOL_STRING_CAP);
    }

    public static String serializeSuccess(Object data, int cap) {
        if (data == null) {
            return truncateForLlm("null", cap);
        }
        if (data instanceof String text) {
            return truncateForLlm(text, cap);
        }
        try {
            return toBoundedJson(data, cap);
        } catch (Exception ignore) {
            return truncateForLlm(String.valueOf(data), cap);
        }
    }

    /**
     * 失败路径：有结构化 detail 时输出 tool_ok JSON，否则 Error 前缀。
     */
    public static String serializeFailure(String error, Object detail) {
        return serializeFailure(error, detail, DEFAULT_LLM_TOOL_STRING_CAP);
    }

    public static String serializeFailure(String error, Object detail, int cap) {
        String errorText = StringUtils.defaultIfBlank(error, "Unknown error");
        boolean emptyMap = detail instanceof java.util.Map<?, ?> map && map.isEmpty();
        if (detail != null && !(detail instanceof String) && !emptyMap) {
            java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("tool_ok", Boolean.FALSE);
            payload.put("error", errorText);
            payload.put("detail", detail);
            try {
                return toBoundedJson(payload, cap);
            } catch (Exception ignore) {
                return truncateForLlm("Error: " + errorText, cap);
            }
        }
        return truncateForLlm("Error: " + errorText, cap);
    }

    /**
     * 从 {@link ToolResultPayload} 生成 observation（仅在 llmObservation 未预填时使用）。
     */
    public static String serializePayload(ToolResultPayload payload) {
        return serializePayload(payload, DEFAULT_LLM_TOOL_STRING_CAP);
    }

    public static String serializePayload(ToolResultPayload payload, int cap) {
        if (payload == null) {
            return truncateForLlm("Error: Unknown error", cap);
        }
        if (Boolean.TRUE.equals(payload.getFailed())) {
            Object detail = payload.getLlmData();
            if (detail == null && StringUtils.isNotBlank(payload.getToolResult())
                    && !StringUtils.equals(payload.getToolResult(), payload.getErrorMsg())) {
                detail = payload.getToolResult();
            }
            return serializeFailure(
                    StringUtils.defaultIfBlank(payload.getErrorMsg(), payload.getToolResult()),
                    detail,
                    cap
            );
        }
        if (payload.getLlmData() != null) {
            return serializeSuccess(payload.getLlmData(), cap);
        }
        if (StringUtils.isNotBlank(payload.getToolResult())) {
            return serializeSuccess(payload.getToolResult(), cap);
        }
        return truncateForLlm("", cap);
    }

    /**
     * 边写边限长：输出上限仍是 {@code cap}，但不再先把整个对象序列化成完整字符串。
     * 未超限时输出与 {@link JSON#toJSONString(Object)} 完全一致；超限时返回等长的截断结果。
     */
    private static String toBoundedJson(Object value, int cap) {
        if (cap <= 0) {
            return JSON.toJSONString(value);
        }
        // 单个超长字符串先截断，避免一次写入就撑爆序列化缓冲。
        ValueFilter limitLongStrings = (object, name, fieldValue) ->
                fieldValue instanceof String text && text.length() > cap
                        ? truncateForLlm(text, cap)
                        : fieldValue;
        SerializeWriter writer = new SerializeWriter(
                null, JSON.DEFAULT_GENERATE_FEATURE, new SerializerFeature[0]);
        int maxBufferSize = cap + JSON_SERIALIZE_HEADROOM;
        if (maxBufferSize > writer.getBufferLength()) {
            writer.setMaxBufSize(maxBufferSize);
        }
        try {
            JSONSerializer serializer = new JSONSerializer(writer);
            serializer.addFilter(limitLongStrings);
            serializer.write(value);
            String json = writer.toString();
            return json.length() <= cap ? json : truncateForLlm(json, cap);
        } catch (JSONException exception) {
            if (!isOutputLimitExceeded(exception)) {
                throw exception;
            }
            String partial = writer.toString();
            return truncateForLlm(partial + TRUNCATION_NOTICE, cap);
        }
    }

    /** fastjson 的缓冲区上限信号；其它序列化异常仍走调用方的兜底路径。 */
    private static boolean isOutputLimitExceeded(JSONException exception) {
        String message = exception.getMessage();
        return message != null && message.startsWith("serialize exceeded MAX_OUTPUT_LENGTH=");
    }

    public static String truncateForLlm(String value) {
        return truncateForLlm(value, DEFAULT_LLM_TOOL_STRING_CAP);
    }

    public static String truncateForLlm(String value, int cap) {
        String text = value == null ? "" : value;
        if (cap <= 0 || text.length() <= cap) {
            return text;
        }
        int noticeLen = TRUNCATION_NOTICE.length();
        if (cap <= noticeLen) {
            return text.substring(0, cap);
        }
        return text.substring(0, cap - noticeLen) + TRUNCATION_NOTICE;
    }
}
