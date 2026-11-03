package org.wwz.ai.test.domain.ledger;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerWriteRepository;
import org.wwz.ai.domain.agent.ledger.entity.DialogueRun;
import org.wwz.ai.domain.agent.ledger.impl.AgentExecutionRecorderImpl;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunStartRecord;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.tooloutput.ToolOutputWriter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * createRun 的部分失败处理：不能在落库后返回 null 又把 run 永久留在 RUNNING。
 */
public class AgentExecutionRecorderCreateRunTest {

    private final IExecutionLedgerWriteRepository repository = mock(IExecutionLedgerWriteRepository.class);
    private final AgentExecutionRecorderImpl recorder =
            new AgentExecutionRecorderImpl(repository, mock(ToolOutputWriter.class));

    @Test
    public void partialFailureAfterRunInsertCompensatesRunToFailed() {
        doAnswer(invocation -> {
            DialogueRun run = invocation.getArgument(0);
            run.setId(7L);
            return null;
        }).when(repository).insertRun(any());
        doThrow(new IllegalStateException("session upsert failed"))
                .when(repository).upsertSessionOnRunStart(any());

        Long runId = recorder.createRun(DialogueRunStartRecord.builder()
                .requestId("req-1")
                .sessionId("s1")
                .queryText("问题")
                .build());

        Assert.assertNull(runId);
        ArgumentCaptor<DialogueRun> captor = ArgumentCaptor.forClass(DialogueRun.class);
        verify(repository).updateRunFinish(captor.capture());
        Assert.assertEquals(Long.valueOf(7L), captor.getValue().getId());
        Assert.assertEquals(Integer.valueOf(ExecutionLedgerConstants.STATUS_FAILED),
                captor.getValue().getStatus());
        Assert.assertNotNull(captor.getValue().getFinishedAt());
    }

    @Test
    public void blankSessionIdSkipsLedgerInsteadOfWritingNullKeyedSession() {
        Long runId = recorder.createRun(DialogueRunStartRecord.builder()
                .requestId("req-2")
                .sessionId(" ")
                .build());

        Assert.assertNull(runId);
        verify(repository, never()).insertRun(any());
        verify(repository, never()).upsertSessionOnRunStart(any());
    }

    @Test
    public void sessionHeadReadFailureHappensBeforeRunInsert() {
        doThrow(new IllegalStateException("read failed"))
                .when(repository).querySessionBySessionId(any());

        Long runId = recorder.createRun(DialogueRunStartRecord.builder()
                .requestId("req-3")
                .sessionId("s3")
                .build());

        Assert.assertNull(runId);
        verify(repository, never()).insertRun(any());
        verify(repository, never()).updateRunFinish(any());
    }
}
