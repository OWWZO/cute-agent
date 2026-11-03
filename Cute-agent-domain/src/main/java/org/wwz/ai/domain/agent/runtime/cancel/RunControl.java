package org.wwz.ai.domain.agent.runtime.cancel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
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

    /**
     * 父 run 透传的取消令牌链（只读引用）：可能包含父自己的任务级令牌与更上层的用户 stop 令牌。
     * <p>必须保留整条链，单字段会在 {@link #bindParentCancellation} 多次调用时互相覆盖，
     * 导致最上层用户 stop 杀不到孙 Agent。</p>
     */
    private final List<RunCancellation> parentCancellations = new CopyOnWriteArrayList<>();

    /** 父 run 是否已 finishRun（SUCCESS/FAILED/STOPPED/WAITING_INPUT）。 */
    private final AtomicBoolean turnClosed = new AtomicBoolean(false);

    /** 运行中用户/协调注入队列（与 ActiveRun 共享同一实例）。 */
    private volatile ConcurrentLinkedQueue<PendingInjectMessage> pendingInjects;

    /** 本轮已注入的后台任务通知 taskId；run 成功收口后据此 markNotified。 */
    private final Set<String> consumedBackgroundNotificationTaskIds = ConcurrentHashMap.newKeySet();

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
        for (RunCancellation cancellation : parentCancellations) {
            if (cancellation.isCancelled()) {
                return cancellation.getReason();
            }
        }
        return null;
    }

    /**
     * 把在途工作挂到取消令牌链上：own 与 parent 两个令牌都注册同一钩子，
     * 任一侧 cancel 都能中止在途工作。返回的注销句柄同时从两者移除。
     */
    public Runnable registerAbort(Runnable hook) {
        RunCancellation own = ownCancellation;
        Runnable removeFromOwn = own == null ? () -> { } : own.registerAbort(hook);
        List<Runnable> parentRemovers = new ArrayList<>(parentCancellations.size());
        for (RunCancellation cancellation : parentCancellations) {
            parentRemovers.add(cancellation.registerAbort(hook));
        }
        return () -> {
            removeFromOwn.run();
            for (Runnable removeFromParent : parentRemovers) {
                removeFromParent.run();
            }
        };
    }

    /** 覆盖自己的任务级取消令牌；子 Agent 的 TaskStop 令牌走这里，不影响父。 */
    public void bindCancellation(RunCancellation cancellation) {
        if (cancellation != null) {
            this.ownCancellation = cancellation;
        }
    }

    /** 追加上游取消令牌（只读引用，不覆盖 own，也不覆盖链上其它父令牌）。 */
    public void bindParentCancellation(RunCancellation cancellation) {
        if (cancellation != null && cancellation != ownCancellation
                && !parentCancellations.contains(cancellation)) {
            parentCancellations.add(cancellation);
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

    /**
     * 父令牌链的完整上游：own（任务级）与 parent（用户 stop）两个令牌都在列。
     *
     * <p>透传给下一层时必须用本方法而非 {@link #getCancellation()}：后者 own 优先，
     * 后台子 Agent 覆盖任务级令牌后会交出任务令牌而非用户 stop 令牌，导致再下一层
     * 收不到 {@code /stop}。两个令牌都挂上，任一侧 cancel 都能中止孙 Agent 的在途工作，
     * 与 {@link #registerAbort(Runnable)} 的双令牌模式一致。</p>
     */
    public List<RunCancellation> cancellationChain() {
        List<RunCancellation> chain = new ArrayList<>(parentCancellations.size() + 1);
        if (ownCancellation != null) {
            chain.add(ownCancellation);
        }
        for (RunCancellation cancellation : parentCancellations) {
            if (cancellation != ownCancellation && !chain.contains(cancellation)) {
                chain.add(cancellation);
            }
        }
        return chain;
    }

    /** own 优先：后台任务覆盖令牌后，取消原因以任务级为准。 */
    public RunCancellation getCancellation() {
        return ownCancellation != null
                ? ownCancellation
                : parentCancellations.isEmpty() ? null : parentCancellations.get(0);
    }

    private boolean isOwnCancelled() {
        RunCancellation cancellation = this.ownCancellation;
        return cancellation != null && cancellation.isCancelled();
    }

    private boolean isParentCancelled() {
        for (RunCancellation cancellation : parentCancellations) {
            if (cancellation.isCancelled()) {
                return true;
            }
        }
        return false;
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

    /**
     * 记录本轮已注入的后台任务通知。
     */
    public void addConsumedBackgroundNotifications(Collection<String> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return;
        }
        for (String taskId : taskIds) {
            if (taskId != null && !taskId.isBlank()) {
                consumedBackgroundNotificationTaskIds.add(taskId.trim());
            }
        }
    }

    /** 取出并清空本轮已消费的通知 taskId（run 成功收口时调用）。 */
    public Set<String> drainConsumedBackgroundNotifications() {
        Set<String> drained = Set.copyOf(consumedBackgroundNotificationTaskIds);
        consumedBackgroundNotificationTaskIds.clear();
        return drained;
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
