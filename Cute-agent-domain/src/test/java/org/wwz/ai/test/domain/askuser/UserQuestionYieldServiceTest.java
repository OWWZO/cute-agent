package org.wwz.ai.test.domain.askuser;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.memory.SessionWorkingMemoryService;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.askuser.IUserQuestionRepository;
import org.wwz.ai.domain.agent.runtime.askuser.UserInputRequiredException;
import org.wwz.ai.domain.agent.runtime.askuser.UserQuestionYieldService;
import org.wwz.ai.domain.agent.runtime.yieldcard.AfterCommitCardSender;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 无法定位 toolCallId 时必须快速失败，不能落库一条不可续跑的问询。
 */
public class UserQuestionYieldServiceTest {

    @Test
    public void missingToolCallIdFailsBeforePersistingQuestion() {
        IUserQuestionRepository repository = mock(IUserQuestionRepository.class);
        UserQuestionYieldService service = new UserQuestionYieldService(
                repository,
                mock(SessionWorkingMemoryService.class),
                mock(AfterCommitCardSender.class));
        AgentContext context = AgentContext.builder()
                .requestId("req-1")
                .sessionId("s-1")
                .build();
        UserInputRequiredException signal = new UserInputRequiredException(
                List.of(java.util.Map.of("question", "继续吗", "header", "确认")), null);

        try {
            service.yieldAndNotify(context, null, null, signal, "react");
            Assert.fail("缺少 toolCallId 必须失败");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("toolCallId"));
        }

        verify(repository, never()).insertIfAbsent(any());
    }
}
