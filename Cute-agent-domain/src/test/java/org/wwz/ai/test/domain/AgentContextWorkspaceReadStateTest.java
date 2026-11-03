package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.tool.workspace.WorkspaceFileReadState;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * workspace_read 去重表的懒初始化必须原子，避免并发首次写入互相覆盖。
 */
public class AgentContextWorkspaceReadStateTest {

    @Test
    public void concurrentFirstWritesKeepEveryEntry() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < 100; round++) {
                AgentContext context = AgentContext.builder()
                        .workspaceReadStateByPath(null)
                        .build();
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> futures = new ArrayList<>();
                for (int index = 0; index < threads; index++) {
                    int fileIndex = index;
                    futures.add(pool.submit(() -> {
                        start.await();
                        context.markWorkspaceFileRead(WorkspaceFileReadState.builder()
                                .absolutePath("/tmp/workspace-file-" + fileIndex)
                                .build());
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> future : futures) {
                    future.get(10, TimeUnit.SECONDS);
                }

                Assert.assertEquals(threads, context.snapshotWorkspaceReadState().size());
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
