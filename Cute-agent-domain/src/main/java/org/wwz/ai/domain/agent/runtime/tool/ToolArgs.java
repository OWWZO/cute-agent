package org.wwz.ai.domain.agent.runtime.tool;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.parser.ParserConfig;
import org.apache.commons.lang3.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具入参归一化。
 * <p>
 * 模型经 function calling 传来的 {@code input} 形态并不稳定：有时是 Map，有时是 JSON 字符串，
 * 有时是别的 POJO。这里统一收敛为只读语义的 {@code Map<String, Object>}，
 * 供各工具在 {@code execute} 开头一次性取值。
 */
public final class ToolArgs {

    /**
     * 模型可控文本一律用 safeMode 解析：显式关闭 autoType，禁止 {@code @type}
     * 触发任意类实例化或 getter 副作用，不依赖 fastjson 的全局默认配置。
     */
    private static final ParserConfig SAFE_PARSER_CONFIG = new ParserConfig();

    static {
        SAFE_PARSER_CONFIG.setSafeMode(true);
    }

    private ToolArgs() {
    }

    /**
     * 宽松归一：接受 Map / JSON 字符串 / 任意可序列化对象，无法识别时返回空 map。
     * <p>
     * 返回的 map 键统一为 String，且<b>一律不可修改</b>：所有分支（含空/非法输入）
     * 返回同一可写性，调用方若试图 {@code put}/{@code remove} 会立刻抛
     * {@link UnsupportedOperationException}，而不是只在边界输入上炸。
     */
    public static Map<String, Object> map(Object input) {
        if (input instanceof Map<?, ?> map) {
            return unmodifiable(stringKeyed(map));
        }
        if (input == null) {
            return Map.of();
        }
        if (input instanceof String text && StringUtils.isNotBlank(text)) {
            try {
                Object parsed = JSON.parse(text, SAFE_PARSER_CONFIG);
                if (parsed instanceof Map<?, ?> map) {
                    return unmodifiable(stringKeyed(map));
                }
            } catch (Exception ignored) {
                // 非法 JSON 落到下面的兜底
            }
            return Map.of();
        }
        try {
            JSONObject obj = JSON.parseObject(JSON.toJSONString(input));
            return obj == null ? Map.of() : unmodifiable(new LinkedHashMap<>(obj));
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    /**
     * 严格归一：只接受 Map，其它一律返回空 map。
     * <p>
     * 适用于入参本来就必须是结构化对象的工具，避免把字符串误当参数表。
     */
    public static Map<String, Object> mapStrict(Object input) {
        if (input instanceof Map<?, ?> map) {
            return unmodifiable(stringKeyed(map));
        }
        return Map.of();
    }

    private static Map<String, Object> unmodifiable(Map<String, Object> values) {
        return Collections.unmodifiableMap(values);
    }

    /** 取值并 trim；缺失或空白返回空串。 */
    public static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static Map<String, Object> stringKeyed(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>(map.size());
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }
}
