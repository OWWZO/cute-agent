package org.wwz.ai.domain.agent.rag.model.query;

import org.wwz.ai.domain.agent.cute.model.enums.EventTypeEnum;

/**
 * Data Query 流事件类型。
 * <p>{@link #wireValue()} 是对外 SSE 协议值：前五个与 {@link EventTypeEnum} 的常量名一一对应，
 * {@link #FINISHED_STREAM} 沿用 Nl2SqlQueryService 的历史字面量 {@code finished_stream}。
 * 发布到前端/回放时必须使用 {@code wireValue()}，不能直接使用枚举的 {@code name()}。</p>
 */
public enum DataQueryEventType {
    CHART_DATA(EventTypeEnum.CHART_DATA.name()),
    THINK(EventTypeEnum.THINK.name()),
    READY(EventTypeEnum.READY.name()),
    ERROR(EventTypeEnum.ERROR.name()),
    DEBUG(EventTypeEnum.DEBUG.name()),
    /** Nl2SqlQueryService 历史上直接发送的字面量，不属于 EventTypeEnum。 */
    FINISHED_STREAM("finished_stream");

    private final String wireValue;

    DataQueryEventType(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
