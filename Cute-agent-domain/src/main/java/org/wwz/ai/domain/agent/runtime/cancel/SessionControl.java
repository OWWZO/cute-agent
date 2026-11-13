package org.wwz.ai.domain.agent.runtime.cancel;

import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTaskRegistry;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionBackgroundTaskHub;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionTaskListStore;
import org.wwz.ai.domain.agent.runtime.tasklist.TasklistPersistencePort;

/**
 * 会话级共享控制面：Todo 任务列表与后台任务注册表。
 *
 * <p><b>不变量：</b>本类实例被父 Agent 与子 Agent 共享（TaskStop / TaskOutput / TaskCreate
 * 必须命中子 Agent）；run 级独占状态（取消令牌、turnClosed、注入队列）在 {@link RunControl}。</p>
 *
 * <p><b>必须由 spawn 方用父 sessionKey eager 物化：</b>子 Agent 的 sessionId 是
 * {@code sub_<agentId>}，若等子 Agent 首次读取时惰性补建，会用子 key 建表并绑进本共享面，
 * 父侧的 TaskList / TaskOutput 从此全部 miss。{@code SubAgentContextFactory} 在 spawn 时
 * 就调用父的 {@code requireSessionTaskList()} / {@code requireBackgroundTasks()} 把实例钉进来。</p>
 *
 * <p><b>并发约束：</b>绑定与「读-判-建」都以本类实例自身为 monitor。不能依赖
 * {@code AgentContext.requireXxx()} 上的 {@code synchronized} —— 父与子是两个不同的
 * {@code AgentContext} 对象，各自的 monitor 互不覆盖，双检锁会失效（父线程与后台子线程
 * 同时看到 null，各建一个 store，两个 Todo 表静默分叉）。</p>
 */
public final class SessionControl {

    /** 会话 Todo 任务列表（TaskCreate/TaskGet）。主/子 Agent 共享同一引用。 */
    private volatile SessionTaskListStore sessionTaskList;

    /**
     * 后台运行任务注册表（TaskStop / TaskOutput）。
     * 按 session 从 DB hydrate，因此必须由持有 sessionKey 的一方补建。
     */
    private volatile RuntimeBackgroundTaskRegistry backgroundTasks;

    public SessionControl() {
    }

    /**
     * 绑定共享的 Todo 列表。null 被忽略，避免把已工作的列表打空。
     */
    public synchronized void bindSessionTaskList(SessionTaskListStore store) {
        // 只在未绑定或重复绑定同一实例时生效，避免后到的绑定覆盖仍在使用的 Todo 列表。
        if (store != null && (this.sessionTaskList == null || this.sessionTaskList == store)) {
            this.sessionTaskList = store;
        }
    }

    /** 流式写法，便于 {@code .sessionControl(new SessionControl().withSessionTaskList(...))}。 */
    public SessionControl withSessionTaskList(SessionTaskListStore store) {
        bindSessionTaskList(store);
        return this;
    }

    public SessionTaskListStore getSessionTaskList() {
        return sessionTaskList;
    }

    /**
     * 读取或按会话 key 补建 Todo 列表（listId 优先 sessionId）。
     * <p>构造（含 hydrate 的 DB 读）在 monitor 之外完成，避免慢库把共享控制面锁住；
     * 并发补建时以 monitor 内二次检查决定唯一发布者，多余的 store 直接丢弃。</p>
     * <p>注意：子 Agent 与父 Agent 共享本实例，子上下文用自己的 sessionId 调用时拿到的
     * 是父列表，这是 {@link #SessionControl} 类注释描述的刻意共享，不是 key 错配。</p>
     */
    public SessionTaskListStore requireSessionTaskList(String listId, TasklistPersistencePort persistence) {
        SessionTaskListStore existing = sessionTaskList;
        if (existing != null) {
            return existing;
        }
        SessionTaskListStore created = new SessionTaskListStore(listId, persistence);
        synchronized (this) {
            if (sessionTaskList == null) {
                sessionTaskList = created;
            }
            return sessionTaskList;
        }
    }

    /**
     * 绑定共享的后台任务表。null 被忽略。
     */
    public synchronized void bindBackgroundTasks(RuntimeBackgroundTaskRegistry registry) {
        // 同上：不覆盖已绑定的注册表，避免丢失在跑任务的 Future/取消句柄。
        if (registry != null && (this.backgroundTasks == null || this.backgroundTasks == registry)) {
            this.backgroundTasks = registry;
        }
    }

    /** 流式写法，便于 {@code .sessionControl(new SessionControl().withBackgroundTasks(...))}。 */
    public SessionControl withBackgroundTasks(RuntimeBackgroundTaskRegistry registry) {
        bindBackgroundTasks(registry);
        return this;
    }

    public RuntimeBackgroundTaskRegistry getBackgroundTasks() {
        return backgroundTasks;
    }

    /**
     * 读取或按会话 key 补建后台任务注册表（进程内按 session 共享，保证跨轮 TaskOutput
     * 命中仍在跑的后台任务）。
     * <p>hub 的创建（含 hydrate）已移到 monitor 与 hub bin lock 之外，避免
     * 「持有本 monitor 再拿静态 map 锁」的锁序反转；发布仍由 monitor 内二次检查收口。
     * 子 Agent 共享父 registry 是类注释描述的刻意行为。</p>
     */
    public RuntimeBackgroundTaskRegistry requireBackgroundTasks(String sessionId, TasklistPersistencePort persistence) {
        return requireBackgroundTasks(sessionId, null, persistence);
    }

    public RuntimeBackgroundTaskRegistry requireBackgroundTasks(String sessionId,
                                                                String requestId,
                                                                TasklistPersistencePort persistence) {
        RuntimeBackgroundTaskRegistry existing = backgroundTasks;
        if (existing != null) {
            return existing;
        }
        RuntimeBackgroundTaskRegistry created =
                SessionBackgroundTaskHub.getOrCreate(sessionId, requestId, persistence);
        synchronized (this) {
            if (backgroundTasks == null) {
                backgroundTasks = created;
            }
            return backgroundTasks;
        }
    }
}
