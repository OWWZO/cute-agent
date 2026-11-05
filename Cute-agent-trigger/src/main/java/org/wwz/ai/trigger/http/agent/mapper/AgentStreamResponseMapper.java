package org.wwz.ai.trigger.http.agent.mapper;

import org.springframework.stereotype.Component;
import org.wwz.ai.application.agent.stream.AgentSessionStreamFrame;
import org.wwz.ai.trigger.http.agent.vo.AgentStreamResponseVO;

/**
 * Case frame 到 HTTP/history response VO 的显式映射。
 */
@Component
public class AgentStreamResponseMapper {

    public AgentStreamResponseVO toResponse(AgentSessionStreamFrame frame) {
        if (frame == null) {
            return null;
        }
        return AgentStreamResponseVO.builder()
                .protocolVersion(frame.getProtocolVersion())
                .eventType(frame.getEventType())
                .eventId(frame.getEventId())
                .seq(frame.getSeq())
                .runId(frame.getRunId())
                .itemId(frame.getItemId())
                .parentItemId(frame.getParentItemId())
                .agentId(frame.getAgentId())
                .kind(frame.getKind())
                .phase(frame.getPhase())
                .itemStatus(frame.getItemStatus())
                .orderKey(frame.getOrderKey())
                .subOrder(frame.getSubOrder())
                .delta(frame.getDelta())
                .payload(frame.getPayload())
                .status(frame.getStatus())
                .response(frame.getResponse())
                .responseAll(frame.getResponseAll())
                .finished(frame.isFinished())
                .useTimes(frame.getUseTimes())
                .useTokens(frame.getUseTokens())
                .resultMap(frame.getResultMap())
                .responseType(frame.getResponseType())
                .traceId(frame.getTraceId())
                .reqId(frame.getReqId())
                .encrypted(frame.isEncrypted())
                .query(frame.getQuery())
                .messages(frame.getMessages())
                .packageType(frame.getPackageType())
                .errorMsg(frame.getErrorMsg())
                .eventSeq(frame.getEventSeq())
                .retryMs(frame.getRetryMs())
                .build();
    }
}
