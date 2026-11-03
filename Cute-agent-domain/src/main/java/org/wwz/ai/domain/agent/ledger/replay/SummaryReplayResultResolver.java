package org.wwz.ai.domain.agent.ledger.replay;

import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.runtime.artifact.TaskSummaryArtifactProtocol;
import org.wwz.ai.domain.agent.runtime.artifact.ToolArtifactFormatter;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 历史回放里的总结结果解析器。
 * 账本保存终答原文（含 {@code $$$} 点名）；不在回放阶段对 artifact 表做交付匹配，
 * 前端用会话文件表按相对路径/文件名映射展示卡片。
 */
public final class SummaryReplayResultResolver {

    private SummaryReplayResultResolver() {
    }

    public static ResolvedSummary resolve(String rawSummaryText) {
        String raw = StringUtils.defaultString(rawSummaryText);
        String[] parts = raw.split(Pattern.quote(ToolArtifactFormatter.ARTIFACT_DELIMITER), 2);
        List<String> requestedKeys = parts.length < 2
                ? List.of()
                : TaskSummaryArtifactProtocol.splitArtifactKeys(parts[1]);
        return new ResolvedSummary(raw, requestedKeys);
    }

    public static final class ResolvedSummary {

        private final String summaryText;
        private final List<String> artifactKeys;

        public ResolvedSummary(String summaryText, List<String> artifactKeys) {
            this.summaryText = summaryText;
            this.artifactKeys = artifactKeys == null ? List.of() : artifactKeys;
        }

        public String getSummaryText() {
            return summaryText;
        }

        public List<String> getArtifactKeys() {
            return artifactKeys;
        }
    }
}
