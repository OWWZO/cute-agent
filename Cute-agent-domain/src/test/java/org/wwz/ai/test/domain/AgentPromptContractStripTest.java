package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.prompt.AgentPrompt;

/**
 * 面向用户终答契约必须能被整体剥离，不能因为契约内部的二级标题提前截断。
 */
public class AgentPromptContractStripTest {

    @Test
    public void userFacingContractRequiresMarkdownForFileLinks() {
        String contract = AgentPrompt.USER_FACING_REPLY_CONTRACT;

        Assert.assertTrue(contract.contains("图片使用 Markdown 图片"));
        Assert.assertTrue(contract.contains("HTML、PDF、文档等其他文件使用 Markdown 链接"));
        Assert.assertTrue(contract.contains("禁止用 `<img>` 嵌入非图片文件"));
    }

    @Test
    public void legacyCoordinatorContractIsRemovedWhenUserContractApplied() {
        String followedByAnotherSection = "# 角色\n你是助手。\n\n"
                + "# 面向协调者的回复约定 (COORDINATOR_FACING_REPLY_CONTRACT_V1)\n"
                + "- 状态：完成 | 部分完成 | 失败\n\n"
                + "# 附加规则\n不要泄漏内部实现。\n";

        String user = AgentPrompt.ensureUserFacingReplyContract(followedByAnotherSection);

        Assert.assertFalse("旧协调者契约必须被整体移除",
                user.contains("COORDINATOR_FACING_REPLY_CONTRACT_V1"));
        Assert.assertFalse("旧协调者回复结构必须被整体移除", user.contains("状态：完成"));
        Assert.assertTrue("契约之后的章节不能被一起吞掉", user.contains("# 附加规则"));
        Assert.assertTrue(user.contains(AgentPrompt.USER_FACING_REPLY_CONTRACT_MARKER));
    }
}
