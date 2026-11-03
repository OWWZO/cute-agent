package org.wwz.ai.domain.agent.runtime.llm;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.ledger.model.replay.ReplayTiming;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.dto.tool.ToolCall;
import org.wwz.ai.domain.agent.cute.config.CuteConfig;
import org.wwz.ai.domain.agent.runtime.util.StringUtil;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 处理 Domain LLM stream event 的流式响应聚合器。
 * <p>
 * 供应商流对象已经在 LlmCompletionPort 之后被转换为纯 Java 事件；本类只保留
 * 增量推送、工具调用聚合、超时和取消语义。
 */
@Slf4j
public class StreamResponseHandler {

    @Resource
    private CuteConfig cuteConfig;

    public CompletableFuture<String> handleStringStream(AgentContext context,
                                                         LlmCompletionPort.StreamCall streamCall) {
        return handleStringStream(context, streamCall, null, false, true);
    }

    public CompletableFuture<String> handleStringStream(AgentContext context,
                                                         LlmCompletionPort.StreamCall streamCall,
                                                         String hiddenStartMarker,
                                                         boolean emitFinalSnapshot) {
        return handleStringStream(context, streamCall, hiddenStartMarker, emitFinalSnapshot, true);
    }

    public CompletableFuture<String> handleStringStream(AgentContext context,
                                                         LlmCompletionPort.StreamCall streamCall,
                                                         String hiddenStartMarker,
                                                         boolean emitFinalSnapshot,
                                                         boolean pushToClient) {
        return handleStringStreamWithUsage(context, streamCall, hiddenStartMarker, emitFinalSnapshot, pushToClient)
                .thenApply(result -> result == null ? null : result.getContent());
    }

    public CompletableFuture<StringStreamResult> handleStringStreamWithUsage(AgentContext context,
                                                                               LlmCompletionPort.StreamCall streamCall,
                                                                               String hiddenStartMarker,
                                                                               boolean emitFinalSnapshot,
                                                                               boolean pushToClient) {
        return handleStringStreamWithUsage(context, streamCall, hiddenStartMarker, emitFinalSnapshot,
                pushToClient, 0, null);
    }

    public CompletableFuture<StringStreamResult> handleStringStreamWithUsage(AgentContext context,
                                                                               LlmCompletionPort.StreamCall streamCall,
                                                                               String hiddenStartMarker,
                                                                               boolean emitFinalSnapshot,
                                                                               boolean pushToClient,
                                                                               int timeoutSeconds) {
        return handleStringStreamWithUsage(context, streamCall, hiddenStartMarker, emitFinalSnapshot,
                pushToClient, timeoutSeconds, null);
    }

