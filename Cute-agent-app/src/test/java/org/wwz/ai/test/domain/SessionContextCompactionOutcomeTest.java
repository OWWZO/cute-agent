package org.wwz.ai.test.domain;

import com.alibaba.fastjson.JSON;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.wwz.ai.domain.agent.memory.CompactionBudget;
import org.wwz.ai.domain.agent.memory.CompactionPrompt;
import org.wwz.ai.domain.agent.memory.SessionWorkingMemoryService;
import org.wwz.ai.domain.agent.memory.WorkingMemoryCompactionEvent;
import org.wwz.ai.domain.agent.memory.WorkingMemoryCompactor;
import org.wwz.ai.domain.agent.runtime.CuteRuntimeDependencies;
import org.wwz.ai.domain.agent.runtime.dto.Message;
import org.wwz.ai.domain.agent.runtime.llm.ContextTokenTracker;
import org.wwz.ai.domain.agent.runtime.llm.PromptShape;
import org.wwz.ai.domain.agent.runtime.llm.TokenCounter;
import org.wwz.ai.infrastructure.dao.cute.IWorkingMemoryCompactionDao;
import org.wwz.ai.infrastructure.cute.service.impl.SessionContextCompactionServiceImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class SessionContextCompactionOutcomeTest {

    /**
     * 守卫：CompactionProperties 的字段初始值必须与 CompactionBudget.defaults() 一致。
     * 两者是同一份压缩配置的两个载体（前者绑 yml，后者是 domain 值对象），漂移即配置错乱。
     */
    @Test
    public void compactionPropertiesDefaultsMatchBudgetDefaults() {
        org.wwz.ai.config.cute.CompactionProperties props = new org.wwz.ai.config.cute.CompactionProperties();
        CompactionBudget defaults = CompactionBudget.defaults();
        Assert.assertEquals(props.isEnabled(), defaults.isEnabled());
        Assert.assertEquals(props.isLlmEnabled(), defaults.isLlmEnabled());
        Assert.assertEquals(props.isMicroEnabled(), defaults.isMicroEnabled());
        Assert.assertEquals(props.isSessionMemoryEnabled(), defaults.isSessionMemoryEnabled());
        Assert.assertEquals(props.getThresholdPercent(), defaults.getThresholdPercent(), 0.0d);
        Assert.assertEquals(props.getKeepMinTokens(), defaults.getKeepMinTokens());
        Assert.assertEquals(props.getKeepMinTextMessages(), defaults.getKeepMinTextMessages());
        Assert.assertEquals(props.getKeepMaxTokens(), defaults.getKeepMaxTokens());
        Assert.assertEquals(props.getMaxConsecutiveFailures(), defaults.getMaxConsecutiveFailures());
        Assert.assertEquals(props.getTemperature(), defaults.getTemperature(), 0.0d);
        Assert.assertEquals(props.getMessageContentCharLimit(), defaults.getMessageContentCharLimit());
        Assert.assertEquals(props.getMicroKeepRecentToolResults(), defaults.getMicroKeepRecentToolResults());
        Assert.assertEquals(props.getMicroToolResultMaxChars(), defaults.getMicroToolResultMaxChars());
        Assert.assertEquals(props.getSummaryTargetRatio(), defaults.getSummaryTargetRatio(), 0.0d);
        Assert.assertEquals(props.getProtectFirstN(), defaults.getProtectFirstN());
        Assert.assertEquals(props.getProtectLastN(), defaults.getProtectLastN());
        Assert.assertEquals(props.getContentMaxChars(), defaults.getContentMaxChars());
        Assert.assertEquals(props.getContentHeadChars(), defaults.getContentHeadChars());
        Assert.assertEquals(props.getContentTailChars(), defaults.getContentTailChars());
        Assert.assertEquals(props.getSummaryInputMaxChars(), defaults.getSummaryInputMaxChars());
        Assert.assertEquals(props.getSummarizerTimeoutSeconds(), defaults.getSummarizerTimeoutSeconds());
        Assert.assertEquals(props.isMidRunEnabled(), defaults.isMidRunEnabled());
        Assert.assertEquals(props.isPersistProjection(), defaults.isPersistProjection());
        Assert.assertEquals(props.isAuditEnabled(), defaults.isAuditEnabled());
        Assert.assertEquals(props.isAuditStoreMessages(), defaults.isAuditStoreMessages());
    }

    @Test
    public void structuredCheckpointAndFailureFallbackAreStable() {
        Assert.assertTrue(CompactionPrompt.getCompactPrompt().contains("## Historical Task Snapshot"));
        Assert.assertFalse(CompactionPrompt.getCompactPrompt().contains("<analysis>"));
        Assert.assertTrue(CompactionPrompt.getIterativeUpdatePrompt().contains("PREVIOUS SUMMARY"));
        Assert.assertTrue(CompactionPrompt.getIterativeUpdatePrompt().contains("NEW TURNS TO INCORPORATE"));
        Assert.assertTrue(CompactionPrompt.getCompactPrompt().contains("## Blocked"));
        Assert.assertTrue(CompactionPrompt.getCompactPrompt(952).contains("under 952 characters"));
        Assert.assertTrue(CompactionPrompt.getIterativeUpdatePrompt(952)
                .contains("under 952 characters"));
        Assert.assertFalse(CompactionPrompt.getIterativeUpdatePrompt().contains("{new_turns}"));
        String wrapped = CompactionPrompt.wrapSummaryForReinject("## Goal\nship it", true);
        Assert.assertTrue(wrapped.startsWith(CompactionPrompt.CONTEXT_COMPACTION_PREFIX));
        Assert.assertTrue(wrapped.contains(CompactionPrompt.END_MARKER));
        Assert.assertEquals("permanent",
                SessionContextCompactionServiceImpl.classifyFailure(new IllegalStateException("401 quota")));
        Assert.assertEquals("transient",
                SessionContextCompactionServiceImpl.classifyFailure(new IllegalStateException("timeout")));
        Assert.assertEquals("transient",
                SessionContextCompactionServiceImpl.classifyFailure(new TimeoutException("summarizer exceeded")));
        Assert.assertEquals(SessionContextCompactionServiceImpl.STRATEGY_FULL_LLM, "full-llm");
        Assert.assertEquals(SessionContextCompactionServiceImpl.STRATEGY_LOCAL_FALLBACK, "local-fallback");
        Assert.assertEquals(SessionContextCompactionServiceImpl.STRATEGY_ABORT_UNCHANGED, "abort-unchanged");
        Assert.assertEquals(SessionContextCompactionServiceImpl.STRATEGY_DROP_OLDEST, "drop-oldest");
    }

    @Test
    public void structuredCheckpointReportsMissingCarryForwardSections() {
        String incomplete = """
                ## Goal
                finish the task
                ## Constraints & Preferences
                do not delete data
                """;
        Assert.assertEquals(
                List.of("Active State"),
                CompactionPrompt.missingRequiredCheckpointSections(incomplete));

        CompactionPrompt.CheckpointFormatException error = Assert.assertThrows(
                CompactionPrompt.CheckpointFormatException.class,
                () -> CompactionPrompt.formatAndValidateCompactSummary(incomplete));
        Assert.assertTrue(error.getMessage().contains("Active State"));

        String complete = """
                ## Goal
                finish the task
                ## Constraints & Preferences
                do not delete data
                ## Active State
                generate the final report
                """;
        Assert.assertTrue(
                CompactionPrompt.missingRequiredCheckpointSections(complete).isEmpty());
    }

    @Test
    public void checkpointNormalizesRealModelHeadingVariants() {
        String raw = "\u001B[5;1H### **Goal:** preserve GOAL-ALPHA\n"
                + "### 约束与偏好：do not delete raw data\n"
                + "#### 当前状态：generate the final report\n"
                + "## Historical Task Snapshothandoff facts\n"
                + "## Completed Actions-produced the checkpoint\n"
                + "## BlockedNone## Key Decisionscontinue with verification\n";

        String normalized = CompactionPrompt.formatAndValidateCompactSummary(raw);

        Assert.assertFalse(normalized.contains("\u001B"));
        Assert.assertTrue(normalized.contains("## Goal\npreserve GOAL-ALPHA"));
        Assert.assertTrue(normalized.contains(
                "## Constraints & Preferences\ndo not delete raw data"));
        Assert.assertTrue(normalized.contains(
                "## Active State\ngenerate the final report"));
        Assert.assertTrue(normalized.contains(
                "## Historical Task Snapshot\nhandoff facts"));
        Assert.assertTrue(normalized.contains(
                "## Completed Actions\nproduced the checkpoint"));
        Assert.assertTrue(normalized.contains("## Blocked\nNone"));
        Assert.assertTrue(normalized.contains(
                "## Key Decisions\ncontinue with verification"));
        Assert.assertTrue(
                CompactionPrompt.missingRequiredCheckpointSections(raw).isEmpty());
    }

    @Test
    public void checkpointPreservesLiteralSourceIdentifiers() {
        String summary = """
                ## Goal
                preserve the active task
                ## Critical Context
                existing fact
                """;

        String preserved = CompactionPrompt.preserveLiteralIdentifiers(
                summary,
                List.of(
                        "目标 GOAL-BETA：保持当前状态",
                        "输出 UNKNOWN-ACTIVE",
                        "已完成动作 DONE-REPORT-77，下一步 NEXT-VERIFY-77",
                        "下一步标识 NEXT-STEP-REPORT"));

        Assert.assertTrue(preserved.contains(
                "- Final answer MUST include these literal identifiers verbatim: "
                        + "GOAL-BETA, UNKNOWN-ACTIVE, "
                        + "DONE-REPORT-77, NEXT-VERIFY-77, NEXT-STEP-REPORT"));
        Assert.assertTrue(preserved.contains("existing fact"));
    }

    @Test
    public void finalHandoffPreservesLiteralIdentifiersAfterWrapping() {
        String handoff = CompactionPrompt.wrapSummaryForReinject(
                """
                        ## Goal
                        keep the current task
                        ## Critical Context
                        existing fact
                        """,
                true);

        String preserved = CompactionPrompt.preserveLiteralIdentifiersInHandoff(
                handoff,
                List.of(
                        "目标 GOAL-BETA：保持当前状态",
                        "约束 CONSTRAINT-NO-DELETE：不得删除原始数据",
                        "下一步 NEXT-STEP-REPORT"));

        Assert.assertTrue(preserved.contains(
                "- Final answer MUST include these literal identifiers verbatim: "
                        + "GOAL-BETA, CONSTRAINT-NO-DELETE, NEXT-STEP-REPORT"));
        Assert.assertTrue(preserved.contains(CompactionPrompt.END_MARKER));
        Assert.assertTrue(preserved.indexOf("Final answer MUST include these literal identifiers")
                < preserved.indexOf(CompactionPrompt.END_MARKER));
    }

    @Test
    public void negativeIdentifierMentionsAreNotForcedIntoFinalAnswer() {
        String preserved = CompactionPrompt.preserveLiteralIdentifiers(
                """
                        ## Goal
                        keep the current task
                        ## Critical Context
                        existing fact
                        """,
                List.of(
                        "不得编造 ACTIVE-FAKE",
                        "输出 UNKNOWN-ACTIVE"));

        Assert.assertFalse(preserved.contains("ACTIVE-FAKE"));
        Assert.assertTrue(preserved.contains("UNKNOWN-ACTIVE"));
    }

    @Test
    public void constraintIdentifierIsNotFilteredByItsProhibitiveText() {
        String preserved = CompactionPrompt.preserveLiteralIdentifiers(
                """
                        ## Goal
                        keep the current task
                        ## Critical Context
                        existing fact
                        """,
                List.of("约束 CONSTRAINT-NO-DELETE：任何情况下都不能删除原始数据"));

        Assert.assertTrue(preserved.contains(
                "- Final answer MUST include these literal identifiers verbatim: "
                        + "CONSTRAINT-NO-DELETE"));
    }

    @Test
    public void staticFallbackProducesCheckpoint() {
        WorkingMemoryCompactor compactor = new WorkingMemoryCompactor();
        String fallback = compactor.buildStaticFallbackSummary(
                List.of(
                        Message.userMessage("old request", null),
                        Message.assistantMessage("x".repeat(20_000), null)),
                null,
                "latest request");
        Assert.assertTrue(fallback.contains("## Historical Task Snapshot"));
        Assert.assertTrue(fallback.contains("## Active State"));
        Assert.assertFalse(fallback.isBlank());
        Assert.assertTrue(fallback.length() < 4000);
        Assert.assertFalse(CompactionBudget.defaults().isSessionMemoryEnabled());
    }

    @Test
    public void applyIfNeededUsesFullLlmStrategy() {
        AtomicReference<WorkingMemoryCompactionEvent> audited = new AtomicReference<>();
        List<Message> input = oversizedMessages();
        String handoff = CompactionPrompt.wrapSummaryForReinject("## Goal\ndone\n## Active State\nok", true);
        List<Message> compactResult = List.of(
                Message.userMessage(handoff, null),
                Message.userMessage("latest", null),
                Message.assistantMessage("reply", null)
        );
        RecordingService service = new RecordingService(audited, compactResult, null);
        List<Message> out = service.applyIfNeeded("s1", "main", "req-1", input);
        Assert.assertTrue(out.stream().anyMatch(m -> m.getContent() != null
                && m.getContent().contains(CompactionPrompt.CONTEXT_COMPACTION_PREFIX)));
        Assert.assertEquals(SessionContextCompactionServiceImpl.STRATEGY_FULL_LLM, audited.get().getStrategy());
        Assert.assertEquals(WorkingMemoryCompactionEvent.STATUS_SUCCESS, audited.get().getStatus().intValue());
    }

    @Test
    public void applyIfNeededUsesLocalFallbackOnTransientFailure() {
        AtomicReference<WorkingMemoryCompactionEvent> audited = new AtomicReference<>();
        String handoff = CompactionPrompt.wrapSummaryForReinject("## Goal\nfallback", true);
        List<Message> smallFallback = List.of(
                Message.userMessage(handoff, null),
                Message.userMessage("latest", null));
        RecordingService service = new RecordingService(audited, null, new IllegalStateException("502 timeout"));
        service.fallbackResult = smallFallback;
        List<Message> out = service.applyIfNeeded("s1", "main", "req-2", oversizedMessages());
        Assert.assertTrue(out.stream().anyMatch(m -> m.getContent() != null
                && m.getContent().contains(CompactionPrompt.CONTEXT_COMPACTION_PREFIX)));
        Assert.assertEquals(SessionContextCompactionServiceImpl.STRATEGY_LOCAL_FALLBACK, audited.get().getStrategy());
    }

    @Test
    public void applyIfNeededAbortsUnchangedOnPermanentFailure() {
        AtomicReference<WorkingMemoryCompactionEvent> audited = new AtomicReference<>();
        List<Message> input = oversizedMessages();
        Message marker = input.get(0);
        RecordingService service = new RecordingService(audited, null, new IllegalStateException("401 insufficient_quota"));
        List<Message> out = service.applyIfNeeded("s1", "main", "req-3", input);
        Assert.assertSame(marker, out.get(0));
        Assert.assertEquals(input.size(), out.size());
        Assert.assertFalse(out.get(0).getContent().contains("[memory"));
        Assert.assertEquals(SessionContextCompactionServiceImpl.STRATEGY_ABORT_UNCHANGED, audited.get().getStrategy());
        Assert.assertEquals(WorkingMemoryCompactionEvent.STATUS_FAILED, audited.get().getStatus().intValue());
    }

    @Test
    public void applyIfNeededUsesDropOldestWhenCompactStillOverThreshold() {
        AtomicReference<WorkingMemoryCompactionEvent> audited = new AtomicReference<>();
        List<Message> stillHuge = oversizedMessages();
        RecordingService service = new RecordingService(audited, stillHuge, null);
        List<Message> out = service.applyIfNeeded("s1", "main", "req-4", oversizedMessages());
        Assert.assertTrue(out.size() < stillHuge.size());
        Assert.assertEquals("full-llm+drop-oldest", audited.get().getStrategy());
    }

    @Test
    public void dropOldestAfterFullLlmKeepsHandoff() {
        AtomicReference<WorkingMemoryCompactionEvent> audited = new AtomicReference<>();
        String handoff = CompactionPrompt.wrapSummaryForReinject("## Goal\nkeep-me\n## Active State\nok", true);
        List<Message> stillHuge = new ArrayList<>();
        stillHuge.add(Message.userMessage(handoff, null));
        stillHuge.addAll(oversizedMessages());
        RecordingService service = new RecordingService(audited, stillHuge, null);
        List<Message> out = service.applyIfNeeded("s1", "main", "req-5", oversizedMessages());
        Assert.assertTrue(out.stream().anyMatch(m -> m.getContent() != null
                && m.getContent().contains(CompactionPrompt.CONTEXT_COMPACTION_PREFIX)));
        Assert.assertEquals("full-llm+drop-oldest", audited.get().getStrategy());
    }

    @Test
    public void applyIfNeededUsesLocalFallbackWhenCircuitOpen() {
        AtomicReference<WorkingMemoryCompactionEvent> audited = new AtomicReference<>();
        RecordingService service = new RecordingService(audited, null, new IllegalStateException("network blip"));
        String handoff = CompactionPrompt.wrapSummaryForReinject("## Goal\ncircuit", true);
        service.fallbackResult = List.of(Message.userMessage(handoff, null), Message.userMessage("latest", null));
        List<Message> input = oversizedMessages();
        service.applyIfNeeded("s-circuit", "main", "r1", input);
        service.applyIfNeeded("s-circuit", "main", "r2", input);
        service.applyIfNeeded("s-circuit", "main", "r3", input);
        audited.set(null);
        service.failWith = null;
        service.compactResult = null;
        service.forceCircuit = true;
        List<Message> out = service.applyIfNeeded("s-circuit", "main", "r4", input);
        Assert.assertTrue(out.stream().anyMatch(m -> m.getContent() != null
                && m.getContent().contains(CompactionPrompt.CONTEXT_COMPACTION_PREFIX)));
        Assert.assertEquals(SessionContextCompactionServiceImpl.STRATEGY_LOCAL_FALLBACK, audited.get().getStrategy());
    }

    @Test
    public void firstStepCompactsWhenSystemPromptIsHugeAndHistoryIsSmall() {
        AtomicReference<WorkingMemoryCompactionEvent> audited = new AtomicReference<>();
        String handoff = CompactionPrompt.wrapSummaryForReinject("## Goal\nhuge-system\n## Active State\nok", true);
        List<Message> compactResult = List.of(
                Message.userMessage(handoff, null),
                Message.userMessage("hi", null)
        );
        RecordingService service = new RecordingService(audited, compactResult, null);
        List<Message> smallHistory = List.of(Message.userMessage("hi", null));
        PromptShape shape = PromptShape.functionCall(
                Message.systemMessage("S".repeat(8_000), null),
                smallHistory,
                null);
        List<Message> out = service.applyIfNeededMidRun(
                "s-huge-sys", "main", "req-sys", smallHistory, null, shape, null);
        Assert.assertTrue(out.stream().anyMatch(m -> m.getContent() != null
                && m.getContent().contains(CompactionPrompt.CONTEXT_COMPACTION_PREFIX)));
        Assert.assertEquals(SessionContextCompactionServiceImpl.STRATEGY_FULL_LLM, audited.get().getStrategy());
    }

    @Test
    public void midRunUsesProviderUsageSnapshotInsteadOfReestimatingHistory() {
        AtomicReference<WorkingMemoryCompactionEvent> audited = new AtomicReference<>();
        RecordingService service = new RecordingService(audited, oversizedMessages(), null);
        List<Message> huge = oversizedMessages();
        TokenCounter counter = new TokenCounter();
        PromptShape shape = PromptShape.functionCall(null, huge, null);
        ContextTokenTracker.Snapshot snapshot = ContextTokenTracker.Snapshot.builder()
                .usageAvailable(true)
                .lastProviderContextTokens(100)
                .anchorMessageCount(huge.size())
                .promptShapeFingerprint(counter.fingerprint(shape))
                .build();
        List<Message> out = service.applyIfNeededMidRun(
                "s-usage", "main", "req-usage", huge, null, shape, snapshot);
        Assert.assertEquals(huge.size(), out.size());
        Assert.assertNull(audited.get());
    }

    @Test
    public void midRunTokenDecisionDoesNotQueryWorkingMemory() {
        SessionWorkingMemoryService workingMemory = mock(SessionWorkingMemoryService.class);
        AtomicInteger daoWrites = new AtomicInteger();
        IWorkingMemoryCompactionDao dao = event -> {
            daoWrites.incrementAndGet();
            return 1;
        };
        SessionContextCompactionServiceImpl service = new SessionContextCompactionServiceImpl(
                RecordingService.nullProvider(),
                workingMemory,
                dao,
                CompactionBudget.defaults().toBuilder()
                        .microEnabled(false)
                        .keepMinTokens(20)
                        .keepMinTextMessages(1)
                        .keepMaxTokens(200)
                        .auditStoreMessages(false)
                        .build(),
                null);
        List<Message> small = List.of(Message.userMessage("hi", null));
        PromptShape shape = PromptShape.text(null, small);
        service.applyIfNeededMidRun("s-mem", "main", "req-mem", small, null, shape, null);
        verify(workingMemory, never()).loadReadyMessages(anyString(), anyString(), anyString());
        verify(workingMemory, never()).replaceReadyProjection(anyString(), anyString(), anyString(), any());
        Assert.assertEquals(0, daoWrites.get());
    }

    @Test
    public void unchangedMessagesAreNotPersistedWhenRequestTokensIncludeLargeSystemPrompt() {
        SessionWorkingMemoryService workingMemory = mock(SessionWorkingMemoryService.class);
        AtomicReference<WorkingMemoryCompactionEvent> audited = new AtomicReference<>();
        List<Message> messages = List.of(Message.userMessage("short request", null));
        RecordingService service = new RecordingService(
                audited, messages, null, workingMemory, true, false, 5);
        PromptShape shape = PromptShape.functionCall(
                Message.systemMessage("S".repeat(8_000), null), messages, null);
        int requestTokens = ContextTokenTracker.estimateCurrent(null, shape, new TokenCounter())
                .getEstimatedTokens();
        Assert.assertTrue(requestTokens > new WorkingMemoryCompactor().estimateTokens(messages));

        service.applyIfNeededMidRun("s-unchanged", "main", "req-unchanged", messages,
                null, shape, null);

        verify(workingMemory, never()).replaceReadyProjection(anyString(), anyString(), anyString(), any());
    }

    @Test
    public void microcompactPersistsWhenItClearsToolContentWithoutReducingMessageCount() {
        SessionWorkingMemoryService workingMemory = mock(SessionWorkingMemoryService.class);
        AtomicReference<WorkingMemoryCompactionEvent> audited = new AtomicReference<>();
        List<Message> messages = List.of(
                Message.toolMessage("legacy-output ".repeat(900), "call-old", null),
                Message.toolMessage("recent result", "call-recent", null));
        RecordingService service = new RecordingService(
                audited, null, null, workingMemory, true, true, 1);
        List<Message> expected = List.of(
                Message.toolMessage(CompactionBudget.CLEARED_TOOL_RESULT, "call-old", null),
                messages.get(1));
        WorkingMemoryCompactor compactor = new WorkingMemoryCompactor();
        Assert.assertTrue(compactor.estimateTokens(expected) < compactor.estimateTokens(messages));

        List<Message> out = service.applyIfNeeded("s-micro-persist", "main", "req-micro", messages);

        Assert.assertEquals(messages.size(), out.size());
        verify(workingMemory, times(1)).replaceReadyProjection(
                eq("s-micro-persist"), eq("main"), anyString(), eq(expected));
    }

    @Test
    public void fullCompactionPersistsWhenItShortensTheMessageList() {
        SessionWorkingMemoryService workingMemory = mock(SessionWorkingMemoryService.class);
        AtomicReference<WorkingMemoryCompactionEvent> audited = new AtomicReference<>();
        List<Message> compactResult = List.of(
                Message.userMessage("compacted checkpoint", null),
                Message.assistantMessage("latest reply", null));
        RecordingService service = new RecordingService(
                audited, compactResult, null, workingMemory, true, false, 5);

        service.applyIfNeeded("s-full-persist", "main", "req-full", oversizedMessages());

        verify(workingMemory, times(1)).replaceReadyProjection(
                eq("s-full-persist"), eq("main"), anyString(), eq(compactResult));
    }

    @Test
    public void oversizedAuditPayloadRemainsValidJson() {
        AtomicReference<WorkingMemoryCompactionEvent> audited = new AtomicReference<>();
        String handoff = CompactionPrompt.wrapSummaryForReinject(
                """
                        ## Goal
                        keep the audit valid
                        ## Constraints & Preferences
                        do not store a broken JSON prefix
                        ## Active State
                        verify the persisted event
                        """,
                false);
        RecordingService service = new RecordingService(
                audited,
                List.of(Message.userMessage(handoff, null)),
                null,
                null,
                false,
                false,
                5,
                true);

        service.applyIfNeeded("s-audit", "main", "req-audit", hugeAuditMessages());

        WorkingMemoryCompactionEvent event = audited.get();
        Assert.assertNotNull(event);
        Assert.assertNotNull(event.getBeforeMessagesJson());
        Assert.assertTrue(
                event.getBeforeMessagesJson().length()
                        <= WorkingMemoryCompactionEvent.MAX_PAYLOAD_CHARS);
        com.alibaba.fastjson.JSONObject marker =
                JSON.parseObject(event.getBeforeMessagesJson());
        Assert.assertTrue(marker.getBooleanValue("truncated"));
        Assert.assertEquals("audit_payload_limit", marker.getString("reason"));
        Assert.assertTrue(marker.getString("sha256").startsWith("sha256:"));
    }

    private static List<Message> oversizedMessages() {
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            messages.add(Message.userMessage("old-user-" + i + " " + "x".repeat(400), null));
            messages.add(Message.assistantMessage("old-assistant-" + i + " " + "y".repeat(400), null));
        }
        messages.add(Message.userMessage("latest-user", null));
        messages.add(Message.assistantMessage("latest-assistant", null));
        return messages;
    }

    private static List<Message> hugeAuditMessages() {
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            messages.add(Message.userMessage(
                    "audit-row-" + i + " " + "x".repeat(1_000), null));
        }
        return messages;
    }

    private static final class RecordingService extends SessionContextCompactionServiceImpl {
        private final AtomicReference<WorkingMemoryCompactionEvent> audited;
        private List<Message> compactResult;
        private List<Message> fallbackResult;
        private Exception failWith;
        private boolean forceCircuit;
        private final boolean microEnabled;
        private final int microKeepRecentToolResults;
        private final boolean auditStoreMessages;

        private RecordingService(AtomicReference<WorkingMemoryCompactionEvent> audited,
                                 List<Message> compactResult,
                                 Exception failWith) {
            this(audited, compactResult, failWith, null, false, false, 5);
        }

        private RecordingService(AtomicReference<WorkingMemoryCompactionEvent> audited,
                                 List<Message> compactResult,
                                 Exception failWith,
                                 SessionWorkingMemoryService workingMemory,
                                 boolean persistProjection,
                                 boolean microEnabled,
                                 int microKeepRecentToolResults) {
            this(
                    audited,
                    compactResult,
                    failWith,
                    workingMemory,
                    persistProjection,
                    microEnabled,
                    microKeepRecentToolResults,
                    false);
        }

        private RecordingService(AtomicReference<WorkingMemoryCompactionEvent> audited,
                                 List<Message> compactResult,
                                 Exception failWith,
                                 SessionWorkingMemoryService workingMemory,
                                 boolean persistProjection,
                                 boolean microEnabled,
                                 int microKeepRecentToolResults,
                                 boolean auditStoreMessages) {
            super(nullProvider(), workingMemory, recordingDao(audited),
                    CompactionBudget.defaults().toBuilder()
                            .microEnabled(microEnabled)
                            .keepMinTokens(20)
                            .keepMinTextMessages(1)
                            .keepMaxTokens(200)
                            .microKeepRecentToolResults(microKeepRecentToolResults)
                            .persistProjection(persistProjection)
                            .auditStoreMessages(auditStoreMessages)
                            .build(),
                    null);
            this.audited = audited;
            this.compactResult = compactResult;
            this.failWith = failWith;
            this.microEnabled = microEnabled;
            this.microKeepRecentToolResults = microKeepRecentToolResults;
            this.auditStoreMessages = auditStoreMessages;
        }

        @Override
        protected List<Message> localFallback(List<Message> messages, CompactionBudget budget) {
            if (fallbackResult != null) {
                return fallbackResult;
            }
            return super.localFallback(messages, budget);
        }

        @Override
        protected CompactionBudget resolveBudget() {
            return CompactionBudget.builder()
                    .enabled(true)
                    .llmEnabled(true)
                    .microEnabled(microEnabled)
                    .sessionMemoryEnabled(false)
                    .contextWindow(2_000)
                    .thresholdPercent(0.50d)
                    .keepMinTokens(40)
                    .keepMinTextMessages(1)
                    .keepMaxTokens(400)
                    .maxConsecutiveFailures(3)
                    .temperature(0.2d)
                    .messageContentCharLimit(4000)
                    .summaryTargetRatio(0.20d)
                    .protectFirstN(2)
                    .protectLastN(4)
                    .contentMaxChars(6000)
                    .contentHeadChars(4000)
                    .contentTailChars(1500)
                    .summaryInputMaxChars(160000)
                    .summarizerTimeoutSeconds(120)
                    .microKeepRecentToolResults(microKeepRecentToolResults)
                    .microToolResultMaxChars(8000)
                    .build();
        }

        @Override
        protected List<Message> fullCompact(String sessionId, String requestId, List<Message> messages, CompactionBudget budget)
                throws Exception {
            if (forceCircuit) {
                throw new IllegalStateException("should not call llm when circuit open");
            }
            if (failWith != null) {
                throw failWith;
            }
            return compactResult;
        }

        static ObjectProvider<CuteRuntimeDependencies> nullProvider() {
            return new ObjectProvider<>() {
                @Override
                public CuteRuntimeDependencies getObject() {
                    throw new IllegalStateException("runtime deps unavailable in unit test");
                }

                @Override
                public CuteRuntimeDependencies getObject(Object... args) {
                    return getObject();
                }

                @Override
                public CuteRuntimeDependencies getIfAvailable() {
                    return null;
                }

                @Override
                public CuteRuntimeDependencies getIfUnique() {
                    return null;
                }
            };
        }

        private static IWorkingMemoryCompactionDao recordingDao(AtomicReference<WorkingMemoryCompactionEvent> audited) {
            return new IWorkingMemoryCompactionDao() {
                @Override
                public int insertEvent(WorkingMemoryCompactionEvent event) {
                    audited.set(event);
                    return 1;
                }
            };
        }
    }
}
