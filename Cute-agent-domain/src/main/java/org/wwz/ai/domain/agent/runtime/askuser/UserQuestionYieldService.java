package org.wwz.ai.domain.agent.runtime.askuser;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.wwz.ai.domain.agent.ledger.ExecutionLedgerRunSupport;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationFinishRecord;
import org.wwz.ai.domain.agent.memory.SessionWorkingMemoryService;
import org.wwz.ai.domain.agent.runtime.yield.PendingYieldToolCalls;
import org.wwz.ai.domain.agent.runtime.command.AgentExecutionCommand;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.agent.ReActAgent;
import org.wwz.ai.domain.agent.runtime.tool.common.planmode.AskUserQuestionTool;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * AskUserQuestion 让步收口：事务内写 question + working_memory + WAITING_INPUT，提交后再发卡片。
 * <p>注意：{@link ExecutionLedgerRunSupport#finishRun} 会先打内存终态（{@code markTurnClosed}）
 * 再写账本，而本方法处于 {@code @Transactional} 内；若后续发生回滚，内存上下文已关闭而库内 run
 * 仍是开放态。当前依赖「本方法内不再抛出异常」这一前提，新增可失败的写入时须先评估该不一致。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserQuestionYieldService {

    private static final long DEFAULT_TIMEOUT_MINUTES = 30L;

    private final IUserQuestionRepository userQuestionRepository;
    private final SessionWorkingMemoryService sessionWorkingMemoryService;
    private final org.wwz.ai.domain.agent.runtime.yieldcard.AfterCommitCardSender afterCommitCardSender;

    @Transactional(rollbackFor = Exception.class)
    public UserQuestionRecord yieldAndNotify(AgentContext agentContext,
                                             ReActAgent executor,
                                             AgentExecutionCommand request,
                                             UserInputRequiredException signal,
                                             String entryAgent) {
        if (agentContext == null || signal == null) {
            throw new IllegalStateException("yield requires agentContext and signal");
        }
        String questionId = "uq_" + UUID.randomUUID().toString().replace("-", "");
        Long runId = agentContext.getAgentRunState() == null ? null : agentContext.getAgentRunState().getRunId();
        // yield 时 memory 只有 assistant.tool_calls、尚无 tool result；先补 waiting 占位，避免网关 400。
        String toolCallId = AskUserQuestionObservationSupport.resolveAskUserToolCallId(
                executor == null || executor.getMemory() == null ? null : executor.getMemory().getMessages(),
                signal.getToolCallId());
        // yield 时 memory 只有 assistant.tool_calls、尚无 tool result；先补 waiting 占位，避免网关 400。
        PendingYieldToolCalls.appendWaitingToolResult(
                executor == null || executor.getMemory() == null ? null : executor.getMemory().getMessages(),
                toolCallId,
                AskUserQuestionObservationSupport.buildWaitingObservation(signal.getQuestions(), questionId));
        Long toolInvocationId = null;
        if (agentContext.getAgentRunState() != null && StringUtils.isNotBlank(toolCallId)) {
            toolInvocationId = agentContext.getAgentRunState().resolveToolInvocationId(toolCallId);
        }
        UserQuestionResumeContext resumeContext = UserQuestionResumeContext.from(agentContext, request, entryAgent);
        String userId = request == null ? null : request.getUserId();

        UserQuestionRecord record = UserQuestionRecord.builder()
                .questionId(questionId)
                .userId(userId)
                .sessionId(agentContext.getSessionId())
                .sourceRunId(runId)
                .sourceRequestId(agentContext.getRequestId())
                .toolInvocationId(toolInvocationId)
                .toolCallId(toolCallId)
                .questions(signal.getQuestions())
                .status(UserQuestionStatuses.PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(DEFAULT_TIMEOUT_MINUTES))
                .resumeContextJson(UserQuestionResumeContext.toJson(resumeContext))
                .build();

        userQuestionRepository.insert(record);
        if (sessionWorkingMemoryService != null && executor != null) {
            sessionWorkingMemoryService.persistTurn(
                    agentContext.getSessionId(),
                    agentContext.getRequestId(),
                    runId,
                    entryAgent,
                    executor.exportWorkingMemoryDelta()
            );
        }
        if (toolInvocationId != null && agentContext.getExecutionRecorder() != null) {
            agentContext.getExecutionRecorder().finishToolInvocation(ToolInvocationFinishRecord.builder()
                    .toolInvocationId(toolInvocationId)
                    .runId(runId)
                    .requestId(agentContext.getRequestId())
                    .sessionId(agentContext.getSessionId())
                    .toolCallId(toolCallId)
                    .toolName(AskUserQuestionTool.NAME)
                    .status(ExecutionLedgerConstants.STATUS_WAITING_INPUT)
                    .llmObservation(AskUserQuestionObservationSupport.buildWaitingObservation(
                            signal.getQuestions(), questionId))
                    .finishedAt(LocalDateTime.now())
                    .build());
        }
        ExecutionLedgerRunSupport.finishRun(
                agentContext,
                ExecutionLedgerConstants.STATUS_WAITING_INPUT,
                null,
                "WAITING_USER_INPUT",
                "等待用户回答 AskUserQuestion"
        );
        afterCommitCardSender.send(agentContext, record.getQuestionId(), "ask_user_question", () -> AskUserQuestionObservationSupport.toClientPayload(record));
        log.info("{} yielded AskUserQuestion questionId={} toolCallId={}",
                agentContext.getRequestId(), questionId, toolCallId);
        return record;
    }

}
