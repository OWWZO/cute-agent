package org.wwz.ai.domain.agent.cute.model.enums;

/**
 * 延期保留的事件类别枚举，描述数据、思考、可输入、异常和调试事件。
 * <p>常量名通过 {@code name()} 直接作为对外事件协议值，禁止重命名或删除已有常量；
 * 新增常量只能追加。</p>
 */
public enum EventTypeEnum {
    /**
     * 数据
     */
    CHART_DATA,
    /**
     * THINK流式消息
     */
    THINK,
    /**
     * 用户可输入
     */
    READY,
    /**
     * 异常
     */
    ERROR,
    /**
     * debug信息
     */
    DEBUG;
}
