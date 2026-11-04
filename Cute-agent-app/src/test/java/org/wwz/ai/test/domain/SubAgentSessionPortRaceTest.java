package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.adapter.port.SubAgentSessionIdentity;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerWriteRepository;
import org.wwz.ai.domain.agent.ledger.entity.DialogueSession;
import org.wwz.ai.domain.agent.ledger.model.DialogueSessionUpsertRecord;
import org.wwz.ai.infrastructure.adapter.port.SubAgentSessionPortAdapter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 子会话建行必须是原子 upsert：两个并发派发不能都走「查询不存在 -> 插入」。
 */
public class SubAgentSessionPortRaceTest {

    @Test
    public void concurrentEnsureChildSessionCreatesExactlyOneRow() throws Exception {
        ConcurrentDialogueSessionRepository repository = new ConcurrentDialogueSessionRepository();
        SubAgentSessionPortAdapter adapter = new SubAgentSessionPortAdapter(repository);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    adapter.ensureChildSession(SubAgentSessionIdentity.builder()
                            .childSessionId("sub-race")
                            .parentSessionId("parent-1")
                            .userId("user-1")
                            .title("并发子任务")
                            .subAgentType("general-purpose")
                            .build());
                    return null;
                });
            }
            Assert.assertTrue("并发线程未就绪", ready.await(5, TimeUnit.SECONDS));
            start.countDown();
        } finally {
            pool.shutdown();
            Assert.assertTrue("并发任务未结束", pool.awaitTermination(5, TimeUnit.SECONDS));
        }

        Assert.assertEquals(1, repository.insertCount.get());
        Assert.assertEquals(1, repository.rows.size());
    }

    private static final class ConcurrentDialogueSessionRepository implements IExecutionLedgerWriteRepository {
        private final Map<String, DialogueSession> rows = new ConcurrentHashMap<>();
        private final AtomicInteger insertCount = new AtomicInteger();

        @Override
        public void insertRun(org.wwz.ai.domain.agent.ledger.entity.DialogueRun run) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DialogueSession querySessionBySessionId(String sessionId) {
            return rows.get(sessionId);
        }

        @Override
        public org.wwz.ai.domain.agent.ledger.entity.DialogueRun queryRunByRequestId(String requestId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<org.wwz.ai.domain.agent.ledger.entity.LlmInvocation> queryLlmInvocationsByRunId(Long runId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<org.wwz.ai.domain.agent.ledger.entity.ToolInvocation> queryToolInvocationsByRunId(Long runId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<org.wwz.ai.domain.agent.ledger.entity.ArtifactRecord> queryArtifactsByRunId(Long runId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int updateRunFinish(org.wwz.ai.domain.agent.ledger.entity.DialogueRun run) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void upsertSession(DialogueSessionUpsertRecord record) {
            insertCount.incrementAndGet();
            rows.put(record.getSessionId(), DialogueSession.builder()
                    .sessionId(record.getSessionId())
                    .userId(record.getUserId())
                    .sessionKind(record.getSessionKind())
                    .parentSessionId(record.getParentSessionId())
                    .rootSessionId(record.getRootSessionId())
                    .subAgentType(record.getSubAgentType())
                    .title(record.getTitle())
                    .runCount(record.getRunCount())
                    .build());
        }

        @Override
        public void insertSessionIfAbsent(DialogueSessionUpsertRecord record) {
            DialogueSession created = DialogueSession.builder()
                    .sessionId(record.getSessionId())
                    .userId(record.getUserId())
                    .sessionKind(record.getSessionKind())
                    .parentSessionId(record.getParentSessionId())
                    .rootSessionId(record.getRootSessionId())
                    .subAgentType(record.getSubAgentType())
                    .title(record.getTitle())
                    .runCount(record.getRunCount())
                    .build();
            if (rows.putIfAbsent(record.getSessionId(), created) == null) {
                insertCount.incrementAndGet();
            }
        }

        @Override
        public void updateSessionPreferences(String sessionId, String model, Boolean thinking,
                                             String thinkingEffort, Integer deepThink) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void upsertSessionOnRunStart(DialogueSessionUpsertRecord record) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int updateSessionRunFinish(String sessionId, int status, String latestSummaryText,
                                          int finishedDelta, int failedDelta,
                                          java.time.LocalDateTime lastActiveAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<org.wwz.ai.domain.agent.ledger.model.DialogueRunView> queryRunsBySessionId(String sessionId,
                                                                                                int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void insertLlmInvocation(org.wwz.ai.domain.agent.ledger.entity.LlmInvocation invocation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateLlmInvocationFinish(org.wwz.ai.domain.agent.ledger.entity.LlmInvocation invocation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void insertToolInvocation(org.wwz.ai.domain.agent.ledger.entity.ToolInvocation invocation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int batchInsertToolInvocations(
                List<org.wwz.ai.domain.agent.ledger.entity.ToolInvocation> invocations) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int updateToolInvocationFinish(
                org.wwz.ai.domain.agent.ledger.entity.ToolInvocation invocation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int batchInsertArtifacts(
                List<org.wwz.ai.domain.agent.ledger.entity.ArtifactRecord> records) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void bumpSessionEventSeq(String sessionId, long eventSeq) {
            throw new UnsupportedOperationException();
        }
    }
}
