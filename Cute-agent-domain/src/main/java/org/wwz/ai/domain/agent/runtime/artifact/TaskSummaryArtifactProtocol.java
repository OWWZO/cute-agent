package org.wwz.ai.domain.agent.runtime.artifact;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.runtime.dto.TaskSummaryResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 终答正文原样透传；{@code $$$} 点名只抽 keys，不在后端对工作区文件。
 * 展示卡片由前端用会话文件表按相对路径/文件名映射。
 */
public final class TaskSummaryArtifactProtocol {

    private static final Pattern ARTIFACT_SPLIT_PATTERN =
            Pattern.compile(ToolArtifactFormatter.ARTIFACT_KEY_SEPARATOR_REGEX);
    private static final Pattern DELIMITER_RUN_PATTERN =
            Pattern.compile("\\$\\$\\$(?:[ \\t]*\\R[ \\t]*\\$\\$\\$)*");
    private static final Pattern TRAILING_DELIMITER_PATTERN =
            Pattern.compile("(?:\\R[ \\t]*\\$\\$\\$[ \\t]*)+\\s*$");
    private static final Pattern MARKDOWN_LINK_PATTERN =
            Pattern.compile("^\\[[^\\]]*]\\(([^)]+)\\)$");
    private static final Pattern INVALID_PATH_CHAR_PATTERN =
            Pattern.compile("[\\s*`?#<>|\"'，,。；;：:！？!]");
    private static final Pattern ARTIFACT_BASENAME_PATTERN =
            Pattern.compile("(?:[^/\\\\]+\\.[A-Za-z0-9][A-Za-z0-9._-]{0,15}|[A-Za-z0-9_.-]+)");

    private TaskSummaryArtifactProtocol() {
    }

    /**
     * 终答原文保留 {@code $$$}；只解析点名 keys，不对 visibleBindings。
     *
     * @param visibleBindings 仅为签名兼容保留，当前不参与解析/过滤
     */
    public static TaskSummaryResult parse(String llmResponse, List<ToolArtifactBinding> visibleBindings) {
        return parse(llmResponse);
    }

    public static TaskSummaryResult parse(String llmResponse) {
        String raw = StringUtils.trimToEmpty(llmResponse);
        if (raw.isEmpty()) {
            return TaskSummaryResult.builder().taskSummary("").build();
        }
        return TaskSummaryResult.builder()
                .taskSummary(raw)
                .artifactKeys(extractArtifactKeys(raw))
                .build();
    }

    /**
     * 终答交付只认 $$$ 点名；匹配与展示交给前端。
     */
    public static TaskSummaryResult resolveForDelivery(
            String llmResponse,
            List<ToolArtifactBinding> visibleBindings
    ) {
        return parse(llmResponse);
    }

    /** 构造与实时/历史 replay 共用的 result event payload。 */
    public static Map<String, Object> toEventPayload(TaskSummaryResult result) {
        return toEventPayload(result, List.of());
    }

    /**
     * 终答 payload 只带原文和点名 keys，不附 fileList。
     *
     * @param visibleBindings 仅为签名兼容保留，当前不参与过滤
     */
    public static Map<String, Object> toEventPayload(
            TaskSummaryResult result,
            List<ToolArtifactBinding> visibleBindings
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskSummary", result == null ? "" : StringUtils.defaultString(result.getTaskSummary()));
        if (result != null && CollectionUtils.isNotEmpty(result.getArtifactKeys())) {
            payload.put("artifactKeys", result.getArtifactKeys());
        }
        return payload;
    }

    /** 按分隔符拆分 {@code $$$} 点名并去空；实时与历史回放共用同一份解析规则。 */
    public static List<String> splitArtifactKeys(String artifactKeys) {
        if (StringUtils.isBlank(artifactKeys)) {
            return List.of();
        }
        String[] parts = ARTIFACT_SPLIT_PATTERN.split(artifactKeys);
        List<String> result = new ArrayList<>(parts.length);
        for (String part : parts) {
            String normalized = normalizeArtifactKey(part);
            if (StringUtils.isNotBlank(normalized)) {
                result.add(normalized);
            }
        }
        return result;
    }

    /**
     * 从终答中找最后一个合法的交付文件段。
     *
     * 正文中可能只是提到 {@code $$$}，因此不能见到分隔符就切；
     * 分隔符后面的内容必须整体像文件路径列表。
     */
    public static List<String> extractArtifactKeys(String llmResponse) {
        String raw = StringUtils.trimToEmpty(llmResponse);
        List<int[]> candidates = new ArrayList<>();
        Matcher matcher = DELIMITER_RUN_PATTERN.matcher(raw);
        while (matcher.find()) {
            candidates.add(new int[]{matcher.start(), matcher.end()});
        }

        for (int index = candidates.size() - 1; index >= 0; index--) {
            int[] candidate = candidates.get(index);
            String suffix = raw.substring(candidate[1]).trim();
            suffix = TRAILING_DELIMITER_PATTERN.matcher(suffix).replaceFirst("").trim();
            if (suffix.isEmpty()) {
                String marker = raw.substring(candidate[0], candidate[1]);
                if (!marker.contains("\n") && !isDelimiterLine(raw, candidate[0], candidate[1])) {
                    continue;
                }
                if (index > 0) {
                    continue;
                }
                return List.of();
            }

            List<String> artifactKeys = splitArtifactKeys(suffix);
            if (!artifactKeys.isEmpty() && artifactKeys.stream().allMatch(TaskSummaryArtifactProtocol::isPlausibleArtifactKey)) {
                return artifactKeys;
            }
        }
        return List.of();
    }

    private static boolean isDelimiterLine(String raw, int start, int end) {
        int lineStart = raw.lastIndexOf('\n', start - 1) + 1;
        int lineEnd = raw.indexOf('\n', end);
        return raw.substring(lineStart, start).isBlank()
                && raw.substring(end, lineEnd >= 0 ? lineEnd : raw.length()).isBlank();
    }

    private static String normalizeArtifactKey(String raw) {
        String text = StringUtils.trimToEmpty(raw);
        if (text.isEmpty()) {
            return "";
        }
        text = text.replace(ToolArtifactFormatter.ARTIFACT_DELIMITER, " ").trim();
        text = text.replaceFirst("^[-*+]\\s+", "")
                .replaceFirst("^\\d+[.)]\\s+", "")
                .trim();
        text = text.replaceFirst("^[*`]+", "")
                .replaceFirst("[`*]+$", "")
                .trim();
        Matcher markdownLink = MARKDOWN_LINK_PATTERN.matcher(text);
        if (markdownLink.matches()) {
            text = markdownLink.group(1).trim();
        }
        text = text.replaceFirst("^['\"]+", "")
                .replaceFirst("['\"]+$", "")
                .trim();
        return text.replaceFirst("[。.;]+$", "").trim();
    }

    private static boolean isPlausibleArtifactKey(String key) {
        if (StringUtils.isBlank(key)
                || key.length() > 512
                || key.contains(ToolArtifactFormatter.ARTIFACT_DELIMITER)
                || key.matches(".*[/\\\\]$")
                || INVALID_PATH_CHAR_PATTERN.matcher(key).find()) {
            return false;
        }
        int separator = Math.max(key.lastIndexOf('/'), key.lastIndexOf('\\'));
        String basename = separator >= 0 ? key.substring(separator + 1) : key;
        return ARTIFACT_BASENAME_PATTERN.matcher(basename).matches();
    }
}
