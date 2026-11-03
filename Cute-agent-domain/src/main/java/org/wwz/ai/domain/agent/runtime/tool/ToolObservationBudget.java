package org.wwz.ai.domain.agent.runtime.tool;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 单工具 observation 预算：超限把完整内容落到工作区，只回一个可续读的信封。
 * <p>
 * 对齐 zCode 的 {@code resultBudget} + {@code <persisted-output>}。落本地工作区而不是上传文件服务，
 * 是因为模型有 {@code workspace_read}，可以直接续读 —— 这才是“可执行的续读指令”。
 * 落盘失败一律退回截断，绝不丢内容。
 */
@Slf4j
public final class ToolObservationBudget {

    /**
     * 默认上限：所有未单独登记的工具都走这个预算。
     * 对齐 zCode 的 {@code MAX_INLINE_OUTPUT_BYTES}（bash 30 KB）。
     */
    public static final int DEFAULT_CAP = 30_000;

    /** grep / glob / list 上限，对齐 zCode 的 MAX_GREP_MODEL_BYTES。 */
    public static final int GREP_GLOB_LIST_CAP = 20_000;

    /** read 上限：正文本身是模型要用的，比检索类宽松一些。 */
    public static final int READ_CAP = 30_000;

    /** 检索类上限：命中列表是候选集，不需要整段进上下文。 */
    public static final int SEARCH_CAP = 20_000;

    /** 信封里的预览字符数，对齐 zCode 的 PERSISTED_OUTPUT_PREVIEW_CHARS。 */
    static final int PREVIEW_CHARS = 2_000;

    /** 只登记与默认值不同的工具，其余一律 {@link #DEFAULT_CAP}。 */
    private static final Map<String, Integer> CAPS_BY_TOOL = Map.of(
            "workspace_read", READ_CAP,
            "workspace_grep", GREP_GLOB_LIST_CAP,
            "workspace_glob", GREP_GLOB_LIST_CAP,
            "workspace_list", GREP_GLOB_LIST_CAP,
            "WebSearch", SEARCH_CAP
    );

    private static final String OBSERVATION_DIR = ".cute/observations";

    /** 每次落盘最多保留的观测文件数：超出按修改时间删最旧的，避免长会话无界占用工作区。 */
    private static final int MAX_PERSISTED_FILES = 200;

    /** 批量工具并行执行时同一毫秒可能撞名，序号保证文件互不覆盖。 */
    private static final java.util.concurrent.atomic.AtomicLong PERSIST_SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    private ToolObservationBudget() {
    }

    /**
     * 空工具名或工作区不可用时返回原文本（截断由 {@code maxObserve} 兜底）。
     */
    public static String apply(AgentContext context, String toolName, String observation) {
        if (context == null || StringUtils.isBlank(observation) || toolName == null) {
            return observation;
        }
        int cap = CAPS_BY_TOOL.getOrDefault(toolName, DEFAULT_CAP);
        if (observation.length() <= cap) {
            return observation;
        }
        Path file = persist(context, toolName, observation);
        if (file == null) {
            return ToolObservationSerializer.truncateForLlm(observation, cap);
        }
        // persist() 仅在 workspaceRoot 非空白时返回文件，这里直接相对化。
        String relative = relativize(Path.of(context.getWorkspaceRoot()), file);
        return envelope(observation.length(), relative, observation.substring(0, PREVIEW_CHARS));
    }

    private static Path persist(AgentContext context, String toolName, String observation) {
        if (StringUtils.isBlank(context.getWorkspaceRoot())) {
            return null;
        }
        try {
            Path dir = Path.of(context.getWorkspaceRoot()).resolve(OBSERVATION_DIR).normalize();
            Files.createDirectories(dir);
            Path file = dir.resolve(safeToolName(toolName) + "-" + System.currentTimeMillis()
                    + "-" + PERSIST_SEQ.incrementAndGet() + ".txt").normalize();
            // 纵深防御：即使上面的清洗未来被放宽，也不允许最终路径逃出 observation 目录。
            if (!file.startsWith(dir)) {
                log.warn("persist oversized observation rejected: path escapes observation dir, tool={}", toolName);
                return null;
            }
            Files.writeString(file, observation, StandardCharsets.UTF_8);
            pruneOldObservations(dir, file);
            return file;
        } catch (Exception e) {
            log.warn("persist oversized observation failed, tool={}", toolName, e);
            return null;
        }
    }

    /**
     * 工具名来自 MCP / Python / 外部工具注册，不可信：只保留安全字符集，
     * 去掉首尾的点与横线，避免 {@code ..} 或绝对路径参与文件名拼接。
     */
    static String safeToolName(String toolName) {
        String cleaned = toolName == null ? "" : toolName.trim().replaceAll("[^A-Za-z0-9._-]+", "-");
        cleaned = cleaned.replaceAll("^[.-]+|[.-]+$", "");
        if (cleaned.isBlank()) {
            return "tool";
        }
        return cleaned.length() <= 80 ? cleaned : cleaned.substring(0, 80);
    }

    /**
     * 保留最新的 {@link #MAX_PERSISTED_FILES} 个观测文件。
     * <p>清理失败不影响本次落盘（内容已在盘上、信封已可续读），仅记录日志。</p>
     */
    private static void pruneOldObservations(Path dir, Path keep) {
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            java.util.List<Path> sorted = files
                    .filter(Files::isRegularFile)
                    .sorted(java.util.Comparator.comparingLong(ToolObservationBudget::lastModified))
                    .collect(java.util.stream.Collectors.toList());
            int excess = sorted.size() - MAX_PERSISTED_FILES;
            for (int i = 0; i < excess; i++) {
                Path candidate = sorted.get(i);
                if (!candidate.equals(keep)) {
                    Files.deleteIfExists(candidate);
                }
            }
        } catch (Exception e) {
            log.warn("prune persisted observations failed, dir={}", dir, e);
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    private static String envelope(int length, String path, String preview) {
        return "<persisted-output>\n"
                + "Output too large (" + (length / 1024) + " KB). Full output saved to: " + path + "\n\n"
                + "Preview (first 2 KB):\n"
                + preview + "\n"
                + "</persisted-output>";
    }

    private static String relativize(Path root, Path file) {
        try {
            return root.toAbsolutePath().normalize()
                    .relativize(file.toAbsolutePath().normalize())
                    .toString()
                    .replace('\\', '/');
        } catch (Exception e) {
            return file.toString();
        }
    }
}
