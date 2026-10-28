package org.wwz.ai.application.agent.channel.outbound;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.channel.model.ChannelAttachmentRef;
import org.wwz.ai.domain.agent.channel.policy.ChannelUrlPolicy;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerReadRepository;
import org.wwz.ai.domain.agent.ledger.entity.ArtifactRecord;
import org.wwz.ai.domain.agent.ledger.entity.DialogueRun;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;

import java.util.ArrayList;
import java.util.List;

/**
 * 把一次 run 的输出产物转成 IM 消息里的附件条目。
 * <p>从 {@code ai_agent_artifact} 查而不是解析正文：产物是结构化的，文本里没有可靠清单。</p>
 */
@Slf4j
public class ChannelArtifactResolver {

    private final IExecutionLedgerReadRepository ledgerReadRepository;
    private final ChannelUrlPolicy urlPolicy;

    public ChannelArtifactResolver(IExecutionLedgerReadRepository ledgerReadRepository,
                                   ChannelUrlPolicy urlPolicy) {
        this.ledgerReadRepository = ledgerReadRepository;
        this.urlPolicy = urlPolicy;
    }

    /**
     * @return 输出且可见的产物；查询失败返回空列表（附件缺失不影响正文投递）
     */
    public List<ChannelAttachmentRef> resolve(String requestId) {
        if (StringUtils.isBlank(requestId)) {
            return List.of();
        }
        try {
            DialogueRun run = ledgerReadRepository.queryRunByRequestId(requestId);
            if (run == null || run.getId() == null) {
                return List.of();
            }
            List<ArtifactRecord> artifacts = ledgerReadRepository.queryArtifactsByRunId(run.getId());
            if (artifacts == null || artifacts.isEmpty()) {
                return List.of();
            }
            List<ChannelAttachmentRef> refs = new ArrayList<>(artifacts.size());
            for (ArtifactRecord artifact : artifacts) {
                if (artifact == null || StringUtils.isBlank(artifact.getFileName())) {
                    continue;
                }
                if (!ExecutionLedgerConstants.ARTIFACT_ROLE_OUTPUT.equals(artifact.getArtifactRole())) {
                    continue;
                }
                if (!ExecutionLedgerConstants.VISIBILITY_VISIBLE.equals(artifact.getVisibility())) {
                    continue;
                }
                // clickableUrl 为空表示「当前环境给不出可达链接」，仍要展示文件名。
                refs.add(new ChannelAttachmentRef(
                        artifact.getFileName(),
                        urlPolicy.toClickableUrl(artifact.getPreviewUrl(), requestId, artifact.getFileName())));
            }
            return refs;
        } catch (Exception e) {
            log.warn("resolve artifacts failed requestId={}", requestId, e);
            return List.of();
        }
    }
}
