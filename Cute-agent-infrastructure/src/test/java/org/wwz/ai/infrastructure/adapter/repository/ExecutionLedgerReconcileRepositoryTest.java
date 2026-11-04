package org.wwz.ai.infrastructure.adapter.repository;

import org.junit.Test;
import org.wwz.ai.domain.agent.ledger.reconcile.OrphanBackgroundTaskRow;
import org.wwz.ai.infrastructure.dao.cute.IBackgroundTaskDao;
import org.wwz.ai.infrastructure.dao.cute.po.BackgroundTaskPO;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ExecutionLedgerReconcileRepositoryTest {

    @Test
    public void skipsBackgroundTasksWithoutSessionId() {
        List<BackgroundTaskPO> rows = new ArrayList<>();
        rows.add(backgroundTask("null-session", null));
        rows.add(backgroundTask("blank-session", "  "));
        rows.add(backgroundTask("valid-session", "session-1"));

        IBackgroundTaskDao backgroundTaskDao = new IBackgroundTaskDao() {
            @Override
            public List<BackgroundTaskPO> selectBySessionId(String sessionId) {
                return List.of();
            }

            @Override
            public BackgroundTaskPO selectBySessionAndTaskId(String sessionId, String taskId) {
                return null;
            }

            @Override
            public int upsert(BackgroundTaskPO row) {
                return 1;
            }

            @Override
            public List<BackgroundTaskPO> selectOrphanedRunning(long startedBeforeMs, int limit) {
                return rows;
            }
        };

        ExecutionLedgerReconcileRepository repository =
                new ExecutionLedgerReconcileRepository(null, null, null, backgroundTaskDao);

        List<OrphanBackgroundTaskRow> result = repository.queryOrphanedRunningBackgroundTasks(0L, 10);

        assertEquals(1, result.size());
        assertEquals("session-1", result.get(0).getSessionId());
        assertEquals("valid-session", result.get(0).getTask().getId());
    }

    private static BackgroundTaskPO backgroundTask(String taskId, String sessionId) {
        BackgroundTaskPO row = new BackgroundTaskPO();
        row.setTaskId(taskId);
        row.setSessionId(sessionId);
        row.setType("generic");
        row.setStatus("running");
        row.setStartedAtMs(1L);
        return row;
    }
}
