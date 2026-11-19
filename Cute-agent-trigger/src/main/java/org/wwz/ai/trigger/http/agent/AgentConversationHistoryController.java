package org.wwz.ai.trigger.http.agent;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.wwz.ai.api.response.Response;
import org.wwz.ai.application.agent.authorization.ConversationSessionAuthorizationService;
import org.wwz.ai.application.agent.query.ConversationSessionBootstrapApplicationService;
import org.wwz.ai.application.agent.query.ConversationSessionBootstrapApplicationService.ConversationSessionBootstrap;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.channel.model.ChannelChatType;
import org.wwz.ai.domain.agent.channel.policy.ChannelSessionKey;
import org.wwz.ai.domain.agent.channel.policy.ChannelUserId;
import org.wwz.ai.domain.agent.ledger.model.ArtifactView;
import org.wwz.ai.domain.agent.ledger.model.ConversationHistoryPage;
import org.wwz.ai.domain.agent.ledger.model.ConversationSessionCursor;
import org.wwz.ai.domain.agent.ledger.model.ConversationSessionPage;
import org.wwz.ai.domain.agent.ledger.model.ConversationRunReplay;
import org.wwz.ai.domain.agent.ledger.model.ConversationRunSummary;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunView;
import org.wwz.ai.domain.agent.ledger.model.DialogueSessionView;
import org.wwz.ai.domain.agent.ledger.ExecutionLedgerQueryService;
import org.wwz.ai.domain.agent.ledger.replay.ArtifactRelativePath;
import org.wwz.ai.domain.agent.ledger.replay.ConversationHistoryReplayService;
import org.wwz.ai.trigger.http.agent.mapper.ConversationRunReplayVoMapper;
import org.wwz.ai.trigger.http.agent.vo.ArtifactReferenceRespVO;
import org.wwz.ai.trigger.http.agent.vo.ConversationHistoryPageRespVO;
import org.wwz.ai.trigger.http.agent.vo.ConversationSessionBootstrapRespVO;
import org.wwz.ai.trigger.http.agent.vo.ConversationRunReplayRespVO;
import org.wwz.ai.trigger.http.agent.vo.ConversationRunSummaryRespVO;
import org.wwz.ai.trigger.http.agent.vo.ConversationSessionPageRespVO;
import org.wwz.ai.trigger.http.agent.vo.ConversationSessionRespVO;
import org.wwz.ai.trigger.http.agent.vo.ConversationSessionStatusRespVO;
import org.wwz.ai.types.agent.user.UserRequestContext;
import org.wwz.ai.types.enums.ResponseCode;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 会话历史恢复接口。
 */
@RestController
@RequestMapping("/api/agent/conversation")
public class AgentConversationHistoryController {

    @Resource
    private ExecutionLedgerQueryService executionLedgerQueryService;

    @Resource
    private ConversationHistoryReplayService conversationHistoryReplayService;

    @Resource
    private ConversationSessionAuthorizationService conversationSessionAuthorizationService;

    @Resource
    private ConversationSessionBootstrapApplicationService conversationSessionBootstrapApplicationService;

    /**
     * 会话切换 bootstrap：一次返回恢复会话所需的全部数据，替代 detail + files + 三类 HITL pending 五个串行请求。
     */
    @GetMapping("/sessions/{sessionId}/bootstrap")
    public Response<ConversationSessionBootstrapRespVO> bootstrap(
            @PathVariable("sessionId") String sessionId,
            @RequestParam(name = "limit", defaultValue = "20") Integer limit,
            @RequestParam(name = "after", required = false) String after,
            @RequestParam(name = "includeFiles", defaultValue = "true") boolean includeFiles,
            @RequestParam(name = "includeReplays", defaultValue = "true") boolean includeReplays) {
        try {
            // 鉴权统一在聚合入口完成：detail / files 原有接口各自校验，三个 pending 原先没有校验。
            conversationSessionAuthorizationService.ensureExistingSessionAccessible(
                    UserRequestContext.requireUserId(),
                    sessionId
            );
            ConversationSessionBootstrap bootstrap = conversationSessionBootstrapApplicationService
                    .queryBootstrap(
                            UserRequestContext.requireUserId(),
                            sessionId,
                            limit,
                            after,
                            includeFiles,
                            includeReplays
                    );
            return Response.<ConversationSessionBootstrapRespVO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(toBootstrapRespVO(bootstrap))
                    .build();
        } catch (Exception e) {
            return Response.<ConversationSessionBootstrapRespVO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(e.getMessage())
                    .build();
        }
    }

