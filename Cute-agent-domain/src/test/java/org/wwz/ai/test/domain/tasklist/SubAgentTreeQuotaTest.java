package org.wwz.ai.test.domain.tasklist;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentResult;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTask;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTaskRegistry;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentTreeLimits;
import org.wwz.ai.domain.agent.runtime.tasklist.TasklistPersistencePort;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SubAgentTreeQuotaTest {

    @Test
    public void rejectsSixthDirectChildBeforeTaskCreation() {
        RuntimeBackgroundTaskRegistry registry = new RuntimeBackgroundTaskRegistry();

        for (int i = 1; i <= SubAgentTreeLimits.MAX_DIRECT_CHILDREN; i++) {
            Assert.assertTrue(registry.reserveTreeAgent("root", "parent", "child-" + i).allowed());
        }

        RuntimeBackgroundTaskRegistry.TreeQuotaDecision denied =
                registry.reserveTreeAgent("root", "parent", "child-6");
        Assert.assertFalse(denied.allowed());
        Assert.assertTrue(denied.reason().contains("直接子节点"));
    }

    @Test
    public void terminalDirectChildrenReleaseSlotForNewDispatch() {
        RuntimeBackgroundTaskRegistry registry = new RuntimeBackgroundTaskRegistry();
        for (int i = 1; i <= SubAgentTreeLimits.MAX_DIRECT_CHILDREN; i++) {
            Assert.assertTrue(registry.reserveTreeAgent("root", "parent", "child-" + i).allowed());
        }
        for (int i = 1; i <= SubAgentTreeLimits.MAX_DIRECT_CHILDREN; i++) {
            registry.releaseTreeAgent("root", "child-" + i);
        }

        for (int i = 1; i <= SubAgentTreeLimits.MAX_DIRECT_CHILDREN; i++) {
            Assert.assertTrue(registry.reserveTreeAgent("root", "parent", "child-new-" + i).allowed());
        }
        Assert.assertFalse(registry.reserveTreeAgent("root", "parent", "child-new-6").allowed());
    }
    @Test
    public void aliveSlotReturnsAfterTerminal() {
        RuntimeBackgroundTaskRegistry registry = new RuntimeBackgroundTaskRegistry();
        for (int i = 1; i <= SubAgentTreeLimits.MAX_ALIVE_AGENTS_PER_TREE; i++) {
            Assert.assertTrue(registry.reserveTreeAgent(
                    "root", "parent-" + i, "alive-" + i).allowed());
        }

        Assert.assertFalse(registry.reserveTreeAgent("root", "parent-9", "alive-9").allowed());

        registry.releaseTreeAgent("root", "alive-1");
        Assert.assertTrue(registry.reserveTreeAgent("root", "parent-9", "alive-9").allowed());
    }

    @Test
    public void waitingTasksKeepAliveSlotUntilTerminal() {
        RuntimeBackgroundTaskRegistry registry = new RuntimeBackgroundTaskRegistry();
        List<RuntimeBackgroundTask> tasks = new ArrayList<>();
        for (int i = 1; i <= SubAgentTreeLimits.MAX_ALIVE_AGENTS_PER_TREE; i++) {
            RuntimeBackgroundTask task = backgroundTask(registry, "waiting-" + i, "parent-" + i);
            task.setPhase(RuntimeBackgroundTask.PHASE_WAITING_BACKGROUND);
            tasks.add(task);
            Assert.assertTrue(registry.reserveTreeAgent(
                    "root", "parent-" + i, "waiting-" + i).allowed());
        }

        Assert.assertFalse(registry.reserveTreeAgent("root", "parent-9", "waiting-9").allowed());

        registry.complete(tasks.get(0).getId(), SubAgentResult.builder()
                .status(SubAgentResult.STATUS_COMPLETED)
                .agentId("waiting-1")
                .build());

        Assert.assertTrue(registry.reserveTreeAgent("root", "parent-9", "waiting-9").allowed());
    }

    @Test
    public void resumeSameAgentDoesNotConsumeCumulativeSlotAgain() {
        RuntimeBackgroundTaskRegistry registry = new RuntimeBackgroundTaskRegistry();

        Assert.assertTrue(registry.reserveTreeAgent("root", "parent", "child").allowed());
        registry.releaseTreeAgent("root", "child");

        Assert.assertTrue(registry.reserveTreeAgent("root", "parent", "child").allowed());
    }

    @Test
    public void terminalTransitionReleasesAliveSlotWithoutCallerCleanup() {
        RuntimeBackgroundTaskRegistry registry = new RuntimeBackgroundTaskRegistry();
        RuntimeBackgroundTask first = backgroundTask(registry, "alive-1", "parent-1");
        for (int i = 1; i <= SubAgentTreeLimits.MAX_ALIVE_AGENTS_PER_TREE; i++) {
            Assert.assertTrue(registry.reserveTreeAgent(
                    "root", "parent-" + i, "alive-" + i).allowed());
        }
        Assert.assertFalse(registry.reserveTreeAgent("root", "parent-9", "alive-9").allowed());

        registry.complete(first.getId(), SubAgentResult.builder()
                .status(SubAgentResult.STATUS_COMPLETED)
                .agentId("alive-1")
                .build());

        Assert.assertTrue(registry.reserveTreeAgent("root", "parent-9", "alive-9").allowed());
    }

    @Test
    public void closingTurnsStopAtLimitAndFailedLaunchCanRollBack() {
        RuntimeBackgroundTaskRegistry registry = new RuntimeBackgroundTaskRegistry();
        RuntimeBackgroundTask task = backgroundTask(registry, "closing", "parent");
        task.setPhase(RuntimeBackgroundTask.PHASE_WAITING_BACKGROUND);

        for (int i = 0; i < SubAgentTreeLimits.MAX_CLOSING_TURNS; i++) {
            Assert.assertTrue(registry.tryBeginClosingTurn(task.getId()));
            registry.markWaiting(task.getId());
        }
        Assert.assertFalse(registry.tryBeginClosingTurn(task.getId()));

        registry.rollbackClosingTurn(task.getId());
        Assert.assertTrue(registry.tryBeginClosingTurn(task.getId()));
    }

    @Test
    public void hydrateRestoresAliveSlotsForRunningRows() {
        String root = "root-hydrated";
        List<RuntimeBackgroundTask> rows = new ArrayList<>();
        for (int i = 1; i <= SubAgentTreeLimits.MAX_ALIVE_AGENTS_PER_TREE; i++) {
            rows.add(RuntimeBackgroundTask.builder()
                    .id("task-" + i)
                    .status(RuntimeBackgroundTask.STATUS_RUNNING)
                    .agentId("alive-" + i)
                    .parentAgentId("parent-" + i)
                    .rootSessionId(root)
                    .build());
        }
        TasklistPersistencePort persistence = mock(TasklistPersistencePort.class);
        when(persistence.loadBackgroundTasks(root)).thenReturn(rows);

        RuntimeBackgroundTaskRegistry registry = new RuntimeBackgroundTaskRegistry(root, persistence);

        Assert.assertFalse(registry.reserveTreeAgent(root, "parent-9", "alive-9").allowed());
    }

    private static RuntimeBackgroundTask backgroundTask(RuntimeBackgroundTaskRegistry registry,
                                                        String agentId,
                                                        String parentAgentId) {
        RuntimeBackgroundTask task = registry.registerLocalAgent(agentId, "general-purpose", "prompt");
        task.setAgentId(agentId);
        task.setRootSessionId("root");
        task.setParentAgentId(parentAgentId);
        task.setOwnerAgentId(parentAgentId);
        task.setPhase(RuntimeBackgroundTask.PHASE_RUNNING);
        return task;
    }
}
