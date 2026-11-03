package org.wwz.ai.domain.agent.runtime.cancel;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单轮 Agent run 的协作式取消标志（用户主动点击停止）。
 * 在途模型请求或阻塞调用通过 {@link #registerAbort(Runnable)} 挂上中止钩子，cancel 时立刻执行。
 */
public class RunCancellation {

    public static final String REASON_USER_STOP = "user_stop";

    /** 取消标记与元数据作为一个不可变整体发布，读者不会看到「已取消但原因为空」。 */
    private record State(boolean cancelled, String reason, long cancelledAtMs) {
    }

    private static final State ACTIVE = new State(false, null, 0L);

    private final AtomicReference<State> state = new AtomicReference<>(ACTIVE);
    private final ConcurrentLinkedQueue<Runnable> abortHooks = new ConcurrentLinkedQueue<>();

    public boolean cancel(String reason) {
        State cancelledState = new State(true, reason == null ? REASON_USER_STOP : reason, System.currentTimeMillis());
        if (!state.compareAndSet(ACTIVE, cancelledState)) {
            return false;
        }
        Runnable hook;
        while ((hook = abortHooks.poll()) != null) {
            runAbort(hook);
        }
        return true;
    }

    /**
     * 注册在途工作的中止回调。已取消时立刻执行。
     * 返回值用于正常结束后注销，避免下一次 cancel 误伤已完成的请求。
     * 钩子必须可重复执行（dispose / future.cancel）。
     * <p><b>钩子必须非阻塞</b>：{@link #cancel(String)} 在调用线程内依次同步执行所有钩子，
     * 一个等待网络或锁的钩子会拖住后续钩子以及 /stop 请求线程本身。
     * 需要优雅关闭的重活应自行投递到后台线程，钩子内只做取消/关闭信号。</p>
     */
    public Runnable registerAbort(Runnable hook) {
        if (hook == null) {
            return () -> {
            };
        }
        if (isCancelled()) {
            runAbort(hook);
            return () -> {
            };
        }
        abortHooks.add(hook);
        if (isCancelled() && abortHooks.remove(hook)) {
            runAbort(hook);
        }
        return () -> abortHooks.remove(hook);
    }

    private static void runAbort(Runnable hook) {
        try {
            hook.run();
        } catch (RuntimeException ignored) {
            // 单个在途请求中止失败不能挡住其余钩子。
        }
    }

    public boolean isCancelled() {
        return state.get().cancelled();
    }

    public String getReason() {
        return state.get().reason();
    }

    public long getCancelledAtMs() {
        return state.get().cancelledAtMs();
    }
}
