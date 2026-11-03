package org.wwz.ai.domain.agent.rag.model.query;

import java.util.List;
import java.util.Objects;

/**
 * Protocol-neutral progress or completion event for a Data Query stream.
 * <p>{@code eventType} 必须非空；{@code data} 只允许文本帧或结果帧，
 * 发布后不得再修改（可能被多个消费者与序列化路径共享）。</p>
 */
public record DataQueryStreamEvent(DataQueryEventType eventType, Payload data) {

    public DataQueryStreamEvent {
        Objects.requireNonNull(eventType, "eventType 不能为空");
        Objects.requireNonNull(data, "data 不能为空");
    }

    public static DataQueryStreamEvent text(DataQueryEventType eventType, String text) {
        return new DataQueryStreamEvent(eventType, new TextPayload(text));
    }

    public static DataQueryStreamEvent chartData(List<DataQueryResult> results) {
        return new DataQueryStreamEvent(DataQueryEventType.CHART_DATA, new ResultPayload(results));
    }

    /** 事件载荷；{@link #wireValue()} 保持原有 wire 形状（字符串或结果数组）。 */
    public sealed interface Payload permits TextPayload, ResultPayload {
        Object wireValue();
    }

    public record TextPayload(String text) implements Payload {
        @Override
        public Object wireValue() {
            return text;
        }
    }

    public record ResultPayload(List<DataQueryResult> results) implements Payload {
        @Override
        public Object wireValue() {
            return results;
        }
    }
}
