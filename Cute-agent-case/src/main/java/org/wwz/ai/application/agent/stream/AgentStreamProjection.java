package org.wwz.ai.application.agent.stream;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.ledger.timeline.TimelineItemProjection;
import org.wwz.ai.domain.agent.runtime.command.AgentExecutionCommand;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamAccumulator;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamEvent;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamResult;
import org.wwz.ai.domain.agent.runtime.enums.AgentType;
import org.wwz.ai.domain.agent.runtime.enums.ResponseTypeEnum;
import org.wwz.ai.domain.agent.runtime.handler.AgentStreamEventHandler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 将执行内核的 {@link AgentStreamEvent} 投影为 Case {@link AgentSessionStreamFrame}。
 * 应用层直接调度时使用，替代旧的 HTTP loopback 再解析路径。
 * <p>投影只赋序、缓冲并发布到 {@link AgentSessionEventBus}。HTTP 观察连接由 Hub 订阅。
 * HITL resume 等仍可挂本地 downstream。断流窗口内的帧按 {@code lastEventSeq} 回放。</p>
 */
@Slf4j
public class AgentStreamProjection implements AgentSessionStream {

    /**
     * 断流窗口内保留的投影帧。
     * <p>
     * <b>没有条数上限</b>：缓冲按「落库边界」清理（见 {@link #prunePersistedDeltas}），
     * 长度天然有界于「当前尚未落库的那一次调用」。用帧数裁剪会在长思考 / 长工具流时丢掉
     * 前半段，而这段内容在落库前没有第二份副本。
     */
    private final Deque<AgentSessionStreamFrame> replayBuffer = new ArrayDeque<>();
    private final Object bufferLock = new Object();
    private final AgentStreamFrameMapper frameMapper = new AgentStreamFrameMapper();

    /**
     * 同组末帧到达即代表整组已落库、可清中间 delta 的流式消息类型。
     * 键是 messageId：同一轮 LLM 的所有 delta 共享一个 messageId。
     */
    private static final Set<String> MESSAGE_GROUPED_STREAM_TYPES = Set.of(
            "agent_stream",
            "llm_reasoning",
            "tool_thought"
    );

    /**
     * 同组末帧到达即可清中间帧的流式工具类型。键是 resultMap.toolCallId。
     * 这类工具在一次调用内持续产帧（如 deep_search 的 search / extend 分片），
     * 结束后由账本的 tool_invocation 一次性表达完整结果。
     */
    private static final Set<String> TOOL_GROUPED_STREAM_TYPES = Set.of(
            "deep_search",
            "browser",
            "code",
            "data_analysis",
            "knowledge"
    );

    private final List<ObserverSink> observers = new CopyOnWriteArrayList<>();
    private final AgentSessionEventBus eventBus;
    private final SessionEventClock eventClock;
    private SessionProjectionRegistry projectionRegistry;
    private final AgentExecutionCommand request;
    private final Map<AgentType, AgentStreamEventHandler> handlerMap;
    private final AgentTimelineEventProjector timelineEventProjector;
    private final Set<String> timelineWrittenItems = ConcurrentHashMap.newKeySet();
    private final AgentStreamAccumulator eventResult = new AgentStreamAccumulator();
    private final List<Runnable> abortHandlers = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong eventSequence = new AtomicLong();
    private final long startTime = System.currentTimeMillis();
    /**
     * 主/子 Agent 并行工具会同时 printer.send。Accumulator 不是线程安全的，
     * 投影必须单写，否则 taskId、orderMapping、resultMap 会错配到前端。
     */
    private final Object projectionLock = new Object();

    public AgentStreamProjection(AgentSessionStream downstream,
                                          AgentExecutionCommand request,
                                         Map<AgentType, AgentStreamEventHandler> handlerMap) {
        this(downstream, request, handlerMap, null);
    }

    public AgentStreamProjection(AgentSessionStream downstream,
                                          AgentExecutionCommand request,
                                         Map<AgentType, AgentStreamEventHandler> handlerMap,
                                         AgentSessionEventBus eventBus) {
        this(downstream, request, handlerMap, eventBus, null);
    }

    public AgentStreamProjection(AgentSessionStream downstream,
                                          AgentExecutionCommand request,
                                         Map<AgentType, AgentStreamEventHandler> handlerMap,
                                         AgentSessionEventBus eventBus,
                                         SessionEventClock eventClock) {
        this(downstream, request, handlerMap, eventBus, eventClock, null);
    }

