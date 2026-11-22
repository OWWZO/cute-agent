package org.wwz.ai.test.domain.subagent;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.wwz.ai.domain.agent.runtime.CuteRuntimeDependencies;
import org.wwz.ai.domain.agent.runtime.subagent.BackgroundSubAgentExecutor;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentResult;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentRegistry;
import org.wwz.ai.domain.agent.runtime.cancel.RunCancellation;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTask;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTaskRegistry;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionBackgroundTaskHub;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionNotificationHub;
import org.wwz.ai.domain.agent.runtime.tasklist.TasklistPersistencePort;

import java.util.List;
import java.util.Optional;

/**
 * 跨线程完成 WAITING 子 Agent 时，必须回到根会话的共享 registry。
 */
public class BackgroundSubAgentPersistedCompletionTest {

    @After
    public void tearDown() {
        SessionBackgroundTaskHub.clearAll();
        SessionNotificationHub.clearAll();
    }

    @Test
    public void completePersistedTaskUsesRootSessionRegistry() {
        String rootSessionId = "root-session";
        String parentSessionId = "sub_parent";
        RuntimeBackgroundTask task = RuntimeBackgroundTask.builder()
                .id("task-root-registry")
                .type(RuntimeBackgroundTask.TYPE_LOCAL_AGENT)
                .status(RuntimeBackgroundTask.STATUS_RUNNING)
                .agentId("child-agent")
                .agentType(SubAgentRegistry.TYPE_GENERAL_PURPOSE)
                .childSessionId("sub_child-agent")
                .parentSessionId(parentSessionId)
                .parentAgentId("root-session")
                .ownerAgentId("root-session")
                .rootSessionId(rootSessionId)
                .delegationDepth(1)
                .phase(RuntimeBackgroundTask.PHASE_WAITING_BACKGROUND)
                .cancellation(new RunCancellation())
                .build();

        TasklistPersistencePort persistence = Mockito.mock(TasklistPersistencePort.class);
        Mockito.when(persistence.loadBackgroundTasks(rootSessionId)).thenReturn(List.of(task));
        Mockito.when(persistence.loadBackgroundTasks(parentSessionId)).thenReturn(List.of());
        Mockito.when(persistence.findBackgroundTask(Mockito.anyString(), Mockito.eq(task.getId())))
                .thenAnswer(invocation -> rootSessionId.equals(invocation.getArgument(0))
                        ? Optional.of(task)
                        : Optional.empty());
        CuteRuntimeDependencies dependencies = CuteRuntimeDependencies.builder()
                .tasklistPersistencePort(persistence)
                .build();

        Optional<RuntimeBackgroundTask> completed = BackgroundSubAgentExecutor.completePersistedTask(
                task,
                SubAgentResult.builder()
                        .status(SubAgentResult.STATUS_COMPLETED)
                        .agentId(task.getAgentId())
                        .agentType(task.getAgentType())
                        .content("final")
                        .build(),
                dependencies,
                null,
                null);

        Assert.assertTrue(completed.isPresent());
        Assert.assertEquals(RuntimeBackgroundTask.STATUS_COMPLETED, completed.get().getStatus());
        RuntimeBackgroundTaskRegistry rootRegistry =
                SessionBackgroundTaskHub.getOrCreate(rootSessionId, persistence);
        RuntimeBackgroundTask shared = rootRegistry.get(task.getId()).orElseThrow();
        Assert.assertEquals(RuntimeBackgroundTask.STATUS_COMPLETED, shared.getStatus());
    }
}
