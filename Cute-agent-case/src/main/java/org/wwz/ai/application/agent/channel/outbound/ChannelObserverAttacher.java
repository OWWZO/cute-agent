package org.wwz.ai.application.agent.channel.outbound;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.application.agent.stream.AgentStreamProjection;
import org.wwz.ai.application.agent.stream.SessionProjectionRegistry;
import org.wwz.ai.domain.agent.channel.port.ChannelAdapter;
import org.wwz.ai.domain.agent.channel.model.ChannelAttachmentRef;
import org.wwz.ai.domain.agent.channel.model.ChannelPushResult;
import org.wwz.ai.domain.agent.channel.model.ChannelTarget;
import org.wwz.ai.domain.agent.channel.policy.ChannelAttachmentFormatter;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerReadRepository;
import org.wwz.ai.domain.agent.ledger.entity.DialogueRun;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;

import java.util.List;

/**
 * 把一次 agent run 的终答送到 IM。
 * <p>两条路径：活投影还在就挂观察者（能收到流式终帧）；投影已结束就走执行账本兜底。</p>
 */
@Slf4j
public class ChannelObserverAttacher {

    private final SessionProjectionRegistry sessionProjectionRegistry;
    private final IExecutionLedgerReadRepository ledgerReadRepository;
    private final ChannelArtifactResolver artifactResolver;

    public ChannelObserverAttacher(SessionProjectionRegistry sessionProjectionRegistry,
                                   IExecutionLedgerReadRepository ledgerReadRepository,
                                   ChannelArtifactResolver artifactResolver) {
        this.sessionProjectionRegistry = sessionProjectionRegistry;
        this.ledgerReadRepository = ledgerReadRepository;
        this.artifactResolver = artifactResolver;
    }

    /**
     * @param adapter 该 binding 对应的出站适配器
     */
    public void attach(String sessionId, String requestId, ChannelTarget target, ChannelAdapter adapter) {
        ChannelFrameAggregator aggregator = new ChannelFrameAggregator(
                requestId, target, (t, text) -> deliver(t, text, requestId, adapter));

        List<AgentStreamProjection> live = sessionProjectionRegistry.listLive(sessionId);
        if (live.isEmpty()) {
            ledgerFallback(requestId, target, adapter);
            return;
        }
        boolean attachedAny = false;
        for (AgentStreamProjection projection : live) {
            try {
                // lastEventSeq=0：补发全部缓冲帧。重复帧由 aggregator 的 delivered 标志去重。
                projection.addDownstream(new ChannelObserverStream(aggregator), 0L);
                attachedAny = true;
            } catch (Exception e) {
                log.warn("IM observer attach failed requestId={} sessionId={}", requestId, sessionId, e);
            }
        }
        if (!attachedAny) {
            ledgerFallback(requestId, target, adapter);
        }
    }

    /**
     * 正文 + 附件段落拼成一条消息发出。
     * <p>正文失败时不再单独发附件：孤立的链接没有上下文。</p>
     */
    private ChannelPushResult deliver(ChannelTarget target, String text, String requestId, ChannelAdapter adapter) {
        String body = text;
        List<ChannelAttachmentRef> attachments = artifactResolver.resolve(requestId);
        if (!attachments.isEmpty()) {
            String block = ChannelAttachmentFormatter.format(attachments);
            if (StringUtils.isNotBlank(block)) {
                body = text + block;
            }
        }
        return adapter.pushText(target, body);
    }

    /**
     * run 已结束或投影不可用时，直接从账本取最终文本与产物。
     */
    private void ledgerFallback(String requestId, ChannelTarget target, ChannelAdapter adapter) {
        DialogueRun run;
        try {
            run = ledgerReadRepository.queryRunByRequestId(requestId);
        } catch (Exception e) {
            log.warn("IM fallback: ledger query failed requestId={}", requestId, e);
            return;
        }
        if (run == null || !isTerminal(run.getStatus())) {
            log.warn("IM fallback: run not found or not terminal requestId={}", requestId);
            return;
        }
        String text = StringUtils.defaultString(run.getFinalSummaryText());
        String block = ChannelAttachmentFormatter.format(artifactResolver.resolve(requestId));
        String body = text + block;
        if (StringUtils.isBlank(body)) {
            log.warn("IM fallback: nothing to deliver requestId={}", requestId);
            return;
        }
        ChannelPushResult result = adapter.pushText(target, body);
        if (!result.success()) {
            log.warn("IM fallback push failed requestId={} error={}", requestId, result.error());
        }
    }

    private static boolean isTerminal(Integer status) {
        if (status == null) {
            return false;
        }
        return status != ExecutionLedgerConstants.STATUS_RUNNING;
    }
}
