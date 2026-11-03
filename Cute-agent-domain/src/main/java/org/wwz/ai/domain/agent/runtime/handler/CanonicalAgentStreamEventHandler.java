package org.wwz.ai.domain.agent.runtime.handler;


import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.wwz.ai.domain.agent.runtime.command.AgentExecutionCommand;
import org.wwz.ai.domain.agent.runtime.enums.ResponseTypeEnum;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamAccumulator;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamEvent;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamResult;
import org.wwz.ai.domain.agent.ledger.replay.ReplayProjector;

import java.util.HashMap;

/**
 * Agent 的增量响应处理器。
 * <p>
 * React 与 PlanSolve 共用同一份 canonical event 投影（实时事件与历史回放同源），
 * 因此只有一个实现；两者差异只体现在日志标签，由 {@link #handle} 内的 agentType 区分。
 */
@Component
@Slf4j
public class CanonicalAgentStreamEventHandler extends BaseAgentStreamEventHandler implements AgentStreamEventHandler {

    public CanonicalAgentStreamEventHandler(ReplayProjector replayProjector) {
        super(replayProjector);
    }

    /** 处理器失败时返回的终态标签，与 case 层 failed 帧保持一致。 */
    private static final String STATUS_FAILED = "failed";

    @Override
    public AgentStreamResult handle(AgentExecutionCommand request, AgentStreamEvent response, AgentStreamAccumulator eventResult) {
        try {
            // 不在处理器内重新拼装工具结果，避免实时链路与账本回放出现字段差异。
            return buildCanonicalIncrResult(request, eventResult, response);
        } catch (Exception e) {
            log.error("agentType={} requestId={} AgentStreamEventHandler handle error",
                    request == null ? null : request.getAgentType(),
                    request == null ? null : request.getRequestId(), e);
            // 不能返回 null：调用方会直接解引用结果，帧会被静默丢弃且 NPE 出现在远离根因的位置。
            // 注意 buildCanonicalIncrResult 可能已推进累积器（taskOrder/messageOrder），
            // 该帧之后的事件顺序可能不连续，但至少会把失败显式推给前端。
            return buildErrorResult(request, e);
        }
    }

    private AgentStreamResult buildErrorResult(AgentExecutionCommand request, Exception e) {
        AgentStreamResult result = new AgentStreamResult();
        result.setComplete(true);
        result.setStatus(STATUS_FAILED);
        result.setFrameType("result");
        result.setContentType(ResponseTypeEnum.TEXT.getValue());
        result.setRequestId(request == null ? null : request.getRequestId());
        result.setTraceId(request == null ? null : request.getRequestId());
        result.setErrorMessage(e == null || e.getMessage() == null
                ? "AgentStreamEventHandler handle failed"
                : e.getMessage());
        result.setContentDelta("");
        result.setContent("");
        result.setEventData(new HashMap<>());
        return result;
    }
}
