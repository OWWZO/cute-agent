package org.wwz.ai.test.domain.ledger;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerReadRepository;
import org.wwz.ai.domain.agent.ledger.impl.ExecutionLedgerQueryServiceImpl;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunView;
import org.wwz.ai.domain.agent.ledger.tooloutput.ToolOutputReader;

import java.time.LocalDateTime;
import java.util.List;

public class ExecutionLedgerQueryServiceTimeRangeTest {

    @Test
    public void shouldQueryTimeRangeAndClampLimit() {
        IExecutionLedgerReadRepository repository =
                Mockito.mock(IExecutionLedgerReadRepository.class);
        ExecutionLedgerQueryServiceImpl service = new ExecutionLedgerQueryServiceImpl(
                repository,
                Mockito.mock(ToolOutputReader.class)
        );
        LocalDateTime from = LocalDateTime.of(2026, 11, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 7, 0, 0);
        DialogueRunView run = DialogueRunView.builder()
                .id(1L)
                .requestId("req-1")
                .status(1)
                .build();
        Mockito.when(repository.queryRunsByTimeRangeAfter(
                        from,
                        to,
                        null,
                        null,
                        null,
                        100
                ))
                .thenReturn(List.of(run));

        List<DialogueRunView> result = service.queryRunsByTimeRange(from, to, 1000);

        Assert.assertEquals(1, result.size());
        Assert.assertEquals("req-1", result.get(0).getRequestId());
        Mockito.verify(repository).queryRunsByTimeRangeAfter(
                from,
                to,
                null,
                null,
                null,
                100
        );
    }

    @Test
    public void shouldForwardStatusAndKeysetCursorToRepository() {
        IExecutionLedgerReadRepository repository =
                Mockito.mock(IExecutionLedgerReadRepository.class);
        ExecutionLedgerQueryServiceImpl service = new ExecutionLedgerQueryServiceImpl(
                repository,
                Mockito.mock(ToolOutputReader.class)
        );
        LocalDateTime from = LocalDateTime.of(2026, 11, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 7, 0, 0);
        LocalDateTime cursor = LocalDateTime.of(2026, 11, 3, 12, 0);
        List<Integer> statuses = List.of(2, 3);
        Mockito.when(repository.queryRunsByTimeRangeAfter(
                        from, to, statuses, cursor, 50L, 25
                ))
                .thenReturn(List.of());

        service.queryRunsByTimeRange(from, to, statuses, cursor, 50L, 25);

        Mockito.verify(repository).queryRunsByTimeRangeAfter(
                from, to, statuses, cursor, 50L, 25
        );
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectPartialTimeRangeCursor() {
        ExecutionLedgerQueryServiceImpl service = new ExecutionLedgerQueryServiceImpl(
                Mockito.mock(IExecutionLedgerReadRepository.class),
                Mockito.mock(ToolOutputReader.class)
        );
        service.queryRunsByTimeRange(
                LocalDateTime.of(2026, 11, 1, 0, 0),
                LocalDateTime.of(2026, 11, 7, 0, 0),
                List.of(2),
                LocalDateTime.of(2026, 11, 3, 12, 0),
                null,
                25
        );
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectInvertedTimeRange() {
        ExecutionLedgerQueryServiceImpl service = new ExecutionLedgerQueryServiceImpl(
                Mockito.mock(IExecutionLedgerReadRepository.class),
                Mockito.mock(ToolOutputReader.class)
        );
        service.queryRunsByTimeRange(
                LocalDateTime.of(2026, 11, 7, 0, 0),
                LocalDateTime.of(2026, 11, 1, 0, 0),
                10
        );
    }
}
