package org.wwz.ai.domain.agent.runtime.tool.mcp.model;

import java.util.regex.Pattern;

/**
 * MCP 工具命名策略（mcp__{server}__{tool}）。
 * 模型侧使用 FQ 名；tools/call 仍使用服务端原始工具名。
 */
public final class McpToolNamePolicy {

    public static final String PREFIX = "mcp__";
    public static final String SEPARATOR = "__";
    /** 每次构建工具列表都会对 server/tool 片段归一化，正则必须预编译。 */
    private static final Pattern INVALID_SEGMENT_CHARS = Pattern.compile("[^a-zA-Z0-9_-]");
    /** 片段内不允许出现 "__"，否则 FQ 名无法再按首个分隔符还原 server。 */
    private static final Pattern UNDERSCORE_RUN = Pattern.compile("_{2,}");

    private McpToolNamePolicy() {
    }

    /**
     * 规范化 server / tool 片段：非 [a-zA-Z0-9_-] 替换为下划线，截断到 64。
     */
    public static String normalizeSegment(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        String normalized = INVALID_SEGMENT_CHARS.matcher(raw.trim()).replaceAll("_");
        normalized = UNDERSCORE_RUN.matcher(normalized).replaceAll("_");
        if (normalized.isEmpty()) {
            return "unknown";
        }
        return normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
    }

    /**
     * 构建模型可见的 FQ 工具名。
     */
    public static String buildQualifiedName(String serverKey, String originalToolName) {
        return PREFIX
                + normalizeSegment(serverKey)
                + SEPARATOR
                + normalizeSegment(originalToolName);
    }

    public static boolean isQualifiedName(String name) {
        if (name == null || name.isBlank() || !name.startsWith(PREFIX)) {
            return false;
        }
        String body = name.substring(PREFIX.length());
        int idx = body.indexOf(SEPARATOR);
        return idx > 0 && idx < body.length() - SEPARATOR.length();
    }

    /**
     * 从 FQ 名解析 server 段；非 FQ 返回 null。
     * <p>返回的是规范化后的片段（非字母数字字符已替换、截断到 64），不是原始 serverKey；
     * 原始工具名同样无法从 FQ 名还原，调用方需自行维护 FQ 名到原始名的映射。</p>
     */
    public static String parseServerKey(String qualifiedName) {
        if (!isQualifiedName(qualifiedName)) {
            return null;
        }
        String body = qualifiedName.substring(PREFIX.length());
        int idx = body.indexOf(SEPARATOR);
        return body.substring(0, idx);
    }
}
