package org.wwz.ai.test.domain.subagent;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentDefinition;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 配置层整体替换回归：读者不能观察到 clear + putAll 之间的空表/半表。
 */
public class SubAgentRegistrySnapshotTest {

    private static final int CONFIGURED_SIZE = 200;

    @Test
    public void readersNeverObservePartialConfiguredLayer() throws Exception {
        SubAgentRegistry registry = new SubAgentRegistry();
        List<SubAgentDefinition> first = definitions("a");
        List<SubAgentDefinition> second = definitions("b");
        // 先发布一代完整配置，读者循环开始后才有稳定的非空基线。
        registry.replaceConfigured(first);
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread writer = new Thread(() -> {
            try {
                for (int i = 0; i < 3_000; i++) {
                    registry.replaceConfigured(i % 2 == 0 ? first : second);
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                stop.set(true);
            }
        });
        List<Thread> readers = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Thread reader = new Thread(() -> {
                while (!stop.get()) {
                    int size = registry.listTypeNames().size();
                    if (size != 1 + CONFIGURED_SIZE) {
                        failure.compareAndSet(null,
                                new AssertionError("观察到部分配置层: size=" + size));
                        return;
                    }
                }
            });
            readers.add(reader);
        }

        writer.start();
        readers.forEach(Thread::start);
        writer.join(10_000);
        for (Thread reader : readers) {
            reader.join(10_000);
        }

        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        Assert.assertEquals(CONFIGURED_SIZE + 1, registry.listTypeNames().size());
    }

    private static List<SubAgentDefinition> definitions(String prefix) {
        List<SubAgentDefinition> defs = new ArrayList<>(CONFIGURED_SIZE);
        for (int i = 0; i < CONFIGURED_SIZE; i++) {
            defs.add(SubAgentDefinition.builder()
                    .agentType(prefix + "-" + i)
                    .allowedTools(Set.of("*"))
                    .disallowedTools(Set.of())
                    .deferredTools(Set.of())
                    .maxSteps(10)
                    .build());
        }
        return defs;
    }
}