    public AgentStreamProjection(AgentSessionStream downstream,
                                 AgentExecutionCommand request,
                                 Map<AgentType, AgentStreamEventHandler> handlerMap,
                                 AgentSessionEventBus eventBus,
                                 SessionEventClock eventClock,
                                 AgentTimelineEventProjector timelineEventProjector) {
        this.request = request;
        this.handlerMap = handlerMap == null ? Map.of() : handlerMap;
        this.eventBus = eventBus;
        this.eventClock = eventClock;
        this.timelineEventProjector = timelineEventProjector;
        if (downstream != null) {
            addDownstream(downstream, 0L);
        }
    }

    public AgentStreamProjection bindRegistry(SessionProjectionRegistry registry) {
        this.projectionRegistry = registry;
        if (registry != null && request != null && StringUtils.isNotBlank(request.getSessionId())) {
            registry.register(request.getSessionId(), this);
        }
        return this;
    }

    public boolean isClosed() {
        return closed.get();
    }

    /**
     * 刷新/重连后续绑浏览器观察流。已 finish 关闭的投影不再接受续绑。
     * 续绑后补发断流窗口内缓冲帧。旧观察者若仍活着会继续收帧，不会被替换。
     */
    public void rebindDownstream(AgentSessionStream next) {
        rebindDownstream(next, 0L);
    }

    public void rebindDownstream(AgentSessionStream next, long lastEventSeq) {
        addDownstream(next, lastEventSeq);
    }

    public void addDownstream(AgentSessionStream next, long lastEventSeq) {
        if (next == null) {
            return;
        }
        synchronized (projectionLock) {
            pruneAbortedObservers();
            ObserverSink sink = new ObserverSink(next);
            observers.add(sink);
            wireObserverAbort(sink);
            log.info("{} attach projection observer lastEventSeq={} observers={}",
                    request == null ? "-" : request.getRequestId(), lastEventSeq, observers.size());
            boolean wasClosed = closed.get();
            replayBufferedFrames(next, lastEventSeq, wasClosed);
            if (wasClosed && !next.isAborted()) {
                next.complete();
            }
        }
    }

    @Override
    public void send(Object payload) throws IOException {
        if (payload instanceof AgentStreamEvent event) {
            sendRuntimeEvent(event);
            return;
        }
        if (payload instanceof AgentSessionStreamFrame frame) {
            forwardIfLive(frame);
            return;
        }
    }

    @Override
    public void sendRuntimeEvent(AgentStreamEvent agentResponse) throws IOException {
        if (closed.get()) {
            return;
        }

        synchronized (projectionLock) {
            if (closed.get()) {
                return;
            }
            AgentType agentType = AgentType.fromCode(request.getAgentType());
            AgentStreamEventHandler handler = handlerMap.get(agentType);
            if (handler == null) {
                log.error("{} no AgentStreamEventHandler found for agentType: {}",
                        request.getRequestId(), agentType);
                AgentSessionStreamFrame failed = buildDefaultResult(
                        request, "Agent 执行失败，请稍后重试");
                assignSeq(failed);
                enrichRunFailed(failed);
                offerReplayBuffer(failed);
                forwardIfLive(failed);
                return;
            }

            // 断流期间仍推进投影状态，避免 rebind 后状态机落后。
            AgentStreamResult result = handler.handle(request, agentResponse, eventResult);
            AgentSessionStreamFrame frame = frameMapper.toFrame(result);
            assignSeq(frame);
            if (timelineEventProjector != null) {
                try {
                    timelineEventProjector.project(frame, request, timelineWrittenItems);
                } catch (RuntimeException e) {
                    // 时间线是展示增强，不能让投影异常吞掉本帧或打断 Agent 主循环。
                    log.warn("{} timeline projection failed, seq={}",
                            request.getRequestId(), frame == null ? null : frame.getEventSeq(), e);
                }
            }
            offerReplayBuffer(frame);
            forwardIfLive(frame);
            // 根 result 的 finished 只表示业务终态（前端收口 loading），不在此关传输层。
            // 关流留给：1) GptQuery / HITL resume 在 finishRun、markStatus(ANSWERED) 之后的显式 complete；
            // 2) 后台空闲时的 stream_settle。避免 SSE 在 ledger/approval 落库前被掐断。
            if (frame != null && frame.isFinished() && isStreamSettle(agentResponse)) {
                log.info("{} task total cost time:{}ms",
                        request.getRequestId(), System.currentTimeMillis() - startTime);
                complete();
            }
        }
    }

