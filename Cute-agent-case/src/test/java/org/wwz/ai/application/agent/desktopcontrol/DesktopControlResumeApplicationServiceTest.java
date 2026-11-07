package org.wwz.ai.application.agent.desktopcontrol;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.application.agent.authorization.ConversationSessionAuthorizationService;
import org.wwz.ai.application.agent.query.AgentQuerySubmitResult;
import org.wwz.ai.application.agent.run.AgentRunLaunchGate;
import org.wwz.ai.domain.agent.ledger.entity.DialogueSession;
import org.wwz.ai.domain.agent.runtime.cancel.ActiveAgentRunRegistry;
import org.wwz.ai.domain.agent.runtime.desktopcontrol.DesktopControlRecord;
import org.wwz.ai.domain.agent.runtime.desktopcontrol.DesktopControlStatuses;
import org.wwz.ai.domain.agent.runtime.desktopcontrol.IDesktopControlRepository;
import org.wwz.ai.domain.agent.runtime.askuser.UserQuestionResumeContext;
import org.wwz.ai.domain.agent.runtime.enums.AgentType;
import org.wwz.ai.types.agent.user.UserRequestContext;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class DesktopControlResumeApplicationServiceTest {

    @After
    public void clearUserContext() {
        UserRequestContext.clear();
    }

    @Test
    public void deferredRuntimeFailureReleasesRunAndResetsControl() {
        DesktopControlRecord record = DesktopControlRecord.builder()
                .controlId("control-1")
                .resumeRequestId("resume-1")
                .sessionId("session-1")
                .userId("user-1")
                .status(DesktopControlStatuses.RESUME_PENDING)
                .resumeContextJson(UserQuestionResumeContext.toJson(
                        UserQuestionResumeContext.builder()
                                .agentType(AgentType.REACT.getValue())
                                .build()))
                .build();
        MemoryDesktopControlRepository repository = new MemoryDesktopControlRepository(record);
        ActiveAgentRunRegistry registry = new ActiveAgentRunRegistry();
        ConversationSessionAuthorizationService authorization =
                new ConversationSessionAuthorizationService(null, null, null) {
                    @Override
                    public DialogueSession ensureInteractiveSessionAccessible(String userId, String sessionId) {
                        return DialogueSession.builder().sessionId(sessionId).userId(userId).build();
                    }
                };
        DesktopControlResumeApplicationService service = new DesktopControlResumeApplicationService(
                repository,
                null,
                authorization,
                registry,
                null,
                new AgentRunLaunchGate(0),
                null,
                null,
                null);
        setField(service, "dispatchExecutor", null);
        setField(service, "handlerMap", Map.of());
        UserRequestContext.bind("user-1");

        AgentQuerySubmitResult result = service.resume("resume-1");

        Assert.assertTrue(result.isAccepted());
        Assert.assertEquals(DesktopControlStatuses.RESUME_PENDING, record.getStatus());
        Assert.assertTrue(registry.find("resume-1").isEmpty());
    }

    private static void setField(Object target, String name, Object value) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static final class MemoryDesktopControlRepository implements IDesktopControlRepository {
        private final DesktopControlRecord record;

        private MemoryDesktopControlRepository(DesktopControlRecord record) {
            this.record = record;
        }

        @Override
        public void insert(DesktopControlRecord ignored) {
        }

        @Override
        public Optional<DesktopControlRecord> findByControlId(String controlId) {
            return "control-1".equals(controlId) ? Optional.of(record) : Optional.empty();
        }

        @Override
        public Optional<DesktopControlRecord> findByResumeRequestId(String resumeRequestId) {
            return "resume-1".equals(resumeRequestId) ? Optional.of(record) : Optional.empty();
        }

        @Override
        public List<DesktopControlRecord> listOpenBySessionId(String sessionId) {
            return List.of(record);
        }

        @Override
        public boolean hasOpenBySessionId(String sessionId) {
            return true;
        }

        @Override
        public boolean casResumePending(String controlId, String userId, String resumeRequestId) {
            return false;
        }

        @Override
        public boolean casClaimResume(String resumeRequestId, String userId) {
            return "resume-1".equals(resumeRequestId) && "user-1".equals(userId);
        }

        @Override
        public boolean markCompleted(String controlId, String userId) {
            return false;
        }

        @Override
        public boolean markStatus(String controlId, String status) {
            if (!"control-1".equals(controlId)) {
                return false;
            }
            record.setStatus(status);
            return true;
        }

        @Override
        public boolean casCancel(String controlId, String userId) {
            return false;
        }
    }
}
