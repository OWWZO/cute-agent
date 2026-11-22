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
import org.wwz.ai.domain.agent.runtime.tasklist.SessionTaskItem;
import org.wwz.ai.domain.agent.runtime.tasklist.TasklistPersistencePort;

import java.util.List;
import java.util.Map;

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
        TasklistPersistencePort tasklistPersistencePort =
                Mockito.mock(TasklistPersistencePort.class);
        ConversationSessionBootstrapApplicationService service =
                new ConversationSessionBootstrapApplicationService(
                        replayService,
                        ledgerQueryService,
                        askUserService,
                        desktopControlService,
                        planApprovalService,
                        tasklistPersistencePort
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
        Mockito.when(tasklistPersistencePort.loadTodos("session-1")).thenReturn(List.of(
                SessionTaskItem.builder()
                        .id("1")
                        .subject("恢复中的任务")
                        .description("刷新后仍然存在")
                        .status(SessionTaskItem.STATUS_IN_PROGRESS)
                        .build()
        ));

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
        Assert.assertEquals(1, bootstrap.getSessionTasks().get("total"));
        Assert.assertEquals(1L, bootstrap.getSessionTasks().get("inProgress"));
        Assert.assertEquals(
                "恢复中的任务",
                ((Map<?, ?>) ((List<?>) bootstrap.getSessionTasks().get("tasks")).get(0)).get("subject")
        );
        Mockito.verifyNoInteractions(ledgerQueryService);
        Mockito.verify(replayService, Mockito.never())
                .queryRunReplay(Mockito.anyString(), Mockito.anyString());
        Mockito.verify(askUserService).listPending("session-1");
        Mockito.verify(desktopControlService).listPending("session-1");
        Mockito.verify(planApprovalService).listPending("session-1");
        Mockito.verify(tasklistPersistencePort).loadTodos("session-1");
    }
}
