package org.wwz.ai.test.domain.askuser;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.askuser.UserQuestionResumeContext;
import org.wwz.ai.domain.agent.runtime.command.AgentExecutionCommand;

/**
 * 续跑快照逐字段回退：request 中缺失的字段必须沿用 AgentContext 实际运行值。
 */
public class UserQuestionResumeContextTest {

    @Test
    public void missingRequestFieldsFallBackToContextPerField() {
        AgentContext context = AgentContext.builder()
                .requestId("req-1")
                .agentType(2)
                .model("model-from-context")
                .thinking(true)
                .thinkingEffort("high")
                .build();
        AgentExecutionCommand request = new AgentExecutionCommand();
        request.setUserId("user-1");
        // agentType/model/thinking/thinkingEffort 均未填写

        UserQuestionResumeContext resume = UserQuestionResumeContext.from(context, request, "react");

        Assert.assertEquals(Integer.valueOf(2), resume.getAgentType());
        Assert.assertEquals("model-from-context", resume.getModel());
        Assert.assertEquals(Boolean.TRUE, resume.getThinking());
        Assert.assertEquals("high", resume.getThinkingEffort());
        Assert.assertEquals("react", resume.getEntryAgent());
    }

    @Test
    public void requestValuesWinWhenPresent() {
        AgentContext context = AgentContext.builder()
                .requestId("req-2")
                .model("context-model")
                .build();
        AgentExecutionCommand request = new AgentExecutionCommand();
        request.setModel("request-model");

        Assert.assertEquals("request-model",
                UserQuestionResumeContext.from(context, request, "react").getModel());
    }
}
