package org.wwz.ai.test.application.agent.run;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.wwz.ai.application.agent.askuser.AskUserResumeApplicationService;
import org.wwz.ai.application.agent.authorization.ConversationSessionAuthorizationService;
import org.wwz.ai.application.agent.planmode.PlanApprovalResumeApplicationService;
import org.wwz.ai.application.agent.run.AgentRunLaunchGate;
import org.wwz.ai.application.agent.stream.SessionProjectionRegistry;
import org.wwz.ai.domain.agent.adapter.port.BackgroundTaskCompletionEvent;
import org.wwz.ai.domain.agent.adapter.port.BackgroundTaskWakePort;
import org.wwz.ai.domain.agent.ledger.entity.DialogueSession;
import org.wwz.ai.domain.agent.runtime.askuser.IUserQuestionRepository;
import org.wwz.ai.domain.agent.runtime.askuser.UserQuestionRecord;
import org.wwz.ai.domain.agent.runtime.askuser.UserQuestionResumeContext;
import org.wwz.ai.domain.agent.runtime.askuser.UserQuestionStatuses;
import org.wwz.ai.domain.agent.runtime.cancel.ActiveAgentRunRegistry;
import org.wwz.ai.domain.agent.runtime.enums.AgentType;
import org.wwz.ai.domain.agent.runtime.planmode.IPlanApprovalRepository;
import org.wwz.ai.domain.agent.runtime.planmode.PlanApprovalRecord;
import org.wwz.ai.domain.agent.runtime.planmode.PlanApprovalResumeContext;
import org.wwz.ai.domain.agent.runtime.planmode.PlanApprovalStatuses;
import org.wwz.ai.types.agent.user.UserRequestContext;

import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public class ResumeFailureSettlementTest {

    @After
    public void clearUserContext() {
        UserRequestContext.clear();
    }

    @Test
    public void askUserSettlementSeesResetStatusBeforeAutoWakeCheck() {
        UserQuestionRecord record = UserQuestionRecord.builder()
                .questionId("question-1")
                .resumeRequestId("resume-1")
                .sessionId("session-1")
                .userId("user-1")
                .status(UserQuestionStatuses.RESUME_PENDING)
                .resumeContextJson(UserQuestionResumeContext.toJson(
                        UserQuestionResumeContext.builder()
                                .agentType(AgentType.REACT.getValue())
                                .build()))
                .build();
        IUserQuestionRepository repository = Mockito.mock(IUserQuestionRepository.class);
        Mockito.when(repository.findByResumeRequestId("resume-1")).thenReturn(Optional.of(record));
        Mockito.when(repository.casClaimResume("resume-1", "user-1")).thenAnswer(invocation -> {
            record.setStatus(UserQuestionStatuses.RESUMING);
            return true;
        });
        Mockito.when(repository.markStatus("question-1", UserQuestionStatuses.RESUME_PENDING))
                .thenAnswer(invocation -> {
                    record.setStatus(UserQuestionStatuses.RESUME_PENDING);
                    return true;
                });

        ActiveAgentRunRegistry activeRuns = new ActiveAgentRunRegistry();
        AtomicReference<String> statusAtSettle = new AtomicReference<>();
        BackgroundTaskWakePort wakePort = wakePort(statusAtSettle, record);
        AgentRunLaunchGate launchGate = new AgentRunLaunchGate(0) {
            @Override
            public void defer(String requestId, String sessionId, Runnable launch) {
                throw new IllegalStateException("launch scheduling failed");
            }
        };
        AskUserResumeApplicationService service = new AskUserResumeApplicationService(
                repository,
                null,
                authorization(),
                activeRuns,
                (ignoredSessionId, ignoredFrame) -> {
                },
                launchGate,
                null,
                new SessionProjectionRegistry(),
                null);
        ReflectionTestUtils.setField(service, "handlerMap", Collections.emptyMap());
        ReflectionTestUtils.setField(service, "backgroundTaskWakePort", wakePort);
        UserRequestContext.bind("user-1");

        try {
            service.resume("resume-1");
            Assert.fail("launch failure must surface");
        } catch (IllegalStateException expected) {
            Assert.assertEquals("launch scheduling failed", expected.getMessage());
        } finally {
            launchGate.shutdown();
        }

        Assert.assertEquals(UserQuestionStatuses.RESUME_PENDING, statusAtSettle.get());
        Assert.assertTrue(activeRuns.findBySessionId("session-1").isEmpty());
    }

    @Test
    public void planStatusResetFailureStillClosesProjectionWithError() {
        PlanApprovalRecord record = PlanApprovalRecord.builder()
                .approvalId("approval-1")
                .resumeRequestId("resume-plan-1")
                .sessionId("session-plan-1")
                .userId("user-1")
                .status(PlanApprovalStatuses.RESUME_PENDING)
                .resumeContextJson(PlanApprovalResumeContext.toJson(
                        PlanApprovalResumeContext.builder()
                                .agentType(AgentType.REACT.getValue())
                                .build()))
                .build();
        IPlanApprovalRepository repository = Mockito.mock(IPlanApprovalRepository.class);
        Mockito.when(repository.findByResumeRequestId("resume-plan-1")).thenReturn(Optional.of(record));
        Mockito.when(repository.casClaimResume("resume-plan-1", "user-1")).thenReturn(true);
        Mockito.when(repository.markStatus("approval-1", PlanApprovalStatuses.RESUME_PENDING))
                .thenThrow(new IllegalStateException("database down"));

        ActiveAgentRunRegistry activeRuns = new ActiveAgentRunRegistry();
        SessionProjectionRegistry projections = new SessionProjectionRegistry();
        AgentRunLaunchGate launchGate = new AgentRunLaunchGate(0);
        PlanApprovalResumeApplicationService service = new PlanApprovalResumeApplicationService(
                repository,
                null,
                authorization(),
                activeRuns,
                (ignoredSessionId, ignoredFrame) -> {
                },
                launchGate,
                null,
                projections,
                null);
        ReflectionTestUtils.setField(service, "handlerMap", Collections.emptyMap());
        ReflectionTestUtils.setField(service, "dispatchExecutor", null);
        ReflectionTestUtils.setField(service, "backgroundTaskWakePort",
                Mockito.mock(BackgroundTaskWakePort.class));
        UserRequestContext.bind("user-1");

        try {
            service.resume("resume-plan-1");
        } finally {
            launchGate.shutdown();
        }

        Assert.assertTrue(activeRuns.findBySessionId("session-plan-1").isEmpty());
        Assert.assertTrue(projections.listLive("session-plan-1").isEmpty());
    }

    private static ConversationSessionAuthorizationService authorization() {
        return new ConversationSessionAuthorizationService(null, null, null) {
            @Override
            public DialogueSession ensureInteractiveSessionAccessible(String userId, String sessionId) {
                return DialogueSession.builder().sessionId(sessionId).userId(userId).build();
            }
        };
    }

    private static BackgroundTaskWakePort wakePort(AtomicReference<String> statusAtSettle,
                                                   UserQuestionRecord record) {
        return new BackgroundTaskWakePort() {
            @Override
            public boolean onBackgroundTaskCompleted(BackgroundTaskCompletionEvent event) {
                return false;
            }

            @Override
            public void onRunSettled(String sessionId) {
            }

            @Override
            public void onRunSettled(String sessionId, String requestId) {
                statusAtSettle.set(record.getStatus());
            }
        };
    }
}
