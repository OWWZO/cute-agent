package org.wwz.ai.test.domain.subagent;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.ledger.ExecutionLedgerRunSupport;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.cancel.PendingInjectMessage;
import org.wwz.ai.domain.agent.runtime.cancel.RunCancellation;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentContextFactory;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTask;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionAgentMailboxHub;
import org.wwz.ai.domain.agent.runtime.tool.ToolCollection;
import org.wwz.ai.domain.agent.runtime.tool.common.AgentDispatchTool;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 子 Agent 控制面隔离回归（026）：SubAgentContextFactory 不得把 run 级控制面共享给子 Agent。
 *
 * <p>锁住三类回归与两类共享语义：</p>
 * <ul>
 *   <li>turnClosed / inject 队列是 run 级独占：子 finishRun 不得关闭父回合、不得摘父 ActiveRun；</li>
 *   <li>取消令牌是令牌链：子任务令牌不顶掉父用户 stop；父 stop 透传到子；</li>
 *   <li>sessionTaskList / backgroundTasks 是会话级共享：TaskStop / TaskOutput 必须命中子 Agent，
 *       且 spawn 时用父 sessionKey eager 物化（子 sessionId 是 sub_&lt;agentId&gt;，惰性补建会把子 key 绑进共享面）。</li>
 * </ul>
 *
 * <p>{@code forkForParallelTask} 走与 {@code SubAgentContextFactory} 同构的规则，一并锁住。</p>
 *
 * <p>放 app 模块：断言调 {@link AgentDispatchTool#shouldSettleParentStream}，
 * 会触发 @Slf4j 静态初始化，只有 app 模块 classpath 能初始化（同 AgentToolPoolNestingHardeningTest）。</p>
 */
public class SubAgentControlIsolationTest {

    private AgentContext newParent(String sessionId) {
        return AgentContext.builder()
                .requestId("req-" + sessionId)
                .sessionId(sessionId)
                .query("父任务")
                .toolCollection(new ToolCollection())
                .build();
    }

    private AgentContext spawnChild(AgentContext parent, String agentId) {
        return SubAgentContextFactory.create(
                parent, "子任务 prompt", "子任务描述", new ToolCollection(), agentId, "general-purpose");
    }

    // ---- turnClosed：run 级独占 ----

    @Test
    public void childFinishRunMustNotCloseParentTurn() {
        AgentContext parent = newParent("sess-iso-1");
        AgentContext child = spawnChild(parent, "agent-iso-1");

        ExecutionLedgerRunSupport.finishRun(
                child, ExecutionLedgerConstants.STATUS_SUCCESS, "子任务结论", null, null);

        Assert.assertFalse("子 Agent finishRun 不得关闭父回合", parent.isTurnClosed());
    }

    @Test
    public void parentFinishRunMustNotCloseChildTurn() {
        AgentContext parent = newParent("sess-iso-2");
        AgentContext child = spawnChild(parent, "agent-iso-2");

        ExecutionLedgerRunSupport.finishRun(
                parent, ExecutionLedgerConstants.STATUS_SUCCESS, "父任务结论", null, null);

        Assert.assertFalse("父 Agent finishRun 不得关闭子回合", child.isTurnClosed());
    }

    @Test
    public void childFinishRunMustNotSettleParentStream() {
        AgentContext parent = newParent("sess-iso-3");
        AgentContext child = spawnChild(parent, "agent-iso-3");

        ExecutionLedgerRunSupport.finishRun(
                child, ExecutionLedgerConstants.STATUS_SUCCESS, "子任务结论", null, null);

        // 父 run 未 finish：即使后台子 Agent 已结束，也不得发 stream_settle 摘掉父 ActiveRun
        Assert.assertFalse("子 Agent 结束不得触发父流 settle",
                AgentDispatchTool.shouldSettleParentStream(parent));
    }

    // ---- 会话级共享：sessionTaskList / backgroundTasks ----

    @Test
    public void sessionScopedStateMustStayShared() {
        AgentContext parent = newParent("sess-iso-4");
        AgentContext child = spawnChild(parent, "agent-iso-4");

        Assert.assertSame("Todo 列表必须父子共享（TaskCreate/TaskGet 同一列表）",
                parent.requireSessionTaskList(), child.requireSessionTaskList());
        Assert.assertSame("后台任务表必须父子共享（TaskStop/TaskOutput 命中子 Agent）",
                parent.requireBackgroundTasks(), child.requireBackgroundTasks());
    }

    @Test
    public void spawnMustMaterializeParentSessionScopeEagerly() {
        AgentContext parent = newParent("sess-iso-5");
        AgentContext child = spawnChild(parent, "agent-iso-5");

        // 子 Agent 先触碰共享面（真实时序：子步进中先用到 TaskList / 后台任务表）
        child.requireSessionTaskList();
        child.requireBackgroundTasks();

        // 子 sessionId 是 sub_<agentId>；惰性补建会把它绑进共享面，父侧全部 miss
        Assert.assertEquals("父 Todo 列表的 listId 必须是父 sessionId，不能被子 key 污染",
                "sess-iso-5", parent.requireSessionTaskList().getListId());

        RuntimeBackgroundTask task = parent.requireBackgroundTasks()
                .register("command", "父注册的后台任务", "echo hi");
        Assert.assertTrue("父的后台任务必须能被父侧 TaskOutput 命中",
                parent.requireBackgroundTasks().get(task.getId()).isPresent());
    }

    // ---- inject 队列：run 级独占 ----

    @Test
    public void childInjectQueueMustNotReplaceParentQueue() {
        AgentContext parent = newParent("sess-iso-6");
        ConcurrentLinkedQueue<PendingInjectMessage> parentQueue = parent.requirePendingInjects();
        AgentContext child = spawnChild(parent, "agent-iso-6");

        child.bindPendingInjectQueue(SessionAgentMailboxHub.queue("sess-iso-6", "agent-iso-6"));

        Assert.assertSame("子 Agent 绑定自己的 mailbox 不得替换父 run 的 inject 队列",
                parentQueue, parent.requirePendingInjects());
    }

    @Test
    public void parentInjectionMustRemainReachableAfterChildBind() {
        AgentContext parent = newParent("sess-iso-7");
        AgentContext child = spawnChild(parent, "agent-iso-7");

        child.bindPendingInjectQueue(SessionAgentMailboxHub.queue("sess-iso-7", "agent-iso-7"));

        parent.offerInject(PendingInjectMessage.builder()
                .text("用户中途指导").source(PendingInjectMessage.SOURCE_USER).build());

        List<PendingInjectMessage> drained = parent.drainPendingInjects();
        Assert.assertEquals("父 inject 必须仍可达（中途指导不得静默丢失）", 1, drained.size());
        Assert.assertEquals("用户中途指导", drained.get(0).getText());
    }

    @Test
    public void siblingSubAgentsMustNotShareInjectQueue() {
        AgentContext parent = newParent("sess-iso-8");
        AgentContext childA = spawnChild(parent, "agent-iso-8a");
        AgentContext childB = spawnChild(parent, "agent-iso-8b");

        childA.bindPendingInjectQueue(SessionAgentMailboxHub.queue("sess-iso-8", "agent-iso-8a"));
        childB.bindPendingInjectQueue(SessionAgentMailboxHub.queue("sess-iso-8", "agent-iso-8b"));

        Assert.assertNotSame("兄弟子 Agent 的 inject 队列不得共享（last-write-wins 串扰）",
                childA.requirePendingInjects(), childB.requirePendingInjects());

        childA.offerInject(PendingInjectMessage.builder().text("给A").build());
        childB.offerInject(PendingInjectMessage.builder().text("给B").build());
        Assert.assertEquals("给A", childA.drainPendingInjects().get(0).getText());
        Assert.assertEquals("给B", childB.drainPendingInjects().get(0).getText());
    }

    // ---- 并发 fork：与子 Agent 同构的 run 级独占规则 ----

    @Test
    public void parallelForkMustOwnRunControlButShareSessionScope() {
        AgentContext parent = newParent("sess-iso-12");
        AgentContext fork = parent.forkForParallelTask("并发子任务");

        Assert.assertNotSame("并发 fork 的 run 级控制面必须独占（finishRun 不得关闭父回合）",
                parent.control(), fork.control());
        Assert.assertSame("会话级 Todo 列表必须共享",
                parent.requireSessionTaskList(), fork.requireSessionTaskList());
        Assert.assertSame("会话级后台任务表必须共享（TaskOutput 命中并发子任务）",
                parent.requireBackgroundTasks(), fork.requireBackgroundTasks());
    }

    @Test
    public void parallelForkFinishRunMustNotCloseParentTurn() {
        AgentContext parent = newParent("sess-iso-13");
        AgentContext fork = parent.forkForParallelTask("并发子任务");

        ExecutionLedgerRunSupport.finishRun(
                fork, ExecutionLedgerConstants.STATUS_SUCCESS, "并发子任务结论", null, null);

        Assert.assertFalse("并发 fork 结束不得关闭父回合", parent.isTurnClosed());
    }

    @Test
    public void parallelForkInjectQueueMustNotReplaceParentQueue() {
        AgentContext parent = newParent("sess-iso-14");
        ConcurrentLinkedQueue<PendingInjectMessage> parentQueue = parent.requirePendingInjects();
        AgentContext fork = parent.forkForParallelTask("并发子任务");

        fork.bindPendingInjectQueue(SessionAgentMailboxHub.queue("sess-iso-14", "fork-14"));

        Assert.assertSame("并发 fork 绑定自己的 inject 队列不得替换父 run 的队列",
                parentQueue, parent.requirePendingInjects());
    }

    @Test
    public void parallelForkMustStillReceiveUserStop() {
        AgentContext parent = newParent("sess-iso-15");
        // 用户 stop 令牌在 run 开始时由 ActiveAgentRunRegistry 注入父，早于 fork
        RunCancellation userToken = new RunCancellation();
        parent.control().bindCancellation(userToken);
        AgentContext fork = parent.forkForParallelTask("并发子任务");

        userToken.cancel(RunCancellation.REASON_USER_STOP);

        Assert.assertTrue("父 /stop 必须杀得到并发子任务", fork.isRunCancelled());
    }

    // ---- 取消令牌：令牌链 ----

    @Test
    public void backgroundTaskCancelMustNotKillParent() {
        AgentContext parent = newParent("sess-iso-9");
        AgentContext child = spawnChild(parent, "agent-iso-9");

        // 后台子 Agent 用任务级令牌（SubAgentRunner:240 的 cancellationOverride）
        RunCancellation taskCancel = new RunCancellation();
        child.control().bindCancellation(taskCancel);
        taskCancel.cancel("task_stop");

        Assert.assertFalse("TaskStop 取消任务令牌不得误杀父 run", parent.isRunCancelled());
    }

    @Test
    public void parentUserStopMustCancelChild() {
        AgentContext parent = newParent("sess-iso-10");
        // 用户 stop 令牌在 run 开始时由 ActiveAgentRunRegistry 注入父，早于子 Agent spawn
        RunCancellation userToken = new RunCancellation();
        parent.control().bindCancellation(userToken);
        AgentContext child = spawnChild(parent, "agent-iso-10");

        userToken.cancel(RunCancellation.REASON_USER_STOP);

        Assert.assertTrue("/stop 必须杀得到嵌套子 Agent", child.isRunCancelled());
    }

    @Test
    public void taskStopAndUserStopMustBeIndependent() {
        AgentContext parent = newParent("sess-iso-11");
        RunCancellation userToken = new RunCancellation();
        parent.control().bindCancellation(userToken);
        AgentContext child = spawnChild(parent, "agent-iso-11");

        RunCancellation taskCancel = new RunCancellation();
        child.control().bindCancellation(taskCancel);
        taskCancel.cancel("task_stop");

        Assert.assertFalse("任务级取消不得经父链误伤", parent.isRunCancelled());

        userToken.cancel(RunCancellation.REASON_USER_STOP);
        Assert.assertTrue("用户 stop 必须仍杀得到已覆盖任务令牌的子 Agent", child.isRunCancelled());
    }

    /**
     * 嵌套第三层：父 Agent 是后台子 Agent 时，它的 own 已被任务令牌覆盖，
     * 透传给孙 Agent 的必须是整条链而不是 own 优先的单个令牌。
     *
     * <p>这是 {@code RunControl.getCancellation()} 的 own 优先语义与令牌链透传的冲突点：
     * 若只透传单个令牌，用户 /stop 取消 userToken 后，孙 Agent 的 parentCancellation 指向
     * 任务令牌，永远收不到取消。</p>
     */
    @Test
    public void grandchildMustReceiveUserStopEvenWhenParentIsBackgroundAgent() {
        AgentContext root = newParent("sess-iso-16");
        // 用户 stop 令牌：ActiveAgentRunRegistry 注入根 Agent
        RunCancellation userToken = new RunCancellation();
        root.control().bindCancellation(userToken);

        AgentContext background = spawnChild(root, "agent-iso-16");
        // SubAgentRunner:240 —— 后台子 Agent 覆盖自己的任务级令牌
        RunCancellation taskCancel = new RunCancellation();
        background.control().bindCancellation(taskCancel);

        AgentContext grandchild = spawnChild(background, "agent-iso-16a");

        userToken.cancel(RunCancellation.REASON_USER_STOP);

        Assert.assertTrue("用户 stop 必须经两层令牌链杀到孙 Agent", grandchild.isRunCancelled());
    }

    @Test
    public void grandchildMustNotBeKilledByBackgroundTaskStop() {
        AgentContext root = newParent("sess-iso-17");
        RunCancellation userToken = new RunCancellation();
        root.control().bindCancellation(userToken);

        AgentContext background = spawnChild(root, "agent-iso-17");
        RunCancellation taskCancel = new RunCancellation();
        background.control().bindCancellation(taskCancel);

        AgentContext grandchild = spawnChild(background, "agent-iso-17a");

        taskCancel.cancel("task_stop");

        Assert.assertFalse("TaskStop 只杀后台子 Agent，不得经链误伤孙 Agent（孙只认用户 stop）",
                grandchild.isRunCancelled());

        userToken.cancel(RunCancellation.REASON_USER_STOP);
        Assert.assertTrue("用户 stop 仍应杀得到孙 Agent", grandchild.isRunCancelled());
    }

    // ---- dateInfo：每次现算，不继承父 ----

    @Test
    public void childDateInfoIsComputedFreshInsteadOfInheritingParent() {
        AgentContext parent = AgentContext.builder()
                .requestId("req-sess-iso-18")
                .sessionId("sess-iso-18")
                .query("父任务")
                .toolCollection(new ToolCollection())
                .dateInfo("今天是 2020年1月1日 星期三")
                .build();

        AgentContext child = spawnChild(parent, "agent-iso-18");

        Assert.assertNotEquals("子 Agent 的 dateInfo 必须是当前日期，不能沿用父的陈旧值",
                "今天是 2020年1月1日 星期三", child.getDateInfo());
        Assert.assertTrue("子 Agent 的 dateInfo 必须含当日年份",
                child.getDateInfo().contains(String.valueOf(java.time.LocalDate.now().getYear())));
    }
}
