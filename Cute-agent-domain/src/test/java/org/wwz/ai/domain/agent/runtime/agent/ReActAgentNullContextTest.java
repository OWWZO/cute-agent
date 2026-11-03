package org.wwz.ai.domain.agent.runtime.agent;

import org.junit.Test;

/**
 * context 尚未装配（null）时，数字员工生成的错误日志不能反而抛 NPE。
 */
public class ReActAgentNullContextTest {

    @Test
    public void emptyTaskShouldReturnWithoutContext() {
        ProbeAgent agent = new ProbeAgent();
        agent.setContext(null);

        agent.generateDigitalEmployee("");

        // 走到这里即证明空任务守卫没有解引用 null context。
    }

    @Test
    public void missingPromptShouldBeLoggedWithoutMaskingFailure() {
        ProbeAgent agent = new ProbeAgent();
        agent.setContext(null);
        agent.setDigitalEmployeePrompt(null);

        agent.generateDigitalEmployee("生成市场洞察专员");

        // 内部会抛 "System prompt is not configured" 并被 catch 记日志；
        // 修复前 catch 里的 context.getRequestId() 会把原异常替换成 NPE 抛出。
    }

    private static final class ProbeAgent extends ReActAgent {

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
