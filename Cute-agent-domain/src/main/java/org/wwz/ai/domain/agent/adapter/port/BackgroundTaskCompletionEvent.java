package org.wwz.ai.domain.agent.adapter.port;

/**
 * 后台任务到达终态时向应用层发出的事件，用于判活与自动续跑。
 */
public record BackgroundTaskCompletionEvent(
        String sessionId,
        String requestId,
        String taskId,
        String agentId,
        String childSessionId,
        String status,
        String summary) {
}
