package org.wwz.ai.domain.agent.rag.model.query;

/**
 * Protocol-neutral progress or completion event for a Data Query stream.
 * <p>{@code eventType} 必须非空且由生产者统一约定；{@code data} 可能持有可变对象，
 * 发布后不得再修改（可能被多个消费者与序列化路径共享）。</p>
 */
public record DataQueryStreamEvent(String eventType, Object data) {
}
