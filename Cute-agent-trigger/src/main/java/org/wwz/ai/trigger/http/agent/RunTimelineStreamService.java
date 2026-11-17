package org.wwz.ai.trigger.http.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.wwz.ai.application.agent.query.RunTimelineQueryService;
import org.wwz.ai.application.agent.query.RunTimelineSnapshot;
import org.wwz.ai.application.agent.run.AgentRunFollowApplicationService;
import org.wwz.ai.application.agent.run.FollowAttachResult;
import org.wwz.ai.application.agent.stream.AgentSessionEventBus;
import org.wwz.ai.application.agent.stream.AgentSessionStreamFrame;
import org.wwz.ai.domain.agent.ledger.timeline.TimelineItemProjection;
import org.wwz.ai.trigger.http.cute.support.SseEmitterAgentSessionStream;
import org.wwz.ai.trigger.http.cute.support.SseLifecycleSupport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * run 级 canonical 时间线流：committed snapshot + live item upsert。
 *
 * <p>先挂 live 缓冲，再发送 committed snapshot，最后 flush 缓冲，避免快照查询期间
 * 新产生的运行帧丢失。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RunTimelineStreamService {

    private final RunTimelineQueryService runTimelineQueryService;
    private final AgentRunFollowApplicationService agentRunFollowApplicationService;
    private final AgentSessionEventBus agentSessionEventBus;

    public SseEmitter open(String userId, String requestId, RunTimelineSnapshot initial, long afterSeq) {
        SseEmitter emitter = SseLifecycleSupport.createLongLivedEmitter();
        SseEmitterAgentSessionStream stream = new SseEmitterAgentSessionStream(emitter);
        LiveFrameBuffer buffer = new LiveFrameBuffer(
                stream,
                runTimelineQueryService,
                userId,
                requestId,
                afterSeq);
        stream.onAbort(buffer::close);
        try {
            FollowAttachResult attached = FollowAttachResult.IDLE;
            if (!initial.terminal()) {
                buffer.bind(agentSessionEventBus.subscribe(
                        initial.sessionId(),
                        buffer::accept,
                        buffer::requestCompletion));
                attached = agentRunFollowApplicationService.attachSession(
                        initial.sessionId(), afterSeq, null);
            }

            RunTimelineSnapshot snapshot =
                    runTimelineQueryService.query(userId, requestId, afterSeq);
            if (snapshot == null) {
                buffer.close();
                stream.complete();
                return emitter;
            }
            for (var item : snapshot.items()) {
                stream.send(item);
            }
            buffer.release();
            if (buffer.isClosed()) {
                return emitter;
            }

            if (snapshot.terminal()) {
                buffer.close();
                stream.send(buildTerminal(snapshot));
                stream.complete();
            } else if (attached == FollowAttachResult.PENDING) {
                stream.send(buildFollowControl(requestId, attached, snapshot.status()));
            } else if (attached != FollowAttachResult.ATTACHED) {
                buffer.close();
                stream.send(buildFollowControl(requestId, attached, snapshot.status()));
                stream.complete();
            }
            return emitter;
        } catch (Exception e) {
            buffer.close();
            log.warn("run timeline stream failed requestId={}", requestId, e);
            stream.completeWithError(e);
            return emitter;
        }
    }

    private AgentSessionStreamFrame buildFollowControl(String requestId,
                                                       FollowAttachResult attached,
                                                       String runStatus) {
        boolean pending = attached == FollowAttachResult.PENDING;
        return AgentSessionStreamFrame.builder()
                .protocolVersion(TimelineItemProjection.PROTOCOL_VERSION)
                .eventType(pending ? "stream.follow_pending" : "stream.follow_idle")
                .runId(requestId)
                .status(StringUtils.lowerCase(runStatus))
                .retryMs(pending ? 800L : null)
                .build();
    }

    private static AgentSessionStreamFrame buildTerminal(RunTimelineSnapshot snapshot) {
        String eventType = switch (snapshot.status()) {
            case "FAILED" -> "run.failed";
            case "STOPPED" -> "run.stopped";
            case "TIMEOUT" -> "run.failed";
            default -> "run.completed";
        };
        String status = switch (snapshot.status()) {
            case "FAILED", "TIMEOUT" -> "failed";
            case "STOPPED" -> "stopped";
            default -> "success";
        };
        return AgentSessionStreamFrame.builder()
                .protocolVersion(TimelineItemProjection.PROTOCOL_VERSION)
                .eventType(eventType)
                .eventId(TimelineItemProjection.eventId(
                        snapshot.sessionId(), snapshot.watermark(), null))
                .seq(snapshot.watermark())
                .runId(snapshot.requestId())
                .status(status)
                .finished(true)
                .resultMap(Map.of())
                .build();
    }

    private static final class LiveFrameBuffer {
        private final SseEmitterAgentSessionStream stream;
        private final RunTimelineQueryService queryService;
        private final String userId;
        private final String requestId;
        private final long afterSeq;
        private final List<AgentSessionStreamFrame> pending = new ArrayList<>();
        private boolean released;
        private boolean closed;
        private boolean completionRequested;
        private AutoCloseable subscription;

        private LiveFrameBuffer(SseEmitterAgentSessionStream stream,
                                RunTimelineQueryService queryService,
                                String userId,
                                String requestId,
                                long afterSeq) {
            this.stream = stream;
            this.queryService = queryService;
            this.userId = userId;
            this.requestId = requestId;
            this.afterSeq = afterSeq;
        }

        private synchronized void bind(AutoCloseable subscription) {
            this.subscription = subscription;
            if (closed) {
                closeSubscription();
            }
        }

        private synchronized void accept(AgentSessionStreamFrame frame) {
            if (closed || !matches(frame)) {
                return;
            }
            if (isTerminalRunEvent(frame.getEventType())) {
                // live terminal 只表示投影已产出终态，不能拿它直接关流。
                // committed run-final 必须等 Ledger 查回后再发送。
                completionRequested = true;
                if (released) {
                    finishFromLedger();
                }
                return;
            }
            if (StringUtils.startsWith(frame.getEventType(), "run.")) {
                return;
            }
            try {
                AgentSessionStreamFrame live = canonicalLiveFrame(frame);
                if (!released) {
                    pending.add(live);
                    return;
                }
                send(live);
            } catch (IOException e) {
                log.debug("run timeline live send failed requestId={}", requestId, e);
                close();
            }
        }

        private synchronized void release() throws IOException {
            released = true;
            for (AgentSessionStreamFrame frame : pending) {
                send(frame);
            }
            pending.clear();
            if (completionRequested) {
                finishFromLedger();
            }
        }

        private synchronized void requestCompletion() {
            completionRequested = true;
            if (!released) {
                return;
            }
            finishFromLedger();
        }

        private boolean matches(AgentSessionStreamFrame frame) {
            if (frame == null || frame.getEventSeq() <= afterSeq) {
                return false;
            }
            String eventType = StringUtils.trimToEmpty(frame.getEventType());
            String frameRunId = StringUtils.defaultIfBlank(frame.getRunId(), frame.getReqId());
            if (eventType.startsWith("run.")) {
                return requestId.equals(frameRunId);
            }
            if (eventType.startsWith("stream.")) {
                return true;
            }
            return StringUtils.isBlank(frameRunId) || requestId.equals(frameRunId);
        }

        private void send(AgentSessionStreamFrame frame) throws IOException {
            if (closed) {
                return;
            }
            try {
                stream.send(frame);
            } catch (IOException e) {
                close();
                throw e;
            }
        }

        private synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            pending.clear();
            closeSubscription();
        }

        private synchronized boolean isClosed() {
            return closed;
        }

        private static boolean isTerminalRunEvent(String eventType) {
            return "run.completed".equals(eventType)
                    || "run.failed".equals(eventType)
                    || "run.stopped".equals(eventType);
        }

        private void finishFromLedger() {
            if (closed) {
                return;
            }
            try {
                RunTimelineSnapshot snapshot =
                        queryService.query(userId, requestId, afterSeq);
                if (snapshot == null || !snapshot.terminal()) {
                    return;
                }
                for (var item : snapshot.items()) {
                    stream.send(item);
                }
                send(buildTerminal(snapshot));
                close();
                stream.complete();
            } catch (IOException e) {
                log.debug("run timeline terminal send failed requestId={}", requestId, e);
                close();
                stream.complete();
            }
        }

        private void closeSubscription() {
            AutoCloseable current = subscription;
            subscription = null;
            if (current == null) {
                return;
            }
            try {
                current.close();
            } catch (Exception e) {
                log.debug("run timeline unsubscribe failed requestId={}", requestId, e);
            }
        }

        private AgentSessionStreamFrame canonicalLiveFrame(AgentSessionStreamFrame source) {
            return AgentSessionStreamFrame.builder()
                    .protocolVersion(source.getProtocolVersion())
                    .eventType(source.getEventType())
                    .eventId(source.getEventId())
                    .seq(source.getSeq())
                    .runId(source.getRunId())
                    .itemId(source.getItemId())
                    .parentItemId(source.getParentItemId())
                    .agentId(source.getAgentId())
                    .kind(source.getKind())
                    .phase(source.getPhase())
                    .itemStatus(source.getItemStatus())
                    .orderKey(source.getOrderKey())
                    .subOrder(source.getSubOrder())
                    .delta(source.getDelta())
                    .payload(source.getPayload())
                    .transientItem(true)
                    .status(source.getStatus())
                    .response(source.getResponse())
                    .responseAll(source.getResponseAll())
                    .finished(source.isFinished())
                    .useTimes(source.getUseTimes())
                    .useTokens(source.getUseTokens())
                    .resultMap(source.getResultMap())
                    .responseType(source.getResponseType())
                    .traceId(source.getTraceId())
                    .reqId(source.getReqId())
                    .encrypted(source.isEncrypted())
                    .query(source.getQuery())
                    .messages(source.getMessages())
                    .packageType(source.getPackageType())
                    .errorMsg(source.getErrorMsg())
                    .eventSeq(source.getEventSeq())
                    .retryMs(source.getRetryMs())
                    .build();
        }
    }
}
