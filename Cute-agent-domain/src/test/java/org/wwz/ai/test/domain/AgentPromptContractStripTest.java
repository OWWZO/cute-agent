package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.prompt.AgentPrompt;

/**
 * 面向用户终答契约必须能被整体剥离，不能因为契约内部的二级标题提前截断。
 */
public class AgentPromptContractStripTest {

    @Test
    public void coordinatorPromptDoesNotRetainUserFacingContractTail() {
        String withUserContract = AgentPrompt.ensureUserFacingReplyContract("# 角色\n你是助手。");
        String followedByAnotherSection = withUserContract + "\n# 附加规则\n不要泄漏内部实现。\n";

        String coordinator = AgentPrompt.ensureCoordinatorFacingReplyContract(followedByAnotherSection);

        Assert.assertFalse("用户侧语言/交付规则必须被整体移除",
                coordinator.contains("交付文件标记（必须）"));
        Assert.assertFalse(coordinator.contains("## 语言"));
        Assert.assertTrue("契约之后的章节不能被一起吞掉", coordinator.contains("# 附加规则"));
        Assert.assertTrue(coordinator.contains(AgentPrompt.COORDINATOR_FACING_REPLY_CONTRACT_MARKER));
    }
}