    private static boolean isStreamSettle(AgentStreamEvent agentResponse) {
        return agentResponse != null && "stream_settle".equals(agentResponse.getMessageType());
    }

    @Override
    public void complete() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        persistWatermark(lastBufferedSeq());
        detachRegistry();
        for (ObserverSink sink : observers) {
            if (sink.stream != null && !sink.stream.isAborted()) {
                sink.stream.complete();
            }
        }
    }

    @Override
    public void completeWithError(Throwable throwable) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (throwable != null) {
            log.error("{} agent stream failed", request == null ? "-" : request.getRequestId(), throwable);
        }
        AgentSessionStreamFrame failed = buildDefaultResult(
                request, "Agent 执行失败，请稍后重试");
        assignSeq(failed);
        enrichRunFailed(failed);
        offerReplayBuffer(failed);
        if (eventBus != null && request != null && StringUtils.isNotBlank(request.getSessionId())) {
            eventBus.publish(request.getSessionId(), failed);
        }
        detachRegistry();
        for (ObserverSink sink : observers) {
            if (sink.stream != null && !sink.stream.isAborted()) {
                sink.stream.completeWithError(throwable);
            }
        }
    }

    @Override
    public void onAbort(Runnable abortHandler) {
        if (abortHandler == null) {
            return;
        }
        abortHandlers.add(abortHandler);
        if (isAborted() && !closed.get()) {
            abortHandler.run();
        }
    }

    @Override
    public boolean isAborted() {
        if (closed.get()) {
            return true;
        }
        // 主路径只发布到 session bus，没有本地 sink 也不算 aborted。
        if (eventBus != null) {
            return false;
        }
        return liveObservers().isEmpty();
    }

    public List<AgentSessionStreamFrame> replayAfter(long lastEventSeq) {
        List<AgentSessionStreamFrame> snapshot;
        synchronized (bufferLock) {
            snapshot = new ArrayList<>(replayBuffer);
        }
        List<AgentSessionStreamFrame> frames = new ArrayList<>();
        for (AgentSessionStreamFrame frame : snapshot) {
            if (frame.getEventSeq() <= lastEventSeq) {
                continue;
            }
            frames.add(frame);
        }
        return frames;
    }

    public static AgentSessionStreamFrame buildHeartbeat(String requestId) {
        return AgentSessionStreamFrame.builder()
                .finished(false)
                .status("success")
                .responseType(ResponseTypeEnum.TEXT.getValue())
                .response("")
                .responseAll("")
                .useTimes(0)
                .useTokens(0)
                .reqId(requestId)
                .packageType("heartbeat")
                .protocolVersion(TimelineItemProjection.PROTOCOL_VERSION)
                .eventType("stream.heartbeat")
                .encrypted(false)
                .build();
    }

    /**
     * 续流时若 run 已不在进程内且 ledger 终态，向前端推终态空包。
     */
    public static AgentSessionStreamFrame buildFollowIdle(String sessionId) {
        return AgentSessionStreamFrame.builder()
                .finished(true)
                .status("success")
                .responseType(ResponseTypeEnum.TEXT.getValue())
                .response("")
                .responseAll("")
                .useTimes(0)
                .useTokens(0)
                .reqId(sessionId)
                .packageType("follow_idle")
                .protocolVersion(TimelineItemProjection.PROTOCOL_VERSION)
                .eventType("stream.follow_idle")
                .eventId(TimelineItemProjection.eventId(sessionId, 0, "follow_idle"))
                // 观察流空闲只代表当前 session 没有可挂载 run，不能伪造 runId。
                .runId(null)
                .encrypted(false)
                .build();
    }

    /**
     * registry 暂无但 ledger 仍 RUNNING：提示前端继续退避重连，不要当任务结束。
     */
    public static AgentSessionStreamFrame buildFollowPending(String sessionId) {
        return buildFollowPending(sessionId, null);
    }

    public static AgentSessionStreamFrame buildFollowPending(String sessionId, Long retryMs) {
        return AgentSessionStreamFrame.builder()
                .finished(false)
                .status("success")
                .responseType(ResponseTypeEnum.TEXT.getValue())
                .response("")
                .responseAll("")
                .useTimes(0)
                .useTokens(0)
                .reqId(sessionId)
                .packageType("follow_pending")
                .protocolVersion(TimelineItemProjection.PROTOCOL_VERSION)
                .eventType("stream.follow_pending")
                .eventId(TimelineItemProjection.eventId(sessionId, 0, "follow_pending"))
                .runId(null)
                .encrypted(false)
                .retryMs(retryMs)
                .build();
    }

    private void forwardIfLive(AgentSessionStreamFrame payload) {
        if (eventBus != null && request != null && StringUtils.isNotBlank(request.getSessionId())) {
            eventBus.publish(request.getSessionId(), payload);
        }
        for (ObserverSink sink : liveObservers()) {
            try {
                sink.stream.send(payload);
            } catch (Exception e) {
                log.warn("{} forward observer failed",
                        request == null ? "-" : request.getRequestId(), e);
            }
        }
    }

    private void assignSeq(AgentSessionStreamFrame result) {
        if (result == null) {
            return;
        }
        long seq = nextSeq();
        result.setEventSeq(seq);
        result.setSeq(seq);
        if (isDurable(result)) {
            persistWatermark(result.getEventSeq());
        }
    }

    private long nextSeq() {
        if (eventClock != null && request != null && StringUtils.isNotBlank(request.getSessionId())) {
            return eventClock.next(request.getSessionId());
        }
        return eventSequence.incrementAndGet();
    }

    private void persistWatermark(long eventSeq) {
        if (eventClock == null || request == null || StringUtils.isBlank(request.getSessionId()) || eventSeq <= 0) {
            return;
        }
        eventClock.persist(request.getSessionId(), eventSeq);
    }

    private long lastBufferedSeq() {
        synchronized (bufferLock) {
            AgentSessionStreamFrame last = replayBuffer.peekLast();
            return last == null ? 0L : last.getEventSeq();
        }
    }

    private static boolean isDurable(AgentSessionStreamFrame result) {
        if (result.isFinished()) {
            return true;
        }
        String packageType = result.getPackageType();
        return "result".equals(packageType)
                || "tool".equals(packageType)
                || "stream_settle".equals(packageType)
                || "follow_idle".equals(packageType);
    }

    private void offerReplayBuffer(AgentSessionStreamFrame result) {
        if (result == null || "heartbeat".equals(result.getPackageType())) {
            return;
        }
        synchronized (bufferLock) {
            replayBuffer.addLast(result);
            prunePersistedDeltas(result);
        }
    }

    /**
     * 按「落库边界」清理回放缓冲。
     * <p>
     * 缓冲的唯一职责是覆盖账本的盲区：某轮 LLM 调用 / 某个流式工具还在产出时，账本里只有
     * RUNNING 行、没有正文，这些 delta 帧是**唯一副本**，必须完整保留。一旦收到该组
     * {@code isFinal=true} 的末帧，说明后端已把完整内容落库（{@code finishLlmInvocation} /
     * {@code finishToolInvocation}），中间 delta 即可移除；末帧本身保留，因为它是该组的
     * 全文快照，重订时前端要靠它一次性补齐。
     * <p>
     * <b>刻意不设全局帧数 / 时长上限</b>：任何按帧数或时间窗口裁剪，都会在长思考、长工具流时
     * 丢掉前半段内容，而这段内容在落库前没有第二份副本。因此缓冲长度会随本投影生命周期内
     * 保留的末帧和其它骨架帧增长，并不等同于「一次调用期间的帧数」上限。
     */
    private void prunePersistedDeltas(AgentSessionStreamFrame finalFrame) {
        Map<String, Object> eventData = readEventData(finalFrame);
        if (eventData == null || !Boolean.TRUE.equals(eventData.get("isFinal"))) {
            return;
        }
        String groupKey = resolvePruneGroupKey(eventData);
        if (groupKey == null) {
            return;
        }
        // 末帧自身保留：它是该组的全文快照，重订时前端要靠它补齐整段内容。
        replayBuffer.removeIf(frame -> frame != finalFrame
                && groupKey.equals(resolvePruneGroupKey(readEventData(frame))));
    }

    /**
     * 该帧所属的「落库分组」；返回 null 表示该帧不参与清理。
     * <p>
     * 只对流式增量帧分组：它们的内容会被同组末帧一次性覆盖进账本。{@code tool_call} /
     * {@code tool_result} / {@code result} 等骨架帧不参与 —— 数量有限，且重订后前端仍需
     * 它们重建时间线与卡片。
     */
    private static String resolvePruneGroupKey(Map<String, Object> eventData) {
        if (eventData == null) {
            return null;
        }
        String messageType = asText(eventData.get("messageType"));
        if (MESSAGE_GROUPED_STREAM_TYPES.contains(messageType)) {
            String messageId = asText(eventData.get("messageId"));
            return messageId.isEmpty() ? null : "msg:" + messageId;
        }
        if (TOOL_GROUPED_STREAM_TYPES.contains(messageType)) {
            Object nested = eventData.get("resultMap");
            String toolCallId = nested instanceof Map<?, ?> map
                    ? asText(map.get("toolCallId"))
                    : "";
            return toolCallId.isEmpty() ? null : "tool:" + toolCallId;
        }
        return null;
    }

    /** 帧的 eventData：与前端 {@code resultMap.eventData} 同源，isFinal / messageType / messageId 都在这里。 */
    private static Map<String, Object> readEventData(AgentSessionStreamFrame frame) {
        if (frame == null || !(frame.getResultMap() instanceof Map<?, ?> resultMap)) {
            return null;
        }
        Object eventData = resultMap.get("eventData");
        if (!(eventData instanceof Map<?, ?> map)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) map;
        return typed;
    }

    private static String asText(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private void replayBufferedFrames(AgentSessionStream next, long lastEventSeq, boolean allowClosed) {
        List<AgentSessionStreamFrame> snapshot;
        synchronized (bufferLock) {
            snapshot = new ArrayList<>(replayBuffer);
        }
        if (snapshot.isEmpty()) {
            return;
        }
        log.info("{} replay {} buffered frames after rebind",
                request == null ? "-" : request.getRequestId(), snapshot.size());
        for (AgentSessionStreamFrame frame : snapshot) {
            if (frame.getEventSeq() > 0 && frame.getEventSeq() <= lastEventSeq) {
                continue;
            }
            if (next.isAborted() || (!allowClosed && closed.get())) {
                return;
            }
            try {
                next.send(frame);
            } catch (Exception e) {
                log.warn("{} replay frame failed after rebind",
                        request == null ? "-" : request.getRequestId(), e);
                return;
            }
        }
    }

    private List<ObserverSink> liveObservers() {
        List<ObserverSink> live = new ArrayList<>();
        for (ObserverSink sink : observers) {
            if (sink.stream != null && !sink.stream.isAborted()) {
                live.add(sink);
            }
        }
        return live;
    }

    private void detachRegistry() {
        if (projectionRegistry == null || request == null) {
            completeSessionObservers();
            return;
        }
        projectionRegistry.unregister(request.getSessionId(), this, this::completeSessionObservers);
    }

    private void completeSessionObservers() {
        if (eventBus != null && request != null && StringUtils.isNotBlank(request.getSessionId())) {
            eventBus.completeSession(request.getSessionId());
        }
    }

    private void pruneAbortedObservers() {
        observers.removeIf(sink -> sink.stream == null || sink.stream.isAborted());
    }

    private void wireObserverAbort(ObserverSink sink) {
        if (sink == null || sink.stream == null) {
            return;
        }
        sink.stream.onAbort(() -> {
            observers.remove(sink);
            if (closed.get() || !liveObservers().isEmpty()) {
                return;
            }
            for (Runnable handler : abortHandlers) {
                try {
                    handler.run();
                } catch (Exception e) {
                    log.warn("{} projection abort handler failed",
                            request == null ? "-" : request.getRequestId(), e);
                }
            }
        });
    }

    private static AgentSessionStreamFrame buildDefaultResult(AgentExecutionCommand request, String errMsg) {
        return AgentSessionStreamFrame.builder()
                .resultMap(new HashMap<>())
                .status("failed")
                .finished(true)
                .errorMsg(errMsg)
                .reqId(request == null ? null : request.getRequestId())
                .traceId(request == null ? null : request.getRequestId())
                .build();
    }

    private void enrichRunFailed(AgentSessionStreamFrame frame) {
        if (frame == null) {
            return;
        }
        frame.setProtocolVersion(TimelineItemProjection.PROTOCOL_VERSION);
        frame.setEventType("run.failed");
        frame.setEventId(TimelineItemProjection.eventId(
                request == null ? null : request.getSessionId(), frame.getEventSeq(), null));
        frame.setRunId(request == null ? null : request.getRequestId());
    }

    private static final class ObserverSink {
        private final AgentSessionStream stream;

        private ObserverSink(AgentSessionStream stream) {
            this.stream = stream;
        }
    }
}
