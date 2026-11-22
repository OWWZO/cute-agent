package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.prompt.AgentPrompt;
import org.wwz.ai.domain.agent.runtime.prompt.PlanSolvePrompt;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentDefinition;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentRegistry;

public class PlanSolvePromptOrchestrationTest {

    @Test
    public void ensureOrchestrationIsIdempotentAndUsesV6() {
        String once = PlanSolvePrompt.ensureOrchestration("");
        String twice = PlanSolvePrompt.ensureOrchestration(once);
        Assert.assertEquals(once, twice);
        Assert.assertTrue(once.contains(PlanSolvePrompt.ORCHESTRATION_MARKER));
        Assert.assertTrue(once.contains("规模门控"));
        Assert.assertTrue(once.contains("## 7. Plan Mode"));
        Assert.assertTrue(once.contains("EnterPlanMode"));
        Assert.assertTrue(once.contains("只用于启动新 Worker"));
        Assert.assertTrue(once.contains("不要传 resume_agent_id"));
        Assert.assertTrue(once.contains("禁止用 workspace_list 轮询子 Agent 是否完成"));
        Assert.assertTrue(once.contains("已结束/失败/停止时后台唤醒"));
        Assert.assertTrue(once.contains("后台结果自动回传"));
        Assert.assertFalse(once.contains("TaskOutput"));
        Assert.assertEquals(1, once.split(PlanSolvePrompt.ORCHESTRATION_MARKER, -1).length - 1);
    }

    @Test
    public void ensureOrchestrationReplacesLegacyV3Block() {
        String legacy = "# Plan-Execute 主代理职责 (PLAN_SOLVE_ORCHESTRATION_V3)\n- old\n";
        String upgraded = PlanSolvePrompt.ensureOrchestration(legacy);
        Assert.assertTrue(upgraded.contains(PlanSolvePrompt.ORCHESTRATION_MARKER));
        Assert.assertFalse(upgraded.contains("PLAN_SOLVE_ORCHESTRATION_V3"));
        Assert.assertTrue(upgraded.contains("协调者"));
    }

    @Test
    public void ensureOrchestrationReplacesLegacyV5Block() {
        String legacy = "# Plan-Execute 主代理职责 (PLAN_SOLVE_ORCHESTRATION_V5)\n- 续跑用 resume_agent_id / SendMessage\n";
        String upgraded = PlanSolvePrompt.ensureOrchestration(legacy);
        Assert.assertTrue(upgraded.contains(PlanSolvePrompt.ORCHESTRATION_MARKER));
        Assert.assertFalse(upgraded.contains("PLAN_SOLVE_ORCHESTRATION_V5"));
        Assert.assertTrue(upgraded.contains("不要传 resume_agent_id"));
    }

    @Test
    public void legacyMarkerWithoutOwnHeadingDoesNotCutCurrentBlock() {
        String current = PlanSolvePrompt.ensureOrchestration("");
        String poisoned = current
                + "\nimportant-keep-this PLAN_SOLVE_ORCHESTRATION_V3 mention\n"
                + "# Appendix\nkeep\n";

        String result = PlanSolvePrompt.ensureOrchestration(poisoned);

        Assert.assertTrue("无关内容不得被 legacy 清理连带删除", result.contains("important-keep-this"));
        Assert.assertFalse(result.contains("PLAN_SOLVE_ORCHESTRATION_V3"));
        Assert.assertTrue(result.contains(PlanSolvePrompt.ORCHESTRATION_MARKER));
    }

    @Test
    public void toolExampleUsesDoubleQuotedJson() {
        Assert.assertFalse(AgentPrompt.STRUCT_PARSE_TOOL_SYSTEM_PROMPT.contains("''name''"));
        Assert.assertTrue(AgentPrompt.STRUCT_PARSE_TOOL_SYSTEM_PROMPT.contains("\"name\": \"deep_search\""));
    }

    @Test
    public void planSolveBaseIsOrchestrationNotReact() {
        String plan = AgentPrompt.composePlanSolveSystemPrompt();
        Assert.assertTrue(plan.contains(AgentPrompt.AGENT_CORE_RULES));
        Assert.assertTrue(plan.contains(PlanSolvePrompt.ORCHESTRATION_MARKER));
        Assert.assertTrue(plan.contains(AgentPrompt.USER_FACING_REPLY_CONTRACT_MARKER));
        Assert.assertFalse(plan.contains("你是 Cute，专注深度调研与数据分析"));
        Assert.assertTrue(plan.contains("# Plan-Execute 主代理职责"));
    }

    @Test
    public void subAgentSharesAgentIdentityAndKeepsUserFacingReplyContract() {
        String sub = AgentPrompt.composeSubAgentSystemPrompt("专属指令");
        Assert.assertTrue(sub.contains(AgentPrompt.AGENT_CORE_RULES));
        Assert.assertFalse(sub.contains("COORDINATOR_FACING_REPLY_CONTRACT_V1"));
        Assert.assertFalse(sub.contains("# 面向协调者的回复约定"));
        Assert.assertTrue(sub.contains(AgentPrompt.USER_FACING_REPLY_CONTRACT_MARKER));
        Assert.assertTrue(sub.contains("专属指令"));
        Assert.assertFalse(sub.contains("执行型子代理（Worker）"));
    }

    @Test
    public void reactBaseKeepsUserFacingWhenEnsured() {
        String react = AgentPrompt.ensureUserFacingReplyContract(AgentPrompt.REACT_SYSTEM_PROMPT);
        Assert.assertTrue(react.contains(AgentPrompt.USER_FACING_REPLY_CONTRACT_MARKER));
        Assert.assertFalse(react.contains("COORDINATOR_FACING_REPLY_CONTRACT_V1"));
        Assert.assertFalse(react.contains(PlanSolvePrompt.ORCHESTRATION_MARKER));
    }

    @Test
    public void generalPurposeDirectiveIsTypeSupplementOnly() {
        SubAgentRegistry registry = new SubAgentRegistry();
        SubAgentDefinition def = registry.require(SubAgentRegistry.TYPE_GENERAL_PURPOSE);
        Assert.assertTrue(def.getSystemPrompt().contains("通用执行补充"));
        Assert.assertTrue(def.getWhenToUse().contains("检索"));
    }
}
