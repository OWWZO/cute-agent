package org.wwz.ai.test.domain.cancel;

import org.junit.Assert;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.wwz.ai.domain.agent.runtime.cancel.ActiveAgentRunRegistry;
import org.wwz.ai.domain.agent.runtime.planmode.IPlanApprovalRepository;
import org.wwz.ai.domain.agent.runtime.planmode.PendingPlanApprovalRegistry;
import org.wwz.ai.types.agent.exception.AgentConcurrentRunException;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 活跃 run 索引的并发不变量与取消清理路径。
 */
public class ActiveAgentRunRegistryTest {

    @Test
    public void sameRequestIdReturnsSameActiveRun() {
        ActiveAgentRunRegistry registry = new ActiveAgentRunRegistry();

        ActiveAgentRunRegistry.ActiveRun first = registry.begin("r1", "s1", "u1");
        ActiveAgentRunRegistry.ActiveRun second = registry.begin("r1", "s1", "u1");

        Assert.assertSame(first, second);
        Assert.assertSame(first, registry.findBySessionId("s1").orElseThrow());
    }

    @Test
    public void concurrentBeginForSameSessionKeepsOnlyOneActiveRun() throws Exception {
        for (int round = 0; round < 50; round++) {
            ActiveAgentRunRegistry registry = new ActiveAgentRunRegistry();
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger rejected = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Callable<Void> task = () -> {
                    start.await();
                    try {
                        registry.begin("r-" + Thread.currentThread().getId(), "same-session", "u1");
                    } catch (AgentConcurrentRunException expected) {
                        rejected.incrementAndGet();
                    }
                    return null;
                };
                Future<?> one = pool.submit(task);
                Future<?> two = pool.submit(task);
                start.countDown();
                one.get();
                two.get();
            } finally {
                pool.shutdownNow();
            }

            Assert.assertEquals("恰好一个请求应被拒绝", 1, rejected.get());
            Assert.assertTrue(registry.findBySessionId("same-session").isPresent());
        }
    }

    @Test
    public void cancelRunsBothPersistedAndInMemoryCleanupPaths() {
        ActiveAgentRunRegistry registry = new ActiveAgentRunRegistry();
        IPlanApprovalRepository repository = mock(IPlanApprovalRepository.class);
        PendingPlanApprovalRegistry pending = mock(PendingPlanApprovalRegistry.class);
        ReflectionTestUtils.setField(registry, "planApprovalRepository", repository);
        ReflectionTestUtils.setField(registry, "pendingPlanApprovalRegistry", pending);
        registry.begin("r1", "s1", "u1");

        Assert.assertTrue(registry.cancel("r1", "user_stop"));

        verify(repository).cancelBySourceRequestId("r1", "user_stop");
        verify(pending).cancelByRequestId("r1", "user_stop");
    }

    @Test
    public void repositoryFailureDoesNotBlockPendingCleanup() {
        ActiveAgentRunRegistry registry = new ActiveAgentRunRegistry();
        IPlanApprovalRepository repository = mock(IPlanApprovalRepository.class);
        PendingPlanApprovalRegistry pending = mock(PendingPlanApprovalRegistry.class);
        doThrow(new IllegalStateException("db down"))
                .when(repository).cancelBySourceRequestId(anyString(), anyString());
        ReflectionTestUtils.setField(registry, "planApprovalRepository", repository);
        ReflectionTestUtils.setField(registry, "pendingPlanApprovalRegistry", pending);
        registry.begin("r1", "s1", "u1");

        Assert.assertTrue(registry.cancel("r1", "user_stop"));

        verify(pending).cancelByRequestId("r1", "user_stop");
    }
}
