package org.wwz.ai.domain.agent.runtime.dto.tool;

/**
 * 工具选择类型枚举
 */
public enum ToolChoice {
    NONE("none"),
    AUTO("auto"),
    REQUIRED("required");

    private final String value;

    ToolChoice(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /**
     * 检查工具选择值是否有效
     */
    public static boolean isValid(ToolChoice toolChoice) {
        return toolChoice != null;
    }

    /**
     * 从字符串获取工具选择类型
     */
    public static ToolChoice fromString(String toolChoice) {
        for (ToolChoice choice : ToolChoice.values()) {
            if (choice.getValue().equals(toolChoice)) {
                return choice;
            }
        }
        throw new IllegalArgumentException("Invalid tool choice: " + toolChoice);
    }
}