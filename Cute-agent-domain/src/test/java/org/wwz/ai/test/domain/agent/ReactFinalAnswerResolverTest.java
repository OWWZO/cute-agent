package org.wwz.ai.test.domain.agent;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.agent.ReActAgent;
import org.wwz.ai.domain.agent.runtime.agent.ReactFinalAnswerResolver;
import org.wwz.ai.domain.agent.runtime.dto.Message;
import org.wwz.ai.domain.agent.runtime.enums.AgentState;

/**
 * Finish[] 这类「有标记但无正文」的输出必须继续走兜底，而不是返回空终答。
 */
public class ReactFinalAnswerResolverTest {

    @Test
    public void emptyFinishMarkerFallsBackToMissingAnswer() {
        StubAgent agent = new StubAgent();
        agent.getMemory().addMessage(Message.assistantMessage("Finish[]", null));
        agent.setState(AgentState.FINISHED);

        String answer = ReactFinalAnswerResolver.resolve(agent, "Finish[]");

        Assert.assertEquals(ReactFinalAnswerResolver.MISSING_USER_FACING_ANSWER, answer);
    }

    @Test
    public void finishWithTextIsUnwrapped() {
        StubAgent agent = new StubAgent();
        agent.getMemory().addMessage(Message.assistantMessage("Finish[真实答案]", null));
        agent.setState(AgentState.FINISHED);

        Assert.assertEquals("真实答案", ReactFinalAnswerResolver.resolve(agent, null));
    }

    @Test
    public void previousTurnAnswerIsNotReusedWhenCurrentTurnHasNoFinalText() {
        StubAgent agent = new StubAgent();
        agent.getMemory().addMessage(Message.assistantMessage("上一轮答案", null));
        agent.getMemory().addMessage(Message.userMessage("新一轮问题", null));
        agent.setState(AgentState.FINISHED);

        Assert.assertEquals(
                ReactFinalAnswerResolver.MISSING_USER_FACING_ANSWER,
                ReactFinalAnswerResolver.resolve(agent, null)
        );
    }

    @Test
    public void sanitizeReturnsEmptyForBlankFinishBody() {
        Assert.assertEquals("", ReactFinalAnswerResolver.sanitize("Finish[]"));
        Assert.assertEquals("", ReactFinalAnswerResolver.sanitize("Finish[   ]"));
    }

    private static final class StubAgent extends ReActAgent {
        @Override
        public boolean think() {
            return false;
        }

        @Override
        public String act() {
            return "";
        }
    }
}
