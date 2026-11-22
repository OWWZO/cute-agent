package org.wwz.ai.infrastructure.tasklist;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTask;
import org.wwz.ai.infrastructure.dao.cute.IBackgroundTaskDao;
import org.wwz.ai.infrastructure.dao.cute.ISessionTodoDao;
import org.wwz.ai.infrastructure.dao.cute.po.BackgroundTaskPO;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MyBatisTasklistPersistenceBackgroundTaskTest {

    @Test
    public void newTreeFieldsRoundTripThroughPo() {
        IBackgroundTaskDao dao = mock(IBackgroundTaskDao.class);
        MyBatisTasklistPersistence persistence =
                new MyBatisTasklistPersistence(mock(ISessionTodoDao.class), dao);
        RuntimeBackgroundTask task = RuntimeBackgroundTask.builder()
                .id("task-1")
                .type(RuntimeBackgroundTask.TYPE_LOCAL_AGENT)
                .status(RuntimeBackgroundTask.STATUS_RUNNING)
                .agentId("agent-1")
                .agentType("general-purpose")
                .childSessionId("sub_agent-1")
                .parentSessionId("root")
                .parentRequestId("req-root")
                .parentToolUseId("tool-1")
                .parentAgentId("root")
                .ownerAgentId("root")
                .rootSessionId("root")
                .delegationDepth(1)
                .phase(RuntimeBackgroundTask.PHASE_WAITING_BACKGROUND)
                .executionMode(RuntimeBackgroundTask.EXECUTION_MODE_INLINE)
                .closingTurnCount(2)
                .startedAtMs(1L)
                .build();

        persistence.upsertBackgroundTask("root", task);

        ArgumentCaptor<BackgroundTaskPO> captor = ArgumentCaptor.forClass(BackgroundTaskPO.class);
        verify(dao).upsert(captor.capture());
        BackgroundTaskPO row = captor.getValue();
        Assert.assertEquals("sub_agent-1", row.getChildSessionId());
        Assert.assertEquals("root", row.getParentSessionId());
        Assert.assertEquals("req-root", row.getParentRequestId());
        Assert.assertEquals("tool-1", row.getParentToolUseId());
        Assert.assertEquals("root", row.getParentAgentId());
        Assert.assertEquals("root", row.getOwnerAgentId());
        Assert.assertEquals("root", row.getRootSessionId());
        Assert.assertEquals(Integer.valueOf(1), row.getDelegationDepth());
        Assert.assertEquals(RuntimeBackgroundTask.PHASE_WAITING_BACKGROUND, row.getPhase());
        Assert.assertEquals(RuntimeBackgroundTask.EXECUTION_MODE_INLINE, row.getExecutionMode());
        Assert.assertEquals(Integer.valueOf(2), row.getClosingTurnCount());
    }

    @Test
    public void activeChildSessionLookupMapsRunningTask() {
        IBackgroundTaskDao dao = mock(IBackgroundTaskDao.class);
        MyBatisTasklistPersistence persistence =
                new MyBatisTasklistPersistence(mock(ISessionTodoDao.class), dao);
        BackgroundTaskPO row = new BackgroundTaskPO();
        row.setTaskId("task-1");
        row.setStatus(RuntimeBackgroundTask.STATUS_RUNNING);
        row.setAgentId("agent-1");
        row.setChildSessionId("sub_agent-1");
        row.setPhase(RuntimeBackgroundTask.PHASE_WAITING_BACKGROUND);
        when(dao.selectActiveByChildSessionId("sub_agent-1")).thenReturn(row);

        Optional<RuntimeBackgroundTask> found =
                persistence.findActiveBackgroundTaskByChildSessionId("sub_agent-1");

        Assert.assertTrue(found.isPresent());
        Assert.assertEquals(RuntimeBackgroundTask.PHASE_WAITING_BACKGROUND, found.get().getPhase());
    }
}
