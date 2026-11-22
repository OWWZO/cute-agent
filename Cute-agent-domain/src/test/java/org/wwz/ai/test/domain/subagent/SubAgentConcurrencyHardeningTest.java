package org.wwz.ai.test.domain.subagent;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentExecutionScope;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentConcurrencyGate;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentRunner;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentRegistry;
import org.wwz.ai.domain.agent.runtime.tool.ToolCollection;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 子 Agent 并发闸门单元测试（不依赖 slf4j 初始化的运行时类）。
 */
public class SubAgentConcurrencyHardeningTest {

    @Test
    public void shouldCapConcurrentPermits() throws Exception {
        SubAgentConcurrencyGate gate = new SubAgentConcurrencyGate(2, 2L);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        gate.runWithPermit(() -> {
                            int now = inFlight.incrementAndGet();
                            maxInFlight.accumulateAndGet(now, Math::max);
                            entered.countDown();
                            try {
                                Assert.assertTrue(release.await(3, TimeUnit.SECONDS));
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } finally {
                                inFlight.decrementAndGet();
                            }
                            return "ok";
                        });
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }

            Assert.assertTrue(entered.await(2, TimeUnit.SECONDS));
            Assert.assertEquals(2, maxInFlight.get());
            release.countDown();
            for (Future<?> future : futures) {
                future.get(3, TimeUnit.SECONDS);
            }
            Assert.assertEquals(2, maxInFlight.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void shouldFailFastWhenNoPermitWithinTimeout() throws Exception {
        SubAgentConcurrencyGate gate = new SubAgentConcurrencyGate(1, 0L);
        CountDownLatch hold = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = pool.submit(() -> {
                try {
                    gate.runWithPermit(() -> {
                        try {
                            Assert.assertTrue(hold.await(3, TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return "hold";
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            Thread.sleep(50L);
            Object denied = gate.runWithPermit(() -> "should-not-run");
            Assert.assertNull(denied);
            hold.countDown();
            holder.get(2, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void nestedCallsOnSameThreadReuseOuterPermit() throws Exception {
        SubAgentConcurrencyGate gate = new SubAgentConcurrencyGate(1, 0L);

        String result = gate.runWithPermit(() -> {
            try {
                return gate.runWithPermit(() -> {
                    try {
                        return gate.runWithPermit(() -> "nested-ok");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return "interrupted";
                    }
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "interrupted";
            }
        });

        Assert.assertEquals("nested-ok", result);
        Assert.assertEquals("最外层退出后许可必须归还", 1, gate.availablePermits());
    }

    @Test
    public void callerRunsPathDoesNotAcquireSecondSubAgentPermit() throws Exception {
        SubAgentConcurrencyGate gate = Mockito.mock(SubAgentConcurrencyGate.class);
        SubAgentRunner runner = new SubAgentRunner(new SubAgentRegistry(), gate);
        AgentContext parent = AgentContext.builder()
                .requestId("req-inline")
                .sessionId("sess-inline")
                .query("q")
                .toolCollection(new ToolCollection())
                .build();

        SubAgentExecutionScope.runInline(() -> runner.run(
                parent, "desc", "prompt", SubAgentRegistry.TYPE_GENERAL_PURPOSE));

        Mockito.verify(gate, Mockito.never()).runWithPermit(Mockito.any());
    }
}
