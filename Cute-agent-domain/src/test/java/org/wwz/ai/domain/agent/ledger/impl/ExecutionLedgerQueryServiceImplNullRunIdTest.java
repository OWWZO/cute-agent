package org.wwz.ai.domain.agent.ledger.impl;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerReadRepository;
import org.wwz.ai.domain.agent.ledger.entity.ArtifactRecord;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunView;
import org.wwz.ai.domain.agent.ledger.tooloutput.ToolOutputReader;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ArtifactRecord.runId 允许为空（直接请求产物挂在 requestId 上），
 * 会话最近 run 列表不能因为一条历史产物行而整体失败。
 */
public class ExecutionLedgerQueryServiceImplNullRunIdTest {

    @Test
    public void artifactWithoutRunIdShouldNotBreakRecentSessionRuns() {
        IExecutionLedgerReadRepository repository = mock(IExecutionLedgerReadRepository.class);
        ExecutionLedgerQueryServiceImpl service =
                new ExecutionLedgerQueryServiceImpl(repository, mock(ToolOutputReader.class));

        when(repository.queryRecentRunsBySessionId(anyString(), anyInt()))
                .thenReturn(List.of(DialogueRunView.builder().id(1L).requestId("req-1").build()));
        when(repository.queryArtifactsByRunIdsWithSubagentFold(List.of(1L)))
                .thenReturn(List.of(
                        ArtifactRecord.builder().runId(null).requestId("req-1").fileName("legacy.txt").build(),
                        ArtifactRecord.builder().runId(1L).requestId("req-1").fileName("kept.txt").build()));

        List<DialogueRunView> runs = service.queryRecentSessionRuns("session-1", 10);

        Assert.assertEquals(1, runs.size());
        Assert.assertEquals(1, runs.get(0).getArtifactSummaries().size());
        Assert.assertEquals("kept.txt", runs.get(0).getArtifactSummaries().get(0).getFileName());
    }
}
