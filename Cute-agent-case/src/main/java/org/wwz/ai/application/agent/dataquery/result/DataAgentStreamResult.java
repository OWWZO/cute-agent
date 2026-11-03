package org.wwz.ai.application.agent.dataquery.result;

/**
 * Case 层数据查询流事件；Trigger 将其原样映射为 HTTP 事件 VO。
 *
 * @param eventType 对外 wire 事件名，取值为 {@code CHART_DATA}、{@code THINK}、{@code READY}、
 *                  {@code ERROR}、{@code DEBUG} 或 {@code finished_stream}
 * @param data      事件载荷；{@code CHART_DATA} 为数据结果列表，其余事件当前为字符串
 */
public record DataAgentStreamResult(String eventType, Object data) {
}
