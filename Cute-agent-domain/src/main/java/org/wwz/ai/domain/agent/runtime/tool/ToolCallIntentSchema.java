package org.wwz.ai.domain.agent.runtime.tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 代码型工具调用的统一意图字段定义。
 */
public final class ToolCallIntentSchema {

    public static final String FIELD = "intent";

    private static final String DESCRIPTION =
            "用一句面向用户的话说明本次调用的目的：要做什么、处理什么对象、希望得到什么结果。"
                    + "不要复述命令或源码，不要写推理过程，不要包含密钥、Cookie 或 Token。";

    private static final String DESCRIPTION_SUFFIX =
            "\n调用时必须提供 intent：" + DESCRIPTION;

    private ToolCallIntentSchema() {
    }

    public static Map<String, Object> property() {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "string");
        property.put("description", DESCRIPTION);
        property.put("minLength", 1);
        property.put("maxLength", 160);
        return property;
    }

    public static String descriptionSuffix() {
        return DESCRIPTION_SUFFIX;
    }
}
