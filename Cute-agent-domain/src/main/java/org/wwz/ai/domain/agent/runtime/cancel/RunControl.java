package org.wwz.ai.domain.agent.runtime.cancel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单轮 Agent run 的控制面：取消令牌链、协作式关闭与注入队列。
 *
 * <p><b>不变量：</b>本类是 run 级独占，父 Agent 与子 Agent 各持一份（会话级共享状态在
 * {@link SessionControl}）。取消语义由令牌链表达：父（用户 stop）令牌经
 * {@code parentCancellation} 只读透传到子，子 Agent 可覆盖 {@code ownCancellation}
 * 换成任务级令牌而不影响父 —— {@code /stop} 杀得到嵌套子 Agent，{@code TaskStop}
 * 不误伤父 run，两个令牌互不覆盖。</p>
 *
 * <p><b>惰性初始化：</b>构造时所有字段都可以为 null；注入队列的读取方必须走
 * {@code requirePendingInjects}。</p>
 */
public final class RunControl {

    /**
     * 子 Agent 可覆盖的任务级取消令牌。后台任务用 TaskStop 的令牌覆盖自己，
     * 不顶掉父的用户 stop 令牌。
     */
    private volatile RunCancellation ownCancellation;

    /** 父 run 透传的取消令牌（只读引用）：父侧 /stop 置位后子 Agent 立即可见。 */
    private volatile RunCancellation parentCancellation;

    /** 父 run 是否已 finishRun（SUCCESS/FAILED/STOPPED/WAITING_INPUT）。 */
    private final AtomicBoolean turnClosed = new AtomicBoolean(false);

    /** 运行中用户/协调注入队列（与 ActiveRun 共享同一实例）。 */
    private volatile ConcurrentLinkedQueue<PendingInjectMessage> pendingInjects;

    public RunControl() {
    }

    // ---- 取消（令牌链）----

    public boolean isCancelled() {
        return isOwnCancelled() || isParentCancelled();
    }

    public String getCancelReason() {
        if (isOwnCancelled()) {
            return ownCancellation.getReason();
        }
        if (isParentCancelled()) {
            return parentCancellation.getReason();
        }
        return null;
    }

    /**
     * 把在途工作挂到取消令牌链上：own 与 parent 两个令牌都注册同一钩子，
     * 任一侧 cancel 都能中止在途工作。返回的注销句柄同时从两者移除。
     */
    public Runnable registerAbort(Runnable hook) {
        Runnable removeFromOwn = ownCancellation == null ? () -> { } : ownCancellation.registerAbort(hook);
        Runnable removeFromParent = parentCancellation == null ? () -> { } : parentCancellation.registerAbort(hook);
        return () -> {
            removeFromOwn.run();
            removeFromParent.run();
        };
    }

    /** 覆盖自己的任务级取消令牌；子 Agent 的 TaskStop 令牌走这里，不影响父。 */
    public void bindCancellation(RunCancellation cancellation) {
        if (cancellation != null) {
            this.ownCancellation = cancellation;
        }
    }

    /** 注入父 run 透传的用户 stop 令牌（只读引用，不覆盖 own）。 */
    public void bindParentCancellation(RunCancellation cancellation) {
        if (cancellation != null) {
            this.parentCancellation = cancellation;
        }
    }

    /** 流式写法，便于 {@code .control(new RunControl().withCancellation(...))}（覆盖 own）。 */
    public RunControl withCancellation(RunCancellation cancellation) {
        bindCancellation(cancellation);
        return this;
    }

    /** 流式写法，便于 {@code .control(new RunControl().withParentCancellation(...))}。 */
    public RunControl withParentCancellation(RunCancellation cancellation) {
        bindParentCancellation(cancellation);
        return this;
    }

    /** own 优先：后台任务覆盖令牌后，取消原因以任务级为准。 */
    public RunCancellation getCancellation() {
        return ownCancellation != null ? ownCancellation : parentCancellation;
    }

    private boolean isOwnCancelled() {
        RunCancellation cancellation = this.ownCancellation;
        return cancellation != null && cancellation.isCancelled();
    }

    private boolean isParentCancelled() {
        RunCancellation cancellation = this.parentCancellation;
        return cancellation != null && cancellation.isCancelled();
    }

    // ---- 协作式关闭 ----

    public void markTurnClosed() {
        turnClosed.set(true);
    }

    public boolean isTurnClosed() {
        return turnClosed.get();
    }

    // ---- 注入队列 ----

    /**
     * 绑定与 ActiveRun 共享的队列。null 被忽略，避免把已工作的队列打空。
     */
    public void bindPendingInjectQueue(ConcurrentLinkedQueue<PendingInjectMessage> queue) {
        if (queue != null) {
            this.pendingInjects = queue;
        }
    }

    public ConcurrentLinkedQueue<PendingInjectMessage> requirePendingInjects() {
        ConcurrentLinkedQueue<PendingInjectMessage> queue = this.pendingInjects;
        if (queue == null) {
            synchronized (this) {
                queue = this.pendingInjects;
                if (queue == null) {
                    queue = new ConcurrentLinkedQueue<>();
                    this.pendingInjects = queue;
                }
            }
        }
        return queue;
    }

    public void offerInject(PendingInjectMessage message) {
        if (message == null || message.getText() == null || message.getText().isBlank()) {
            return;
        }
        requirePendingInjects().offer(message);
    }

    /** 取出并清空待注入消息（步进边界调用）。 */
    public List<PendingInjectMessage> drainPendingInjects() {
        ConcurrentLinkedQueue<PendingInjectMessage> queue = requirePendingInjects();
        List<PendingInjectMessage> drained = new ArrayList<>();
        PendingInjectMessage next;
        while ((next = queue.poll()) != null) {
            drained.add(next);
        }
        return drained;
    }
}
