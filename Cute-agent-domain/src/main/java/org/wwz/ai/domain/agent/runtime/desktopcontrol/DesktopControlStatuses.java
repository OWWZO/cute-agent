package org.wwz.ai.domain.agent.runtime.desktopcontrol;

import java.util.List;

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
