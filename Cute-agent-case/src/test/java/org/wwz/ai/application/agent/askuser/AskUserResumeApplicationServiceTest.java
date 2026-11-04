package org.wwz.ai.application.agent.askuser;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.application.agent.authorization.ConversationSessionAuthorizationService;
import org.wwz.ai.application.agent.query.AgentQuerySubmitResult;
import org.wwz.ai.application.agent.run.AgentRunLaunchGate;
import org.wwz.ai.domain.agent.ledger.entity.DialogueSession;
import org.wwz.ai.domain.agent.runtime.askuser.IUserQuestionRepository;
import org.wwz.ai.domain.agent.runtime.askuser.UserQuestionRecord;
import org.wwz.ai.domain.agent.runtime.askuser.UserQuestionResumeContext;
import org.wwz.ai.domain.agent.runtime.askuser.UserQuestionStatuses;
import org.wwz.ai.domain.agent.runtime.cancel.ActiveAgentRunRegistry;
import org.wwz.ai.domain.agent.runtime.enums.AgentType;
import org.wwz.ai.types.agent.user.UserRequestContext;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class AskUserResumeApplicationServiceTest {

    @After
    public void clearUserContext() {
        UserRequestContext.clear();
    }

    @Test
    public void corruptResumeContextResetsRecordToPending() {
        UserQuestionRecord record = record("{not-json");
        MemoryQuestionRepository repository = new MemoryQuestionRepository(record);
        ActiveAgentRunRegistry registry = new ActiveAgentRunRegistry();
        AskUserResumeApplicationService service = service(repository, registry, null);
        UserRequestContext.bind("user-1");

        try {
            service.resume("resume-1");
            Assert.fail("损坏的续跑上下文必须抛出异常");
        } catch (RuntimeException expected) {
            Assert.assertNotNull(expected.getMessage());
        }

        Assert.assertEquals(UserQuestionStatuses.RESUME_PENDING, record.getStatus());
        Assert.assertTrue(registry.find("resume-1").isEmpty());
    }

    @Test
    public void deferredRuntimeFailureReleasesRunAndResetsQuestion() {
        UserQuestionRecord record = record(UserQuestionResumeContext.toJson(
                UserQuestionResumeContext.builder()
                        .agentType(AgentType.REACT.getValue())
                        .build()));
        MemoryQuestionRepository repository = new MemoryQuestionRepository(record);
        ActiveAgentRunRegistry registry = new ActiveAgentRunRegistry();
        AskUserResumeApplicationService service = service(repository, registry, null);
        UserRequestContext.bind("user-1");

        AgentQuerySubmitResult result = service.resume("resume-1");

        Assert.assertTrue(result.isAccepted());
        Assert.assertEquals(UserQuestionStatuses.RESUME_PENDING, record.getStatus());
        Assert.assertTrue(registry.find("resume-1").isEmpty());
    }

    private static AskUserResumeApplicationService service(MemoryQuestionRepository repository,
                                                           ActiveAgentRunRegistry registry,
                                                           java.util.concurrent.Executor executor) {
        ConversationSessionAuthorizationService authorization =
                new ConversationSessionAuthorizationService(null, null, null) {
                    @Override
                    public DialogueSession ensureInteractiveSessionAccessible(String userId, String sessionId) {
                        return DialogueSession.builder().sessionId(sessionId).userId(userId).build();
                    }
                };
        AskUserResumeApplicationService service = new AskUserResumeApplicationService(
                repository,
                null,
                authorization,
                registry,
                null,
                new AgentRunLaunchGate(0),
                null,
                null);
        setField(service, "dispatchExecutor", executor);
        setField(service, "handlerMap", Map.of());
        return service;
    }

    private static UserQuestionRecord record(String resumeContextJson) {
        return UserQuestionRecord.builder()
                .questionId("question-1")
                .resumeRequestId("resume-1")
                .sessionId("session-1")
                .userId("user-1")
                .status(UserQuestionStatuses.RESUME_PENDING)
                .resumeContextJson(resumeContextJson)
                .build();
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

    private static final class MemoryQuestionRepository implements IUserQuestionRepository {
        private final UserQuestionRecord record;

        private MemoryQuestionRepository(UserQuestionRecord record) {
            this.record = record;
        }

        @Override
        public boolean insertIfAbsent(UserQuestionRecord ignored) {
            return false;
        }

        @Override
        public Optional<UserQuestionRecord> findByQuestionId(String questionId) {
            return "question-1".equals(questionId) ? Optional.of(record) : Optional.empty();
        }

        @Override
        public Optional<UserQuestionRecord> findByResumeRequestId(String resumeRequestId) {
            return "resume-1".equals(resumeRequestId) ? Optional.of(record) : Optional.empty();
        }

        @Override
        public List<UserQuestionRecord> listOpenBySessionId(String sessionId) {
            return List.of(record);
        }

        @Override
        public boolean hasOpenBySessionId(String sessionId) {
            return true;
        }

        @Override
        public boolean casAnswerPending(String questionId, String userId,
                                        Map<String, String> answers, String resumeRequestId) {
            return false;
        }

        @Override
        public boolean casClaimResume(String resumeRequestId, String userId) {
            return "resume-1".equals(resumeRequestId) && "user-1".equals(userId);
        }

        @Override
        public boolean markStatus(String questionId, String status) {
            if (!"question-1".equals(questionId)) {
                return false;
            }
            record.setStatus(status);
            return true;
        }

        @Override
        public boolean casCancel(String questionId, String userId) {
            return false;
        }
    }
}
