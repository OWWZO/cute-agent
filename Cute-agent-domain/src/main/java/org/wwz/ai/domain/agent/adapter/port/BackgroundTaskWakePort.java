package org.wwz.ai.domain.agent.adapter.port;

/**
 * 后台任务完成后的续跑端口。
 * <p>domain 只负责生产通知并询问「是否已接管」；A/B/C 三态判活与唤醒轮派发在 case 层。</p>
 */
public interface BackgroundTaskWakePort {

    /**
     * 后台任务完成时调用。
     *
     * @return true 表示已接管续跑（调用方不要发 stream_settle，保持父流打开）
     */
    boolean onBackgroundTaskCompleted(BackgroundTaskCompletionEvent event);

    /** run 收口后复查：仍有未投递通知且当前空闲时补一次判活。 */
    void onRunSettled(String sessionId);

    /**
     * run 收口后复查，并释放本 run 未 ACK 的 Inbox 认领。
     */
    default void onRunSettled(String sessionId, String requestId) {
        onRunSettled(sessionId);
    }

    /**
     * 用户消息已持久化到 Session Inbox 后触发判活。
     */
    default boolean onInboxChanged(String sessionId) {
        return false;
    }

    /**
     * 当前会话是否暂停自动唤醒：用户已 Stop，或仍有 HELD 用户消息。
     */
    default boolean isAutoWakePaused(String sessionId) {
        return false;
    }
}
