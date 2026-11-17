package org.wwz.ai.application.agent.query;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.wwz.ai.application.agent.askuser.AskUserQuestionApplicationService;
import org.wwz.ai.application.agent.desktopcontrol.DesktopControlApplicationService;
import org.wwz.ai.application.agent.planmode.PlanApprovalApplicationService;
import org.wwz.ai.domain.agent.ledger.ExecutionLedgerQueryService;
import org.wwz.ai.domain.agent.ledger.model.ConversationHistoryPage;
import org.wwz.ai.domain.agent.ledger.model.ConversationRunSummary;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.replay.ConversationHistoryReplayService;

import java.util.List;

public class ConversationSessionBootstrapApplicationServiceTest {

    @Test
    public void chatBootstrapSkipsWorkspaceFilesAndReplay() {
        ConversationHistoryReplayService replayService =
                Mockito.mock(ConversationHistoryReplayService.class);
        ExecutionLedgerQueryService ledgerQueryService =
                Mockito.mock(ExecutionLedgerQueryService.class);
        AskUserQuestionApplicationService askUserService =
                Mockito.mock(AskUserQuestionApplicationService.class);
        DesktopControlApplicationService desktopControlService =
                Mockito.mock(DesktopControlApplicationService.class);
        PlanApprovalApplicationService planApprovalService =
                Mockito.mock(PlanApprovalApplicationService.class);
        ConversationSessionBootstrapApplicationService service =
                new ConversationSessionBootstrapApplicationService(
                        replayService,
                        ledgerQueryService,
                        askUserService,
                        desktopControlService,
                        planApprovalService
                );

        ConversationHistoryPage page = ConversationHistoryPage.builder()
                .sessionId("session-1")
                .runs(List.of(ConversationRunSummary.builder()
                        .requestId("run-1")
                        .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                        .hasReplay(true)
                        .build()))
                .build();
        Mockito.when(replayService.queryConversationHistoryPage(
                "user-1",
                "session-1",
                20,
                null
        )).thenReturn(page);

        var bootstrap = service.queryBootstrap(
                "user-1",
                "session-1",
                20,
                null,
                false,
                false
        );

        Assert.assertTrue(bootstrap.getFiles().isEmpty());
        Assert.assertTrue(bootstrap.getReplays().isEmpty());
        Assert.assertFalse(bootstrap.isReplaysIncluded());
        Mockito.verifyNoInteractions(ledgerQueryService);
        Mockito.verify(replayService, Mockito.never())
                .queryRunReplay(Mockito.anyString(), Mockito.anyString());
        Mockito.verify(askUserService).listPending("session-1");
        Mockito.verify(desktopControlService).listPending("session-1");
        Mockito.verify(planApprovalService).listPending("session-1");
    }
}
