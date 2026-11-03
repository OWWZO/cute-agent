package org.wwz.ai.domain.agent.runtime.tasklist;

import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * 后台任务完成通知的模型可见文本（XML 形状对齐 ZCode）。
 * <p>通知只注入模型上下文，不写用户可见 transcript。</p>
 */
public final class BackgroundTaskNotificationFormatter {

    static final int MAX_RESULT_CHARS = 120_000;

    private BackgroundTaskNotificationFormatter() {
    }

    public static String formatBatch(List<TaskNotification> notifications) {
        if (notifications == null || notifications.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (TaskNotification notification : notifications) {
            if (notification == null) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append(format(notification));
        }
        return sb.toString();
    }

    public static String format(TaskNotification notification) {
        if (notification == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(256);
        sb.append("<task-notification>\n");
        appendTag(sb, "task-id", notification.taskId());
        appendTag(sb, "agent-id", notification.agentId());
        appendTag(sb, "child-session-id", notification.childSessionId());
        appendTag(sb, "tool-use-id", notification.parentToolUseId());
        appendTag(sb, "status", notification.status());
        appendTag(sb, "summary", summary(notification));
        String result = truncate(notification.result());
        if (StringUtils.isNotBlank(result)) {
            appendTag(sb, "result", result);
        }
        if (StringUtils.isNotBlank(notification.errorMsg())) {
            appendTag(sb, "error", notification.errorMsg());
        }
        String usage = usage(notification);
        if (StringUtils.isNotBlank(usage)) {
            appendTag(sb, "usage", usage);
        }
        sb.append("</task-notification>");
        return sb.toString();
    }

    private static String summary(TaskNotification notification) {
        String description = StringUtils.defaultIfBlank(
                notification.description(),
                StringUtils.defaultIfBlank(notification.agentId(), notification.taskId()));
        if (notification.completed()) {
            return "后台任务 \"" + description + "\" 已完成";
        }
        String reason = StringUtils.defaultIfBlank(notification.errorMsg(), "未知原因");
        return "后台任务 \"" + description + "\" 失败：" + reason;
    }

    private static String usage(TaskNotification notification) {
        StringBuilder usage = new StringBuilder();
        if (notification.totalToolUseCount() != null) {
            usage.append("tools=").append(notification.totalToolUseCount());
        }
        if (notification.totalDurationMs() != null) {
            if (!usage.isEmpty()) {
                usage.append(", ");
            }
            usage.append("duration=").append(notification.totalDurationMs()).append("ms");
        }
        return usage.toString();
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        if (text.length() <= MAX_RESULT_CHARS) {
            return text;
        }
        return text.substring(0, MAX_RESULT_CHARS) + "\n[truncated]";
    }

    private static void appendTag(StringBuilder sb, String tag, String value) {
        if (StringUtils.isBlank(value)) {
            return;
        }
        sb.append('<').append(tag).append('>')
                .append(escapeXml(value))
                .append("</").append(tag).append(">\n");
    }

    static String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&apos;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
