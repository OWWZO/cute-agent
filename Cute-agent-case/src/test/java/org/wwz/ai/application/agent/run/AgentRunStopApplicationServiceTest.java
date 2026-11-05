package org.wwz.ai.application.agent.run;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.adapter.port.AgentMessageStream;
import org.wwz.ai.domain.agent.ledger.AgentExecutionRecorder;
import org.wwz.ai.domain.agent.ledger.model.ArtifactRecordCommand;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunFinishRecord;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunStartRecord;
import org.wwz.ai.domain.agent.ledger.model.LlmInvocationFinishRecord;
import org.wwz.ai.domain.agent.ledger.model.LlmInvocationStartRecord;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationBatchStartRecord;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationFinishRecord;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.cancel.ActiveAgentRunRegistry;
import org.wwz.ai.domain.agent.runtime.tasklist.StreamSettlePolicy;

import java.util.List;
import java.util.Map;

public class AgentRunStopApplicationServiceTest {

    @Test
    public void ledgerFailureMustNotSkipStreamCompletion() {
        boolean previousDefer = StreamSettlePolicy.deferBackgroundSettle();
        StreamSettlePolicy.setDeferBackgroundSettle(false);
        AgentRunLaunchGate launchGate = new AgentRunLaunchGate(0);
        try {
            ActiveAgentRunRegistry registry = new ActiveAgentRunRegistry();
            ActiveAgentRunRegistry.ActiveRun run = registry.begin(
                    "req-stop-ledger", "sess-stop-ledger", "user-1");
            AgentContext context = AgentContext.builder()
                    .requestId("req-stop-ledger")
                    .sessionId("sess-stop-ledger")
                    .userId("user-1")
                    .executionRecorder(new ThrowingRecorder())
                    .build();
            context.activateLedgerRun(9L, "req-stop-ledger");
            run.setAgentContext(context);
            RecordingStream stream = new RecordingStream();
            run.setStream(stream);

            Map<String, Object> result = new AgentRunStopApplicationService(registry, launchGate)
                    .stop("sess-stop-ledger", "req-stop-ledger");

            Assert.assertEquals(Boolean.TRUE, result.get("stopped"));
            Assert.assertTrue("ledger 写失败仍必须关闭 run 的输出流", stream.completed);
            Assert.assertTrue("run 的内存终态仍应收口", context.control().isTurnClosed());
        } finally {
            launchGate.shutdown();
            StreamSettlePolicy.setDeferBackgroundSettle(previousDefer);
        }
    }

    private static final class RecordingStream implements AgentMessageStream {
        private boolean completed;

        @Override
        public void send(Object payload) {
        }

        @Override
        public void complete() {
            completed = true;
        }

        @Override
        public void completeWithError(Throwable throwable) {
            completed = true;
        }

        @Override
        public void onAbort(Runnable abortHandler) {
        }

        @Override
        public boolean isAborted() {
            return false;
        }
    }

    private static final class ThrowingRecorder implements AgentExecutionRecorder {
        @Override
        public Long createRun(DialogueRunStartRecord record) {
            return null;
        }

        @Override
        public void finishRun(DialogueRunFinishRecord record) {
            throw new IllegalStateException("ledger unavailable");
        }

        @Override
        public Long createLlmInvocation(LlmInvocationStartRecord record) {
            return null;
        }

        @Override
        public void finishLlmInvocation(LlmInvocationFinishRecord record) {
        }

        @Override
        public Map<String, Long> createToolInvocations(ToolInvocationBatchStartRecord record) {
            return Map.of();
        }

        @Override
        public void finishToolInvocation(ToolInvocationFinishRecord record) {
        }

        @Override
        public void recordArtifacts(List<ArtifactRecordCommand> records) {
        }

        @Override
        public void recordArtifactsOrThrow(List<ArtifactRecordCommand> records) {
        }
    }
}
