package org.wwz.ai.domain.agent.runtime.enums;

/**
 * Agent 增量响应类型枚举。
 * <p>常量名与对外 {@code contentType} 取值解耦：外部契约只认 {@link #getValue()}。</p>
 */
public enum ResponseTypeEnum {
    MARKDOWN("markdown"),
    TEXT("text"),
    CARD("card");

    private final String value;

    ResponseTypeEnum(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
