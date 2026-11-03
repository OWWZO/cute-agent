package org.wwz.ai.domain.agent.runtime.desktopcontrol;

import java.util.List;

/**
 * 桌面控制任务状态常量。
 * <p>PENDING / RESUME_PENDING / RESUMING 是开放态（{@link #OPEN}）；COMPLETED / CANCELLED / FAILED 是终态。
 * RESUMING 表示续跑已被认领、正在执行，此时取消会与续跑收口竞争，因此 {@link #CANCELABLE}
 * 只包含 PENDING / RESUME_PENDING——这是有意为之，不是遗漏。</p>
 */
public final class DesktopControlStatuses {

    public static final String PENDING = "PENDING";
    public static final String RESUME_PENDING = "RESUME_PENDING";
    public static final String RESUMING = "RESUMING";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";
    public static final String FAILED = "FAILED";

    public static final List<String> OPEN = List.of(PENDING, RESUME_PENDING, RESUMING);
    public static final List<String> CANCELABLE = List.of(PENDING, RESUME_PENDING);

    private DesktopControlStatuses() {
    }

    public static boolean isOpen(String status) {
        // 显式判空：List.of(...).contains(null) 会抛 NPE，这里保持对 null 返回 false。
        return status != null && OPEN.contains(status);
    }
}
