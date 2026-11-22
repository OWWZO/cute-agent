package org.wwz.ai.application.agent.query;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.wwz.ai.application.agent.askuser.AskUserQuestionApplicationService;
import org.wwz.ai.application.agent.desktopcontrol.DesktopControlApplicationService;
import org.wwz.ai.application.agent.planmode.PlanApprovalApplicationService;
import org.wwz.ai.domain.agent.ledger.ExecutionLedgerQueryService;
import org.wwz.ai.domain.agent.ledger.model.ArtifactView;
import org.wwz.ai.domain.agent.ledger.model.ConversationHistoryPage;
import org.wwz.ai.domain.agent.ledger.model.ConversationRunReplay;
import org.wwz.ai.domain.agent.ledger.model.ConversationRunSummary;
import org.wwz.ai.domain.agent.ledger.timeline.TimelineItemProjection;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.replay.ConversationHistoryReplayService;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionTaskListStore;
import org.wwz.ai.domain.agent.runtime.tasklist.TasklistPersistencePort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 会话切换 bootstrap 聚合应用服务。
 * <p>
 * 把「恢复一个会话所需的全部读请求」收敛为一次调用：历史摘要、产物文件、Todo 当前快照、
 * 三类 HITL 待办，以及末尾 turn 的 run 回放。鉴权由调用方（trigger）在进入本服务前完成，
 * 与 {@link ConversationHistoryReplayService} 分层一致。
 * 三类 HITL 是独立业务域，这里只做顺序调用与打包，不搬移各自的 listPending 逻辑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationSessionBootstrapApplicationService {

    /** 续跑 run 的 requestId 前缀，与 AskUserQuestion / DesktopControl / PlanApproval 的生成规则一致。 */
    private static final String RESUME_REQUEST_ID_PREFIX = "resume_";

    /**
     * 内联回放的字节预算。
     * <p>
     * 按线上实测：末尾 turn 的 replay 合计 p50≈20KB、p90≈228KB、p99≈1.1MB、max≈7.4MB。
     * 256KB 覆盖到 p90，约 90% 的切换能一次拿全；超出的整段降级为不内联，
     * 由前端走既有的并行补拉路径，避免把 20KB 的摘要壳放大成数 MB 响应。
     */
    private static final int INLINE_REPLAY_BYTE_BUDGET = 256 * 1024;

    private final ConversationHistoryReplayService conversationHistoryReplayService;
    private final ExecutionLedgerQueryService executionLedgerQueryService;
    private final AskUserQuestionApplicationService askUserQuestionApplicationService;
    private final DesktopControlApplicationService desktopControlApplicationService;
    private final PlanApprovalApplicationService planApprovalApplicationService;
    private final TasklistPersistencePort tasklistPersistencePort;

    /**
     * 聚合查询会话 bootstrap 数据。
     *
     * @param userId    当前登录用户；读取层会再次校验会话归属
     * @param sessionId 会话 ID（调用方已完成鉴权）
     * @param limit     历史 run 分页大小，null 用 replay 服务默认值
     * @param after     历史 run 游标
     */
    public ConversationSessionBootstrap queryBootstrap(String userId,
                                                       String sessionId,
                                                       Integer limit,
                                                       String after) {
        return queryBootstrap(userId, sessionId, limit, after, true, true);
    }

    /**
     * 可按用途裁剪的 bootstrap。
     * <p>
     * 会话切换首屏只需要历史摘要和 HITL；产物文件归右侧工作区，replay 归用户
     * 展开过程时按轮加载。把两者从首屏路径移除后，三个读取链路不再互相等待。
     */
    public ConversationSessionBootstrap queryBootstrap(String userId,
                                                       String sessionId,
                                                       Integer limit,
                                                       String after,
                                                       boolean includeFiles,
                                                       boolean includeReplays) {
        ConversationHistoryPage page = conversationHistoryReplayService.queryConversationHistoryPage(
                userId, sessionId, limit, after);
        List<ArtifactView> artifacts = includeFiles
                ? executionLedgerQueryService.querySessionArtifacts(userId, sessionId)
                : List.of();
        InlineReplays inlineReplays = includeReplays
                ? loadTailTurnReplays(userId, page)
                : InlineReplays.skipped();
        return ConversationSessionBootstrap.builder()
                .detail(page)
                .files(artifacts)
                .askUser(safeListPending("ask-user", sessionId, askUserQuestionApplicationService::listPending))
                .desktopControl(safeListPending("desktop-control", sessionId,
                        desktopControlApplicationService::listPending))
                .planApproval(safeListPending("plan-approval", sessionId,
                        planApprovalApplicationService::listPending))
                .sessionTasks(loadSessionTasks(sessionId))
                .replays(inlineReplays.replays)
                .replaysIncluded(inlineReplays.included)
                .build();
    }

    /**
     * Todo 是会话当前状态，不属于某一轮的 ledger 回放。
     * 刷新时直接从持久化列表恢复，避免只能依赖实时 session_tasks SSE。
     */
    private Map<String, Object> loadSessionTasks(String sessionId) {
        try {
            return new SessionTaskListStore(sessionId, tasklistPersistencePort).toClientSnapshot();
        } catch (Exception e) {
            log.warn("bootstrap 读取 session todo 失败, sessionId={}", sessionId, e);
            return Map.of();
        }
    }

    /**
     * 只加载**末尾 turn** 的 replay，与前端 {@code hydrateSessionWithRunningReplay} 的选取口径一致：
     * 有 RUNNING run 的 turn 优先，否则取最后一个 turn；turn 内的续跑 run 一起带上。
     * <p>
     * 为什么不带全部 run：实测 20 个 run 全内联平均 117MB，而末尾 turn 平均只有 97KB。
     * 旧 turn 的工具卡仍由前端点开时按 run 补拉。
     * <p>
     * 超预算整段降级而不截断：否则前端会同时面对「部分内联、部分补拉」的混合来源，
     * 合并逻辑要处理两种来源的交错，复杂度不值得。
     */
    private InlineReplays loadTailTurnReplays(String userId, ConversationHistoryPage page) {
        if (page == null || page.getRuns() == null || page.getRuns().isEmpty()) {
            return InlineReplays.skipped();
        }
        List<ConversationRunSummary> tailTurn = resolveTailTurn(page.getRuns());
        if (tailTurn.isEmpty()) {
            return InlineReplays.skipped();
        }

        Map<String, ConversationRunReplay> replays = new LinkedHashMap<>();
        long totalBytes = 0;
        for (ConversationRunSummary run : tailTurn) {
            // RUNNING run 的内容归 SSE 全量重放，账本回放拿不到进行中的流式正文，
            // 拉了还会与 SSE 重复挂载工具卡。
            if (isRunning(run) || !Boolean.TRUE.equals(run.getHasReplay())) {
                continue;
            }
            ConversationRunReplay replay = conversationHistoryReplayService.queryRunReplay(
                    userId, run.getRequestId());
            if (replay == null) {
                continue;
            }
            totalBytes += estimateReplayBytes(replay);
            if (totalBytes > INLINE_REPLAY_BYTE_BUDGET) {
                log.info("bootstrap 跳过内联 replay：超出预算 sessionId={} requestId={} bytes>{}",
                        page.getSessionId(), run.getRequestId(), INLINE_REPLAY_BYTE_BUDGET);
                return InlineReplays.skipped();
            }
            replays.put(run.getRequestId(), replay);
        }
        return replays.isEmpty() ? InlineReplays.skipped() : new InlineReplays(replays, true);
    }

    /**
     * 定位末尾 turn。runs 按时间正序返回，续跑 run 归入当前 turn。
     */
    private List<ConversationRunSummary> resolveTailTurn(List<ConversationRunSummary> runs) {
        List<List<ConversationRunSummary>> turns = new ArrayList<>();
        for (ConversationRunSummary run : runs) {
            if (!turns.isEmpty() && isContinuation(run)) {
                turns.get(turns.size() - 1).add(run);
                continue;
            }
            turns.add(new ArrayList<>(List.of(run)));
        }
        // 有 RUNNING run 的 turn 优先，与前端选取口径一致。
        for (int i = turns.size() - 1; i >= 0; i--) {
            List<ConversationRunSummary> turn = turns.get(i);
            for (ConversationRunSummary run : turn) {
                if (isRunning(run)) {
                    return turn;
                }
            }
        }
        return turns.isEmpty() ? List.of() : turns.get(turns.size() - 1);
    }

    /**
     * 续跑判据与前端 {@code isContinuationRun} 对齐：requestId 前缀为主。
     * 前端的「query 为空」兜底在摘要层不成立（queryPreview 是截断预览），故不复制。
     */
    private boolean isContinuation(ConversationRunSummary run) {
        return run != null && StringUtils.startsWith(run.getRequestId(), RESUME_REQUEST_ID_PREFIX);
    }

    private boolean isRunning(ConversationRunSummary run) {
        return run != null
                && run.getStatus() != null
                && run.getStatus() == ExecutionLedgerConstants.STATUS_RUNNING;
    }

    /**
     * 估算单 run replay 的序列化体积。
     * <p>
     * 只用于预算判定，不要求精确：正文（content / eventData）是体积主体，
     * 其余元数据按常量粗估。宁可略高估，避免超预算的响应被放行。
     */
    private long estimateReplayBytes(ConversationRunReplay replay) {
        long bytes = 0;
        var timelineItems = replay.getTimelineItems();
        if (TimelineItemProjection.hasRenderableEventData(timelineItems)) {
            for (var item : timelineItems) {
                if (item == null) {
                    continue;
                }
                bytes += lengthOf(item.getDelta());
                bytes += lengthOf(item.getErrorMsg());
                bytes += estimateMapBytes(item.getPayload());
                bytes += 256; // item 元数据粗估
            }
        } else if (replay.getReplayFrames() != null) {
            for (var frame : replay.getReplayFrames()) {
                if (frame == null) {
                    continue;
                }
                bytes += lengthOf(frame.getContent());
                bytes += lengthOf(frame.getContentDelta());
                bytes += lengthOf(frame.getQuery());
                bytes += lengthOf(frame.getErrorMessage());
                bytes += estimateMapBytes(frame.getEventData());
                bytes += 256; // frame 元数据粗估
            }
        }
        if (replay.getRun() != null) {
            bytes += lengthOf(replay.getRun().getQueryText());
            bytes += lengthOf(replay.getRun().getFinalSummaryText());
        }
        return bytes;
    }

    private long estimateMapBytes(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return 0;
        }
        long bytes = 0;
        for (Object value : map.values()) {
            bytes += estimateValueBytes(value);
        }
        return bytes;
    }

    private long estimateValueBytes(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof CharSequence text) {
            return lengthOf(text.toString());
        }
        if (value instanceof Map<?, ?> nested) {
            long bytes = 0;
            for (Object nestedValue : nested.values()) {
                bytes += estimateValueBytes(nestedValue);
            }
            return bytes;
        }
        if (value instanceof Iterable<?> items) {
            long bytes = 0;
            for (Object item : items) {
                bytes += estimateValueBytes(item);
            }
            return bytes;
        }
        return 16; // 标量粗估
    }

    /** 中文等多字节字符按 UTF-8 上界估，避免低估。 */
    private long lengthOf(String text) {
        return text == null ? 0 : text.length() * 2L;
    }

    /**
     * 单个 HITL 域失败时返回空列表，不把故障传染给整个 bootstrap。
     */
    private List<Map<String, Object>> safeListPending(String domain,
                                                      String sessionId,
                                                      Function<String, List<Map<String, Object>>> loader) {
        try {
            List<Map<String, Object>> pending = loader.apply(sessionId);
            return pending == null ? List.of() : pending;
        } catch (Exception e) {
            log.warn("bootstrap 读取 {} pending 失败, sessionId={}", domain, sessionId, e);
            return List.of();
        }
    }

    /** 内联回放结果：回放表 + 是否真的内联（前端据此决定是否还要补拉）。 */
    private record InlineReplays(Map<String, ConversationRunReplay> replays, boolean included) {
        static InlineReplays skipped() {
            return new InlineReplays(Map.of(), false);
        }
    }

    /**
     * 应用层聚合模型。detail / files / replays 是领域模型，三个 pending 是各自域已有的 Map 投影。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConversationSessionBootstrap {

        private ConversationHistoryPage detail;

        private List<ArtifactView> files;

        private List<Map<String, Object>> askUser;

        private List<Map<String, Object>> desktopControl;

        private List<Map<String, Object>> planApproval;

        /** 会话当前 Todo 快照；与实时 session_tasks 使用同一结构。 */
        private Map<String, Object> sessionTasks;

        /** 末尾 turn 的 run 回放，key = requestId。 */
        private Map<String, ConversationRunReplay> replays;

        /** false 表示未内联（超预算或无可用回放），前端需走并行补拉。 */
        private boolean replaysIncluded;
    }
}