    public CompletableFuture<StringStreamResult> handleStringStreamWithUsage(AgentContext context,
                                                                               LlmCompletionPort.StreamCall streamCall,
                                                                               String hiddenStartMarker,
                                                                               boolean emitFinalSnapshot,
                                                                               boolean pushToClient,
                                                                               int timeoutSeconds,
                                                                               ReplayTiming runtimeTiming) {
        if (context != null && context.isRunCancelled()) {
            return failedFuture(new LlmCancelledException(context.getRunCancelReason(), null));
        }
        if (streamCall == null) {
            return failedFuture(new IllegalArgumentException("LLM stream call must not be null"));
        }

        CompletableFuture<StringStreamResult> future = new CompletableFuture<>();
        StringBuilder allContent = new StringBuilder();
        StringBuilder streamBuffer = new StringBuilder();
        String messageId = canAllocateStreamMessageId(context) ? StringUtil.getUUID() : null;
        int[] intervals = resolveIntervals();
        int[] tokenIndex = new int[]{1};
        int[] emittedLength = new int[]{0};
        int[] hiddenMarkerIndex = new int[]{-1};
        LlmUsageSnapshot[] usageHolder = new LlmUsageSnapshot[]{LlmUsageSnapshot.empty()};

        // partial 只在取消时读取：按需从缓冲区生成快照，避免每个 chunk 都 toString 一遍（O(n²)）。
        java.util.function.Supplier<String> partialContent = () -> snapshotPartial(allContent, hiddenStartMarker);
        try {
            streamCall.subscribe(event -> {
                try {
                    if (abortIfInactive(context, future, streamCall, partialContent)) {
                        return;
                    }
                    usageHolder[0] = usageHolder[0].mergeLatest(
                            LlmUsageSnapshot.resolve(event == null ? null : event.getUsage()));
                    String chunkContent = ReasoningContentExtractor.extractDeltaContent(event);
                    if (StringUtils.isBlank(chunkContent)) {
                        return;
                    }
                    boolean emitted = appendVisibleContent(allContent, chunkContent, hiddenStartMarker,
                            hiddenMarkerIndex, streamBuffer, emittedLength, pushToClient && messageId != null);
                    if (emitted) {
                        if (shouldFlush(tokenIndex[0], intervals[0], intervals[1])) {
                            sendStreamEvent(context, messageId, context.getStreamMessageType(),
                                    streamBuffer.toString(), runtimeTiming, false);
                            streamBuffer.setLength(0);
                        }
                        tokenIndex[0]++;
                    }
                } catch (Exception e) {
                    future.completeExceptionally(e);
                    streamCall.cancel();
                }
            }, error -> {
                if (context != null && context.isRunCancelled()) {
                    abortStream(context, future, streamCall, partialContent);
                    return;
                }
                completeExceptionallyIfActive(future, error);
            }, () -> {
                try {
                    if (abortIfInactive(context, future, streamCall, partialContent)) {
                        return;
                    }
                    if (pushToClient && messageId != null && streamBuffer.length() > 0) {
                        sendStreamEvent(context, messageId, context.getStreamMessageType(),
                                streamBuffer.toString(), runtimeTiming, false);
                    }
                    if (pushToClient && messageId != null && emitFinalSnapshot) {
                        String visibleFinalContent = extractVisibleContent(allContent.toString(), hiddenStartMarker).trim();
                        if (StringUtils.isNotBlank(visibleFinalContent)) {
                            completeRuntimeTiming(runtimeTiming);
                            sendStreamEvent(context, messageId, context.getStreamMessageType(),
                                    visibleFinalContent, runtimeTiming, true);
                        }
                    }
                    completeRuntimeTiming(runtimeTiming);
                    String finalContent = allContent.toString().trim();
                    if (finalContent.isEmpty()) {
                        future.completeExceptionally(new IllegalArgumentException("Empty response from streaming LLM"));
                    } else {
                        future.complete(new StringStreamResult(finalContent, usageHolder[0]));
                    }
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        armStream(context, future, streamCall, partialContent, timeoutSeconds);
        return future;
    }

    public CompletableFuture<LLM.ToolCallResponse> handleToolCallStream(AgentContext context,
                                                                         LlmCompletionPort.StreamCall streamCall,
                                                                         long startTimeMs) {
        return handleToolCallStream(context, streamCall, startTimeMs, true);
    }

    public CompletableFuture<LLM.ToolCallResponse> handleToolCallStream(AgentContext context,
                                                                         LlmCompletionPort.StreamCall streamCall,
                                                                         long startTimeMs,
                                                                         boolean pushToClient) {
        return handleToolCallStream(context, streamCall, startTimeMs, pushToClient, 0, null);
    }

    public CompletableFuture<LLM.ToolCallResponse> handleToolCallStream(AgentContext context,
                                                                         LlmCompletionPort.StreamCall streamCall,
                                                                         long startTimeMs,
                                                                         boolean pushToClient,
                                                                         int timeoutSeconds) {
        return handleToolCallStream(context, streamCall, startTimeMs, pushToClient, timeoutSeconds, null);
    }

    public CompletableFuture<LLM.ToolCallResponse> handleToolCallStream(AgentContext context,
                                                                          LlmCompletionPort.StreamCall streamCall,
                                                                          long startTimeMs,
                                                                          boolean pushToClient,
                                                                          int timeoutSeconds,
                                                                          ReplayTiming runtimeTiming) {
        if (context != null && context.isRunCancelled()) {
            return failedFuture(new LlmCancelledException(context.getRunCancelReason(), null));
        }
        if (streamCall == null) {
            return failedFuture(new IllegalArgumentException("LLM stream call must not be null"));
        }

        CompletableFuture<LLM.ToolCallResponse> future = new CompletableFuture<>();
        StringBuilder allContent = new StringBuilder();
        StringBuilder allReasoning = new StringBuilder();
        StringBuilder streamBuffer = new StringBuilder();
        StringBuilder reasoningBuffer = new StringBuilder();
        String messageId = canAllocateStreamMessageId(context) ? StringUtil.getUUID() : null;
        String reasoningMessageId = canAllocateStreamMessageId(context) ? StringUtil.getUUID() : null;
        int[] intervals = resolveIntervals();
        int[] tokenIndex = new int[]{1};
        Map<String, ToolCallAccumulator> toolCallAccumulators = new LinkedHashMap<>();
        String[] finishReason = new String[1];
        LlmUsageSnapshot[] usageHolder = new LlmUsageSnapshot[]{LlmUsageSnapshot.empty()};
        int[] chunkCount = new int[]{0};
        int[] toolDeltaCount = new int[]{0};
        // partial 只在取消时读取，按需生成快照，避免每个 chunk 都复制整个已累积正文。
        java.util.function.Supplier<String> partialContent = () -> snapshotToolPartial(allContent);

        try {
            streamCall.subscribe(event -> {
                try {
                    if (abortIfInactive(context, future, streamCall, partialContent)) {
                        return;
                    }
                    chunkCount[0]++;
                    List<LlmToolCall> toolCalls = event == null ? null : event.getToolCalls();
                    if (toolCalls != null) {
                        toolDeltaCount[0] += toolCalls.size();
                        mergeToolCalls(toolCalls, toolCallAccumulators);
                    }

                    String chunkReasoning = ReasoningContentExtractor.extractDeltaReasoning(event);
                    String chunkContent = ReasoningContentExtractor.extractDeltaContent(event);
                    if (StringUtils.isNotEmpty(chunkContent)
                            && StringUtils.containsIgnoreCase(chunkContent, "<think>")) {
                        ReasoningContentExtractor.SplitResult tagged =
                                ReasoningContentExtractor.split(chunkContent, chunkReasoning);
                        chunkContent = tagged.content();
                        if (StringUtils.isNotEmpty(tagged.reasoningContent())) {
                            chunkReasoning = tagged.reasoningContent();
                        }
                    }

                    if (StringUtils.isNotEmpty(chunkReasoning)) {
                        String reasoningDelta = appendReasoningChunk(allReasoning, chunkReasoning);
                        if (StringUtils.isNotEmpty(reasoningDelta)
                                && pushToClient && reasoningMessageId != null && context.getPrinter() != null) {
                            reasoningBuffer.append(reasoningDelta);
                            if (shouldFlush(tokenIndex[0], intervals[0], intervals[1])
                                    || reasoningBuffer.length() >= 24) {
                                sendStreamEvent(context, reasoningMessageId, ReasoningContentExtractor.EVENT_TYPE,
                                        reasoningBuffer.toString(), runtimeTiming, false);
                                reasoningBuffer.setLength(0);
                            }
                        }
                    }

                    if (StringUtils.isNotEmpty(chunkContent)) {
                        synchronized (allContent) {
                            allContent.append(chunkContent);
                        }
                        if (pushToClient && messageId != null && context.getPrinter() != null) {
                            streamBuffer.append(chunkContent);
                            if (shouldFlush(tokenIndex[0], intervals[0], intervals[1])) {
                                sendStreamEvent(context, messageId, context.getStreamMessageType(),
                                        streamBuffer.toString(), runtimeTiming, false);
                                streamBuffer.setLength(0);
                            }
                            tokenIndex[0]++;
                        }
                    }

                    if (event != null && StringUtils.isNotBlank(event.getFinishReason())) {
                        finishReason[0] = event.getFinishReason();
                    }
                    usageHolder[0] = usageHolder[0].mergeLatest(
                            LlmUsageSnapshot.resolve(event == null ? null : event.getUsage()));
                } catch (Exception e) {
                    future.completeExceptionally(e);
                    streamCall.cancel();
                }
            }, error -> {
                if (context != null && context.isRunCancelled()) {
                    abortStream(context, future, streamCall, partialContent);
                    return;
                }
                completeExceptionallyIfActive(future, error);
            }, () -> {
                try {
                    if (abortIfInactive(context, future, streamCall, partialContent)) {
                        return;
                    }
                    List<ToolCall> toolCalls = buildToolCalls(toolCallAccumulators);
                    ReasoningContentExtractor.SplitResult finalSplit =
                            ReasoningContentExtractor.split(allContent.toString(), allReasoning.toString());
                    String content = finalSplit.content();
                    String reasoningContent = finalSplit.reasoningContent();
                    boolean hasToolCalls = toolCalls != null && !toolCalls.isEmpty();
                    boolean hasReasoning = StringUtils.isNotBlank(reasoningContent);
                    boolean hasContent = StringUtils.isNotBlank(content);

                    if (pushToClient && reasoningMessageId != null && context.getPrinter() != null && hasReasoning) {
                        if (reasoningBuffer.length() > 0) {
                            sendStreamEvent(context, reasoningMessageId, ReasoningContentExtractor.EVENT_TYPE,
                                    reasoningBuffer.toString(), runtimeTiming, false);
                            reasoningBuffer.setLength(0);
                        }
                        completeRuntimeTiming(runtimeTiming);
                        sendStreamEvent(context, reasoningMessageId, ReasoningContentExtractor.EVENT_TYPE,
                                reasoningContent, runtimeTiming, true);
                    }
                    if (pushToClient && messageId != null && hasContent && context.getPrinter() != null) {
                        if (streamBuffer.length() > 0) {
                            sendStreamEvent(context, messageId, context.getStreamMessageType(),
                                    streamBuffer.toString(), runtimeTiming, false);
                            streamBuffer.setLength(0);
                        }
                        completeRuntimeTiming(runtimeTiming);
                        // moreRounds：本段之后是否还有模型轮（hasToolCalls=true 说明本轮调了工具，
                        // 循环会继续）。前端据此把中间轮发言封口成时间线上独立的助手文本行，
                        // 终轮则留在结论区由 result 收口 —— 对齐 ZCode 的行模型。
                        sendStreamEvent(context, messageId, context.getStreamMessageType(),
                                content, runtimeTiming, true, Map.of("moreRounds", hasToolCalls));
                    }

                    completeRuntimeTiming(runtimeTiming);
                    if (!hasContent && !hasToolCalls && !hasReasoning) {
                        String requestId = context == null ? "-" : context.getRequestId();
                        log.warn("{} empty streaming tool-call response: chunks={}, toolDeltas={}, accumulators={}, finishReason={}, usage={}",
                                requestId, chunkCount[0], toolDeltaCount[0], toolCallAccumulators.size(),
                                finishReason[0], usageHolder[0].getTotalTokens());
                        future.completeExceptionally(new IllegalArgumentException(
                                String.format("Empty response from streaming LLM (chunks=%d, toolDeltas=%d, accumulators=%d, finishReason=%s). "
                                                + "Check model endpoint, tools schema size, and whether tool_call deltas were dropped.",
                                        chunkCount[0], toolDeltaCount[0], toolCallAccumulators.size(), finishReason[0])));
                        return;
                    }

                    LLM.ToolCallResponse response = LLM.ToolCallResponse.builder()
                            .content(hasContent ? content : null)
                            .reasoningContent(hasReasoning ? reasoningContent : null)
                            .toolCalls(toolCalls)
                            .streamMessageId(messageId)
                            .finishReason(finishReason[0])
                            .duration(System.currentTimeMillis() - startTimeMs)
                            .timing(snapshotTiming(runtimeTiming))
                            .build();
                    applyUsage(response, usageHolder[0]);
                    future.complete(response);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        armStream(context, future, streamCall, partialContent, timeoutSeconds);
        return future;
    }

    private void armStream(AgentContext context,
                           CompletableFuture<?> future,
                           LlmCompletionPort.StreamCall streamCall,
                           java.util.function.Supplier<String> partialContent,
                           int timeoutSeconds) {
        Runnable unregister = context == null
                ? () -> {
                }
                : context.registerRunAbort(() -> abortStream(context, future, streamCall, partialContent));
        wireStreamLifecycle(future, streamCall, timeoutSeconds, unregister);
    }

    private static void wireStreamLifecycle(CompletableFuture<?> future,
                                            LlmCompletionPort.StreamCall streamCall,
                                            int timeoutSeconds,
                                            Runnable unregister) {
        if (future == null) {
            return;
        }
        Runnable release = () -> {
            if (unregister != null) {
                unregister.run();
            }
            cancelQuietly(streamCall);
        };
        if (timeoutSeconds > 0) {
            CompletableFuture.delayedExecutor(timeoutSeconds, TimeUnit.SECONDS).execute(() -> {
                if (future.completeExceptionally(new TimeoutException("LLM stream timeout after " + timeoutSeconds + "s"))) {
                    release.run();
                }
            });
        }
        future.whenComplete((ignored, error) -> release.run());
    }

    private static boolean abortIfInactive(AgentContext context,
                                           CompletableFuture<?> future,
                                           LlmCompletionPort.StreamCall streamCall,
                                           java.util.function.Supplier<String> partialContent) {
        if (future.isDone()) {
            cancelQuietly(streamCall);
            return true;
        }
        if (context != null && context.isRunCancelled()) {
            abortStream(context, future, streamCall, partialContent);
            return true;
        }
        return false;
    }

    private static void abortStream(AgentContext context,
                                    CompletableFuture<?> future,
                                    LlmCompletionPort.StreamCall streamCall,
                                    java.util.function.Supplier<String> partialContent) {
        if (future == null || future.isDone()) {
            cancelQuietly(streamCall);
            return;
        }
        String partial = null;
        if (partialContent != null) {
            try {
                partial = partialContent.get();
            } catch (RuntimeException ignored) {
                partial = null;
            }
        }
        String reason = context == null ? null : context.getRunCancelReason();
        future.completeExceptionally(new LlmCancelledException(reason, partial));
        cancelQuietly(streamCall);
    }

    private static void completeExceptionallyIfActive(CompletableFuture<?> future, Throwable error) {
        if (future != null && !future.isDone()) {
            future.completeExceptionally(error);
        }
    }

    private static void cancelQuietly(LlmCompletionPort.StreamCall streamCall) {
        if (streamCall != null) {
            try {
                streamCall.cancel();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private boolean canAllocateStreamMessageId(AgentContext context) {
        return context != null
                && Boolean.TRUE.equals(context.getIsStream())
                && StringUtils.isNotBlank(context.getStreamMessageType());
    }

    private void sendStreamEvent(AgentContext context,
                                 String messageId,
                                 String messageType,
                                 Object message,
                                 ReplayTiming runtimeTiming,
                                 boolean isFinal) {
        sendStreamEvent(context, messageId, messageType, message, runtimeTiming, isFinal, null);
    }

    /** 带额外 resultMap 键的流事件：帧语义由前端按 these keys 解释（如 moreRounds 分段标记）。 */
    private void sendStreamEvent(AgentContext context,
                                 String messageId,
                                 String messageType,
                                 Object message,
                                 ReplayTiming runtimeTiming,
                                 boolean isFinal,
                                 Map<String, Object> extraResultMap) {
        if (context == null || context.getPrinter() == null) {
            return;
        }
        if (runtimeTiming == null && (extraResultMap == null || extraResultMap.isEmpty())) {
            context.getPrinter().send(messageId, messageType, message, isFinal);
            return;
        }
        Map<String, Object> resultMap = new LinkedHashMap<>();
        if (runtimeTiming != null) {
            resultMap.put("timing", snapshotTiming(runtimeTiming));
        }
        if (extraResultMap != null) {
            resultMap.putAll(extraResultMap);
        }
        context.getPrinter().sendWithResultMap(messageId, messageType, message, resultMap, isFinal);
    }

    private void completeRuntimeTiming(ReplayTiming timing) {
        ReplayTiming.finishRuntime(timing);
    }

    private ReplayTiming snapshotTiming(ReplayTiming timing) {
        return ReplayTiming.copyOf(timing);
    }

    private int[] resolveIntervals() {
        String rawConfig = cuteConfig == null || cuteConfig.getMessageInterval() == null
                ? "1,3"
                : cuteConfig.getMessageInterval().getOrDefault("llm", "1,3");
        String[] intervalConfig = rawConfig.split(",");
        if (intervalConfig.length < 2) {
            log.warn("消息间隔配置格式非法，回退默认值 1,3: {}", rawConfig);
            return new int[]{1, 3};
        }
        try {
            int firstInterval = Math.max(1, Integer.parseInt(intervalConfig[0].trim()));
            int sendInterval = Math.max(1, Integer.parseInt(intervalConfig[1].trim()));
            return new int[]{firstInterval, sendInterval};
        } catch (NumberFormatException e) {
            log.warn("消息间隔配置无法解析，回退默认值 1,3: {}", rawConfig);
            return new int[]{1, 3};
        }
    }

    private boolean shouldFlush(int tokenIndex, int firstInterval, int sendInterval) {
        return tokenIndex == firstInterval || tokenIndex % sendInterval == 0;
    }

    /**
     * 追加 reasoning 增量。用 CharSequence 前缀比较代替 {@code all.toString()}，
     * 避免每个 chunk 都复制已累积的完整推理正文。
     */
    private static String appendReasoningChunk(StringBuilder all, String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return "";
        }
        if (all.length() == 0) {
            all.append(chunk);
            return chunk;
        }
        if (startsWith(chunk, all)) {
            String delta = chunk.substring(all.length());
            all.setLength(0);
            all.append(chunk);
            return delta;
        }
        if (startsWith(all, chunk)) {
            return "";
        }
        all.append(chunk);
        return chunk;
    }

    private static boolean startsWith(CharSequence text, CharSequence prefix) {
        if (text == null || prefix == null || text.length() < prefix.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if (text.charAt(i) != prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 追加当前 chunk 并计算可见长度。marker 只会出现一次且一旦出现就不再展开，
     * 因此只需从上次扫描位置附近继续查找，避免每个 chunk 全量重扫。
     *
     * @return 是否有新的可见内容被写进 streamBuffer
     */
    private static boolean appendVisibleContent(StringBuilder allContent,
                                                String chunkContent,
                                                String hiddenStartMarker,
                                                int[] hiddenMarkerIndex,
                                                StringBuilder streamBuffer,
                                                int[] emittedLength,
                                                boolean pushContent) {
        synchronized (allContent) {
            int previousLength = allContent.length();
            allContent.append(chunkContent);
            int markerIndex = hiddenMarkerIndex[0];
            if (markerIndex < 0 && StringUtils.isNotBlank(hiddenStartMarker)) {
                int searchFrom = Math.max(0, previousLength - hiddenStartMarker.length() + 1);
                markerIndex = allContent.indexOf(hiddenStartMarker, searchFrom);
                hiddenMarkerIndex[0] = markerIndex;
            }
            int visibleLength = markerIndex >= 0 ? markerIndex : allContent.length();
            if (!pushContent || visibleLength <= emittedLength[0]) {
                return false;
            }
            streamBuffer.append(allContent, emittedLength[0], visibleLength);
            emittedLength[0] = visibleLength;
            return true;
        }
    }

    /** 取消时生成可见正文快照；与订阅线程的追加共用同一把锁。 */
    private static String snapshotPartial(StringBuilder allContent, String hiddenStartMarker) {
        synchronized (allContent) {
            String visible = extractVisibleContent(allContent, hiddenStartMarker);
            String trimmed = visible.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
    }

    private static String snapshotToolPartial(StringBuilder allContent) {
        synchronized (allContent) {
            return allContent.toString().trim();
        }
    }

    private static String extractVisibleContent(CharSequence allContent, String hiddenStartMarker) {
        String content = allContent.toString();
        if (StringUtils.isBlank(hiddenStartMarker)) {
            return content;
        }
        int markerIndex = content.indexOf(hiddenStartMarker);
        return markerIndex >= 0 ? content.substring(0, markerIndex) : content;
    }

    private void mergeToolCalls(List<LlmToolCall> toolCalls,
                                Map<String, ToolCallAccumulator> toolCallAccumulators) {
        int index = 0;
        for (LlmToolCall toolCall : toolCalls) {
            if (toolCall == null) {
                index++;
                continue;
            }
            String key = StringUtils.isNotBlank(toolCall.getId()) ? toolCall.getId() : "idx#" + index;
            if (StringUtils.isNotBlank(toolCall.getId())
                    && toolCallAccumulators.containsKey("idx#" + index)) {
                ToolCallAccumulator existing = toolCallAccumulators.remove("idx#" + index);
                toolCallAccumulators.put(toolCall.getId(), existing);
                key = toolCall.getId();
            }
            ToolCallAccumulator accumulator = toolCallAccumulators.computeIfAbsent(key,
                    ignored -> new ToolCallAccumulator());
            accumulator.merge(toolCall);
            index++;
        }
    }

    private List<ToolCall> buildToolCalls(Map<String, ToolCallAccumulator> accumulators) {
        List<ToolCall> toolCalls = new ArrayList<>();
        for (ToolCallAccumulator accumulator : accumulators.values()) {
            ToolCall toolCall = accumulator.toToolCall();
            if (toolCall != null) {
                toolCalls.add(toolCall);
            }
        }
        return toolCalls;
    }

    private void applyUsage(LLM.ToolCallResponse response, LlmUsageSnapshot usage) {
        LlmUsageSnapshot.applyTo(response, usage);
    }

    private static class ToolCallAccumulator {
        private String id;
        private String type;
        private String name;
        private String arguments = "";

        void merge(LlmToolCall toolCall) {
            if (StringUtils.isNotBlank(toolCall.getId())) {
                id = toolCall.getId();
            }
            if (StringUtils.isNotBlank(toolCall.getType())) {
                type = toolCall.getType();
            }
            if (StringUtils.isNotBlank(toolCall.getName())) {
                name = toolCall.getName();
            }
            String incoming = StringUtils.defaultString(toolCall.getArguments());
            if (StringUtils.isBlank(incoming)) {
                return;
            }
            if (StringUtils.isBlank(arguments)) {
                arguments = incoming;
            } else if (!incoming.equals(arguments) && !arguments.startsWith(incoming)) {
                if (incoming.startsWith(arguments)) {
                    arguments = incoming;
                } else {
                    arguments = arguments + incoming;
                }
            }
        }

        ToolCall toToolCall() {
            if (StringUtils.isBlank(name)) {
                return null;
            }
            return ToolCall.builder()
                    .id(StringUtils.defaultIfBlank(id, StringUtil.getUUID()))
                    .type(StringUtils.defaultIfBlank(type, "function"))
                    .function(ToolCall.Function.builder()
                            .name(name)
                            .arguments(StringUtils.defaultIfBlank(arguments, "{}"))
                            .build())
                    .build();
        }
    }

    private <T> CompletableFuture<T> failedFuture(Throwable error) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(error);
        return future;
    }

    @Data
    @AllArgsConstructor
    public static class StringStreamResult {
        private String content;
        private LlmUsageSnapshot usage;
    }
}
