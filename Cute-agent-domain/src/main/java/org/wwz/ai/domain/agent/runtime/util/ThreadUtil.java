package org.wwz.ai.domain.agent.runtime.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;

import java.util.concurrent.*;

/**
 * 延期保留的线程工具类。
 * <p>主链路优先使用 AgentExecutorSupport 的受控执行器，本类仅服务历史调用方。</p>
 */
@Slf4j
public class ThreadUtil {
    private static final int MAX_QUEUE_SIZE = 1000;
    /** 并发 execute() 需看到同一个已发布 executor；volatile 保证安全发布。 */
    private static volatile ThreadPoolExecutor executor = null;

    private ThreadUtil() {
    }

    public static synchronized void initPool(int poolSize) {
        int actualPoolSize = Math.max(poolSize, 1);
        ThreadPoolExecutor current = executor;
        if (current == null) {
            ThreadFactory threadFactory = (new BasicThreadFactory.Builder()).namingPattern("exe-pool-%d").daemon(true).build();
            executor = new ThreadPoolExecutor(actualPoolSize, actualPoolSize, 60000L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(MAX_QUEUE_SIZE), threadFactory, new RejectAndLogHandler());
            return;
        }
        // 重复 initPool 必须生效，否则后调用方的 poolSize 被静默忽略。
        // 调整顺序遵守 ThreadPoolExecutor 的约束：扩容先抬 max，缩容先降 core。
        if (current.getCorePoolSize() == actualPoolSize
                && current.getMaximumPoolSize() == actualPoolSize) {
            return;
        }
        if (actualPoolSize > current.getMaximumPoolSize()) {
            current.setMaximumPoolSize(actualPoolSize);
            current.setCorePoolSize(actualPoolSize);
        } else {
            current.setCorePoolSize(actualPoolSize);
            current.setMaximumPoolSize(actualPoolSize);
        }
    }

    public static void execute(Runnable runnable) {
        ThreadPoolExecutor current = executor;
        if (current == null) {
            initPool(100);
            current = executor;
        }
        current.execute(runnable);
    }

    public static CountDownLatch getCountDownLatch(int count) {
        return new CountDownLatch(count);
    }

    public static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            // 恢复中断标记，让调用方/上层调度器知道等待被取消。
            Thread.currentThread().interrupt();
        }
    }

    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 有界队列饱和时显式拒绝并留下日志，而不是在提交线程上偷偷执行任务
     * （CallerRunsPolicy 会拖住请求线程甚至造成近似死锁）。
     */
    private static final class RejectAndLogHandler implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            log.warn("exe-pool saturated, rejecting task: poolSize={}, active={}, queue={}",
                    executor.getPoolSize(), executor.getActiveCount(), executor.getQueue().size());
            throw new RejectedExecutionException("exe-pool saturated");
        }
    }
}
