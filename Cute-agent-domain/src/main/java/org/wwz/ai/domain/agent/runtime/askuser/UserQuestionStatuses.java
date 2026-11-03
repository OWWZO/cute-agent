package org.wwz.ai.domain.agent.runtime.askuser;

import java.util.List;

/**
 * AskUserQuestion 持久化状态（附属表，非 ledger）。
 */
public final class UserQuestionStatuses {

    public static final String PENDING = "PENDING";
    public static final String RESUME_PENDING = "RESUME_PENDING";
    public static final String RESUMING = "RESUMING";
    public static final String ANSWERED = "ANSWERED";
    public static final String TIMEOUT = "TIMEOUT";
    public static final String CANCELLED = "CANCELLED";
    public static final String FAILED = "FAILED";

    public static final List<String> OPEN = List.of(PENDING, RESUME_PENDING, RESUMING);
    public static final List<String> CANCELABLE = List.of(PENDING, RESUME_PENDING);
    /** 续跑收口允许写入的目标状态（{@code markStatus}）。 */
    public static final List<String> RESUME_OUTCOMES = List.of(ANSWERED, FAILED, RESUME_PENDING);

    private UserQuestionStatuses() {
    }

    public static boolean isOpen(String status) {
        // 显式判空：List.of(...).contains(null) 会抛 NPE，这里保持对 null 返回 false。
        return status != null && OPEN.contains(status);
    }
}