    @GetMapping("/sessions")
    public Response<ConversationSessionPageRespVO> list(
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "after", required = false) String after) {
        try {
            ConversationSessionCursor cursor = StringUtils.isBlank(after)
                    ? null
                    : ConversationSessionCursor.decode(after);
            ConversationSessionPage page = executionLedgerQueryService.queryRecentSessions(
                    UserRequestContext.requireUserId(),
                    cursor,
                    limit == null ? ExecutionLedgerQueryService.DEFAULT_RECENT_SESSION_PAGE_SIZE : limit
            );
            List<ConversationSessionRespVO> sessions = page.getSessions().stream()
                    .map(this::toSessionRespVO)
                    .collect(Collectors.toList());

            return Response.<ConversationSessionPageRespVO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(ConversationSessionPageRespVO.builder()
                            .sessions(sessions)
                            .nextCursor(page.getNextCursor())
                            .hasMore(page.isHasMore())
                            .build())
                    .build();
        } catch (Exception e) {
            return Response.<ConversationSessionPageRespVO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(e.getMessage())
                    .build();
        }
    }

    /**
     * 会话运行状态：Web 端只对当前打开的渠道会话轮询，用来发现 QQ 侧新起的轮次。
     */
    @GetMapping("/sessions/{sessionId}/status")
    public Response<ConversationSessionStatusRespVO> status(
            @PathVariable("sessionId") String sessionId) {
        try {
            conversationSessionAuthorizationService.ensureExistingSessionAccessible(
                    UserRequestContext.requireUserId(),
                    sessionId
            );
            DialogueSessionView session = executionLedgerQueryService.querySession(sessionId);
            if (session == null) {
                throw new IllegalArgumentException("当前会话不存在");
            }
            return Response.<ConversationSessionStatusRespVO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(ConversationSessionStatusRespVO.builder()
                            .sessionId(session.getSessionId())
                            .status(ConversationRunReplayVoMapper.toStatusLabel(session.getStatus()))
                            .latestRequestId(session.getLatestRequestId())
                            .bindingId(session.getBindingId())
                            .build())
                    .build();
        } catch (Exception e) {
            return Response.<ConversationSessionStatusRespVO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(e.getMessage())
                    .build();
        }
    }

    /**
     * 会话树：给树中任一节点，返回「根 + 全部后代」的扁平列表。
     * <p>层级由 {@code parentSessionId} 表达，前端据此画分支；根会话排在最前。</p>
     */
    @GetMapping("/sessions/{sessionId}/tree")
    public Response<List<ConversationSessionRespVO>> tree(
            @PathVariable("sessionId") String sessionId,
            @RequestParam(name = "limit", defaultValue = "200") Integer limit) {
        try {
            conversationSessionAuthorizationService.ensureExistingSessionAccessible(
                    UserRequestContext.requireUserId(),
                    sessionId
            );
            List<ConversationSessionRespVO> tree = executionLedgerQueryService
                    .querySessionTree(UserRequestContext.requireUserId(), sessionId, limit)
                    .stream()
                    .map(this::toSessionRespVO)
                    .collect(Collectors.toList());
            return Response.<List<ConversationSessionRespVO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(tree)
                    .build();
        } catch (Exception e) {
            return Response.<List<ConversationSessionRespVO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(e.getMessage())
                    .build();
        }
    }

    @GetMapping("/sessions/{sessionId}")
    public Response<ConversationHistoryPageRespVO> detail(            @PathVariable("sessionId") String sessionId,
            @RequestParam(name = "limit", defaultValue = "20") Integer limit,
            @RequestParam(name = "after", required = false) String after) {
        try {
            conversationSessionAuthorizationService.ensureExistingSessionAccessible(
                    UserRequestContext.requireUserId(),
                    sessionId
            );
            ConversationHistoryPage page = conversationHistoryReplayService.queryConversationHistoryPage(
                    sessionId,
                    limit,
                    after
            );
            return Response.<ConversationHistoryPageRespVO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(toPageRespVO(page))
                    .build();
        } catch (Exception e) {
            return Response.<ConversationHistoryPageRespVO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(e.getMessage())
                    .build();
        }
    }

    @GetMapping("/sessions/{sessionId}/files")
    public Response<List<ArtifactReferenceRespVO>> files(
            @PathVariable("sessionId") String sessionId) {
        try {
            conversationSessionAuthorizationService.ensureExistingSessionAccessible(
                    UserRequestContext.requireUserId(),
                    sessionId
            );
            List<ArtifactReferenceRespVO> files = executionLedgerQueryService
                    .querySessionArtifacts(UserRequestContext.requireUserId(), sessionId)
                    .stream()
                    .map(this::toArtifactReferenceRespVO)
                    .collect(Collectors.toList());
            return Response.<List<ArtifactReferenceRespVO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(files)
                    .build();
        } catch (Exception e) {
            return Response.<List<ArtifactReferenceRespVO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(e.getMessage())
                    .build();
        }
    }

    @GetMapping("/runs/{requestId}/replay")
    public Response<ConversationRunReplayRespVO> replay(
            @PathVariable("requestId") String requestId) {
        try {
            String userId = UserRequestContext.requireUserId();
            // 归属校验内聚到读契约：非 owner 直接视为 run 不存在，不泄漏 sessionId。
            DialogueRunView run = executionLedgerQueryService.queryRunSummary(userId, requestId);
            if (run == null || run.getSessionId() == null) {
                throw new IllegalArgumentException("requestId 对应的 run 不存在");
            }
            ConversationRunReplay replay = conversationHistoryReplayService.queryRunReplay(userId, requestId);
            if (replay == null) {
                throw new IllegalArgumentException("run replay 不可用");
            }
            return Response.<ConversationRunReplayRespVO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(ConversationRunReplayVoMapper.toRespVO(replay))
                    .build();
        } catch (Exception e) {
            return Response.<ConversationRunReplayRespVO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(e.getMessage())
                    .build();
        }
    }

    private ConversationSessionRespVO toSessionRespVO(DialogueSessionView session) {
        if (session == null) {
            return null;
        }
        Long bindingId = session.getBindingId();
        ChannelChatType chatType = bindingId == null
                ? null
                : ChannelSessionKey.chatTypeOf(session.getSessionId());
        return ConversationSessionRespVO.builder()
                .sessionId(session.getSessionId())
                .title(session.getTitle())
                .status(ConversationRunReplayVoMapper.toStatusLabel(session.getStatus()))
                .sessionKind(session.getSessionKind())
                .parentSessionId(session.getParentSessionId())
                .rootSessionId(session.getRootSessionId())
                .subAgentType(session.getSubAgentType())
                .subSessionCount(session.getSubSessionCount())
                .bindingId(bindingId)
                .channelChatType(chatType == null ? null : chatType.name())
                .visitorLabel(bindingId == null ? null : ChannelUserId.shortLabel(session.getUserId()))
                .latestQueryText(session.getLatestQueryText())
                .runCount(session.getRunCount())
                .finishedRunCount(session.getFinishedRunCount())
                .failedRunCount(session.getFailedRunCount())
                .startedAt(session.getStartedAt())
                .lastActiveAt(session.getLastActiveAt())
                .build();
    }

    private ConversationHistoryPageRespVO toPageRespVO(ConversationHistoryPage page) {
        if (page == null) {
            return null;
        }
        return ConversationHistoryPageRespVO.builder()
                .sessionId(page.getSessionId())
                .title(page.getTitle())
                .status(ConversationRunReplayVoMapper.toStatusLabel(page.getStatus()))
                .deepThink(page.getDeepThink())
                .model(page.getModel())
                .thinking(page.getThinking())
                .thinkingEffort(page.getThinkingEffort())
                .latestRequestId(page.getLatestRequestId())
                .latestQueryPreview(page.getLatestQueryPreview())
                .latestSummaryPreview(page.getLatestSummaryPreview())
                .runCount(page.getRunCount())
                .finishedRunCount(page.getFinishedRunCount())
                .failedRunCount(page.getFailedRunCount())
                .startedAt(page.getStartedAt())
                .lastActiveAt(page.getLastActiveAt())
                .runs(page.getRuns() == null ? List.of() : page.getRuns().stream()
                        .map(this::toRunSummaryRespVO)
                .collect(Collectors.toList()))
                .nextCursor(page.getNextCursor())
                .hasMore(page.isHasMore())
                .build();
    }

    private ConversationRunSummaryRespVO toRunSummaryRespVO(ConversationRunSummary run) {
        if (run == null) {
            return null;
        }
        return ConversationRunSummaryRespVO.builder()
                .requestId(run.getRequestId())
                .entryAgent(run.getEntryAgent())
                .status(ConversationRunReplayVoMapper.toStatusLabel(run.getStatus()))
                .queryPreview(run.getQueryPreview())
                .finalSummaryPreview(run.getFinalSummaryPreview())
                .finalSummaryText(run.getFinalSummaryText())
                .contextUsage(run.getContextUsage())
                .llmCallCount(run.getLlmCallCount())
                .toolCallCount(run.getToolCallCount())
                .artifactCount(run.getArtifactCount())
                .startedAt(run.getStartedAt())
                .finishedAt(run.getFinishedAt())
                .durationMs(run.getDurationMs())
                .hasReplay(run.getHasReplay())
                .build();
    }

    private ConversationSessionBootstrapRespVO toBootstrapRespVO(ConversationSessionBootstrap bootstrap) {
        if (bootstrap == null) {
            return null;
        }
        return ConversationSessionBootstrapRespVO.builder()
                .detail(toPageRespVO(bootstrap.getDetail()))
                .files(bootstrap.getFiles() == null ? List.of() : bootstrap.getFiles().stream()
                        .map(this::toArtifactReferenceRespVO)
                        .collect(Collectors.toList()))
                .askUser(emptyIfNull(bootstrap.getAskUser()))
                .desktopControl(emptyIfNull(bootstrap.getDesktopControl()))
                .planApproval(emptyIfNull(bootstrap.getPlanApproval()))
                .replays(toReplayRespVOMap(bootstrap.getReplays()))
                .replaysIncluded(bootstrap.isReplaysIncluded())
                .build();
    }

    private Map<String, ConversationRunReplayRespVO> toReplayRespVOMap(
            Map<String, ConversationRunReplay> replays) {
        if (replays == null || replays.isEmpty()) {
            return Map.of();
        }
        Map<String, ConversationRunReplayRespVO> result = new LinkedHashMap<>();
        replays.forEach((requestId, replay) ->
                result.put(requestId, ConversationRunReplayVoMapper.toRespVO(replay)));
        return result;
    }

    private <T> List<T> emptyIfNull(List<T> list) {
        return list == null ? List.of() : list;
    }

    private ArtifactReferenceRespVO toArtifactReferenceRespVO(ArtifactView artifact) {
        String downloadUrl = artifact == null ? null : artifact.getDownloadUrl();
        String previewUrl = artifact == null ? null : artifact.getPreviewUrl();
        boolean missing = StringUtils.isBlank(downloadUrl) && StringUtils.isBlank(previewUrl);
        return ArtifactReferenceRespVO.builder()
                .requestId(artifact == null ? null : artifact.getRequestId())
                .artifactRole(artifact == null ? null : artifact.getArtifactRole())
                .artifactType(artifact == null ? null : artifact.getMimeType())
                .displayName(artifact == null ? null : artifact.getFileName())
                .relativePath(ArtifactRelativePath.resolve(artifact))
                .resourceKey(artifact == null
                        ? null
                        : StringUtils.defaultIfBlank(artifact.getStorageKey(), artifact.getFileName()))
                .downloadUrl(downloadUrl)
                .previewUrl(previewUrl)
                .fileSize(artifact == null ? null : artifact.getFileSize())
                .mimeType(artifact == null ? null : artifact.getMimeType())
                .missing(missing)
                .missingReason(missing ? "artifact_not_found" : null)
                .build();
    }

}
