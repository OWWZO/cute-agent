package org.wwz.ai.domain.agent.ledger.impl;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerWriteRepository;
import org.wwz.ai.domain.agent.ledger.entity.DialogueRun;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunFinishRecord;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.tooloutput.ToolOutputWriter;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * finishRun 的终态写入必须先把可空字段收口：status=null 不能在 run 落终态后才炸，
 * 没有会话归属时也不能拿 null sessionId 去累加计数。
 */
public class AgentExecutionRecorderFinishRunNullTest {

    private final IExecutionLedgerWriteRepository repository = mock(IExecutionLedgerWriteRepository.class);
    private final AgentExecutionRecorderImpl recorder =
            new AgentExecutionRecorderImpl(repository, mock(ToolOutputWriter.class));

    @Test
    public void nullStatusShouldBeRejectedBeforeAnyWrite() {
        recorder.finishRun(DialogueRunFinishRecord.builder()
                .runId(1L)
                .requestId("req-1")
                .status(null)
                .build());

        verify(repository, never()).queryRunByRequestId(anyString());
        verify(repository, never()).updateRunFinish(any());
    }

    @Test
    public void blankSessionIdShouldSkipSessionCounterUpdate() {
        stubRun("   ");

        recorder.finishRun(successRecord());

        verify(repository).updateRunFinish(any(DialogueRun.class));
        verify(repository, never()).updateSessionRunFinish(
                anyString(), anyInt(), any(), anyInt(), anyInt(), any());
    }

    @Test
    public void normalSessionIdShouldStillUpdateSessionCounter() {
        stubRun("session-1");

        recorder.finishRun(successRecord());

        verify(repository).updateSessionRunFinish(
                eq("session-1"),
                eq(ExecutionLedgerConstants.STATUS_SUCCESS),
                any(),
                eq(1),
                eq(0),
                any());
    }

    private void stubRun(String sessionId) {
        DialogueRun run = DialogueRun.builder()
                .id(1L)
                .requestId("req-1")
                .sessionId(sessionId)
                .build();
        when(repository.queryRunByRequestId("req-1")).thenReturn(run);
        when(repository.queryLlmInvocationsByRunId(1L)).thenReturn(List.of());
        when(repository.queryToolInvocationsByRunId(1L)).thenReturn(List.of());
        when(repository.queryArtifactsByRunId(1L)).thenReturn(List.of());
        when(repository.updateRunFinish(any(DialogueRun.class))).thenReturn(1);
    }

    private DialogueRunFinishRecord successRecord() {
        return DialogueRunFinishRecord.builder()
                .runId(1L)
                .requestId("req-1")
                .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                .finalSummaryText("done")
                .build();
    }
}
