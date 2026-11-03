package org.wwz.ai.domain.agent.runtime.enums;

/**
 * 消息角色类型枚举
 */
public enum RoleType {
    USER("user"),
    SYSTEM("system"),
    ASSISTANT("assistant"),
    TOOL("tool");

    private final String value;

    RoleType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /**
     * 检查角色值是否有效
     */
    public static boolean isValid(String role) {
        if (role == null) {
            return false;
        }
        for (RoleType type : RoleType.values()) {
            if (type.value.equals(role)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从字符串获取角色类型
     */
    public static RoleType fromString(String role) {
        if (role == null) {
            throw new IllegalArgumentException("Role must not be null");
        }
        for (RoleType type : RoleType.values()) {
            if (type.value.equals(role)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Invalid role: " + role);
    }
}