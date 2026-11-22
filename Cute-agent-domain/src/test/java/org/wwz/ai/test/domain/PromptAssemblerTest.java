package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.prompt.AgentExecutionRole;
import org.wwz.ai.domain.agent.runtime.prompt.AgentPrompt;
import org.wwz.ai.domain.agent.runtime.prompt.PlanSolvePrompt;
import org.wwz.ai.domain.agent.runtime.prompt.PromptAssembler;
import org.wwz.ai.domain.agent.runtime.prompt.PromptAssemblyContext;
import org.wwz.ai.domain.agent.runtime.prompt.PromptBundle;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentDefinition;

import java.util.List;
import java.util.Set;

public class PromptAssemblerTest {

    @Test
    public void mainAgentAndWorkerShareIdentityAndUserContract() {
        PromptBundle main = PromptAssembler.assemble(PromptAssemblyContext.builder()
                .role(AgentExecutionRole.MAIN)
                .agentKey("data-analysis")
                .agentDirective("数据分析领域职责")
                .includeMemoryGuidance(false)
                .build());
        PromptBundle worker = PromptAssembler.assemble(PromptAssemblyContext.builder()
                .role(AgentExecutionRole.WORKER)
                .agentKey("data-analysis")
                .agentDirective("数据分析领域职责")
                .includeMemoryGuidance(false)
                .build());

        Assert.assertTrue(main.systemPrompt().contains(AgentPrompt.AGENT_CORE_RULES));
        Assert.assertTrue(worker.systemPrompt().contains(AgentPrompt.AGENT_CORE_RULES));
        Assert.assertTrue(main.systemPrompt().contains("数据分析领域职责"));
        Assert.assertTrue(worker.systemPrompt().contains("数据分析领域职责"));
        Assert.assertTrue(worker.systemPrompt().contains(AgentPrompt.USER_FACING_REPLY_CONTRACT_MARKER));
        Assert.assertFalse(worker.systemPrompt().contains("COORDINATOR_FACING_REPLY_CONTRACT_V1"));
    }

    @Test
    public void coordinatorHasCoreRoleAndSortedWorkerCatalog() {
        SubAgentDefinition research = definition("research", "深度研究");
        SubAgentDefinition code = definition("code", "代码任务");
        String catalog = PromptAssembler.formatWorkerCatalog(List.of(research, code));
        PromptBundle bundle = PromptAssembler.assemble(PromptAssemblyContext.builder()
                .role(AgentExecutionRole.COORDINATOR)
                .workerCatalog(catalog)
                .includeAgentDispatchGuidance(true)
                .includeMemoryGuidance(false)
                .build());

        Assert.assertTrue(bundle.systemPrompt().contains(AgentPrompt.AGENT_CORE_RULES));
        Assert.assertTrue(bundle.systemPrompt().contains(PlanSolvePrompt.ORCHESTRATION_MARKER));
        Assert.assertTrue(bundle.systemPrompt().contains("`code`: 代码任务"));
        Assert.assertTrue(bundle.systemPrompt().contains("`research`: 深度研究"));
        Assert.assertTrue(bundle.systemPrompt().indexOf("`code`") < bundle.systemPrompt().indexOf("`research`"));
        Assert.assertFalse(bundle.systemPrompt().contains("完整系统提示词"));
    }

    @Test
    public void sameInputProducesStableHashAndSectionOrder() {
        PromptAssemblyContext context = PromptAssemblyContext.builder()
                .role(AgentExecutionRole.MAIN)
                .agentKey("general-purpose")
                .agentDirective("通用领域职责")
                .includeMemoryGuidance(false)
                .build();

        PromptBundle first = PromptAssembler.assemble(context);
        PromptBundle second = PromptAssembler.assemble(context);

        Assert.assertEquals(first.systemPrompt(), second.systemPrompt());
        Assert.assertEquals(first.promptHash(), second.promptHash());
        Assert.assertEquals(first.sectionNames(), second.sectionNames());
    }

    @Test
    public void disabledCapabilitiesDoNotLeakGuidance() {
        PromptBundle bundle = PromptAssembler.assemble(PromptAssemblyContext.builder()
                .role(AgentExecutionRole.MAIN)
                .agentDirective("通用领域职责")
                .includeMemoryGuidance(false)
                .includeWorkspaceGuidance(false)
                .includeBackgroundTaskGuidance(false)
                .includeParallelToolGuidance(false)
                .includeAgentDispatchGuidance(false)
                .build());

        Assert.assertFalse(bundle.systemPrompt().contains("# 记忆规则"));
        Assert.assertFalse(bundle.systemPrompt().contains("# 共享工作区"));
        Assert.assertFalse(bundle.systemPrompt().contains("# 后台任务"));
        Assert.assertFalse(bundle.systemPrompt().contains("# 并行工具调用"));
        Assert.assertFalse(bundle.systemPrompt().contains("# Agent 调度"));
    }

    @Test
    public void coreRulesStayShort() {
        int length = AgentPrompt.AGENT_CORE_RULES.codePointCount(
                0, AgentPrompt.AGENT_CORE_RULES.length());
        Assert.assertTrue("核心规则过短", length >= 200);
        Assert.assertTrue("核心规则超过 900 字符", length <= 900);
    }

    private static SubAgentDefinition definition(String key, String whenToUse) {
        return SubAgentDefinition.builder()
                .agentType(key)
                .whenToUse(whenToUse)
                .systemPrompt("完整系统提示词不应进入目录")
                .allowedTools(Set.of("*"))
                .build();
    }
}
