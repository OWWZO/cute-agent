package org.wwz.ai.domain.agent.runtime.agent;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具调用链路共用的 JSON 归一。
 * <p>
 * 入参可能来自模型、MCP 或内部拼装，形态不保证是合法 JSON；账本和 SSE 卡片都要求稳定文本，
 * 因此统一在这里退化为 {@code "{}"}，避免每个调用点各写一套 try/catch。
 */
final class ToolCallJson {

    private static final Logger log = LoggerFactory.getLogger(ToolCallJson.class);
    private static final int LOG_PAYLOAD_LIMIT = 512;

    /** 小数保留为 BigDecimal，避免高精度坐标/金额在归一过程中丢精度。 */
    static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private ToolCallJson() {
    }

    /** 归一为合法 JSON 文本；空值或非法 JSON 退化为 "{}"。 */
    static String normalize(String payload) {
        if (StringUtils.isBlank(payload)) {
            return "{}";
        }
        try {
            JsonNode node = MAPPER.readTree(payload);
            // "null" 会解析成 NullNode；数组/标量同样不是工具入参对象，统一退化为 {}。
            return node != null && node.isObject() ? node.toString() : "{}";
        } catch (Exception e) {
            // 退化本身必须可观测：否则损坏的模型输出会被当成「模型传了空参数」。
            log.warn("tool args json normalize failed, degrade to {}: payload={} error={}",
                    abbreviate(payload), e.getMessage());
            return "{}";
        }
    }

    /** 归一后解析成通用对象；失败返回 null（SSE 卡片允许没有入参）。 */
    static Object parseOrNull(String payload) {
        try {
            return MAPPER.readValue(normalize(payload), Object.class);
        } catch (Exception ignore) {
            return null;
        }
    }

    /** 序列化为 JSON 文本；失败退化为 "{}"。 */
    static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("tool args json write failed, degrade to {}: {}", e.getMessage());
            return "{}";
        }
    }

    private static String abbreviate(String payload) {
        return payload.length() <= LOG_PAYLOAD_LIMIT
                ? payload
                : payload.substring(0, LOG_PAYLOAD_LIMIT) + "...(truncated, total=" + payload.length() + ")";
    }
}
