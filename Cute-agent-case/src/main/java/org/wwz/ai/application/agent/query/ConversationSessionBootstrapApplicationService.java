package org.wwz.ai.application.agent.query;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.wwz.ai.application.agent.askuser.AskUserQuestionApplicationService;
import org.wwz.ai.application.agent.desktopcontrol.DesktopControlApplicationService;
import org.wwz.ai.application.agent.planmode.PlanApprovalApplicationService;
import org.wwz.ai.domain.agent.ledger.ExecutionLedgerQueryService;
import org.wwz.ai.domain.agent.ledger.model.ArtifactView;
import org.wwz.ai.domain.agent.ledger.model.ConversationHistoryPage;
import org.wwz.ai.domain.agent.ledger.replay.ConversationHistoryReplayService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 会话切换 bootstrap 聚合应用服务。
 * <p>
 * 把「恢复一个会话所需的全部读请求」收敛为一次调用：历史摘要、产物文件、三类 HITL 待办。
 * 鉴权由调用方（trigger）在进入本服务前完成，与 {@link ConversationHistoryReplayService} 分层一致。
 * 三类 HITL 是独立业务域，这里只做顺序调用与打包，不搬移各自的 listPending 逻辑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationSessionBootstrapApplicationService {

    private final ConversationHistoryReplayService conversationHistoryReplayService;
    private final ExecutionLedgerQueryService executionLedgerQueryService;
    private final AskUserQuestionApplicationService askUserQuestionApplicationService;
    private final DesktopControlApplicationService desktopControlApplicationService;
    private final PlanApprovalApplicationService planApprovalApplicationService;

    /**
     * 聚合查询会话 bootstrap 数据。
     *
     * @param sessionId 会话 ID（调用方已完成鉴权）
     * @param limit     历史 run 分页大小，null 用 replay 服务默认值
     * @param after     历史 run 游标
     */
    public ConversationSessionBootstrap queryBootstrap(String sessionId, Integer limit, String after) {
        ConversationHistoryPage page = conversationHistoryReplayService.queryConversationHistoryPage(
                sessionId, limit, after);
        List<ArtifactView> artifacts = executionLedgerQueryService.querySessionArtifacts(sessionId);
        return ConversationSessionBootstrap.builder()
                .detail(page)
                .files(artifacts == null ? List.of() : artifacts)
                .askUser(safeListPending("ask-user", sessionId, askUserQuestionApplicationService::listPending))
                .desktopControl(safeListPending("desktop-control", sessionId,
                        desktopControlApplicationService::listPending))
                .planApproval(safeListPending("plan-approval", sessionId,
                        planApprovalApplicationService::listPending))
                .build();
    }

    /**
     * 单个 HITL 域失败时返回空列表，不把故障传染给整个 bootstrap。
     */
    private List<Map<String, Object>> safeListPending(String domain,
                                                      String sessionId,
                                                      java.util.function.Function<String, List<Map<String, Object>>> loader) {
        try {
            List<Map<String, Object>> pending = loader.apply(sessionId);
            return pending == null ? List.of() : pending;
        } catch (Exception e) {
            log.warn("bootstrap 读取 {} pending 失败, sessionId={}", domain, sessionId, e);
            return List.of();
        }
    }

    /**
     * 应用层聚合模型。detail / files 是领域模型，三个 pending 是各自域已有的 Map 投影。
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ConversationSessionBootstrap {

        private ConversationHistoryPage detail;

        private List<ArtifactView> files;

        private List<Map<String, Object>> askUser;

        private List<Map<String, Object>> desktopControl;

        private List<Map<String, Object>> planApproval;
    }
}
