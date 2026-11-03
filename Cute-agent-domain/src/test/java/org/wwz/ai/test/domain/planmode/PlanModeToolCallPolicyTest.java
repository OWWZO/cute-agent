package org.wwz.ai.test.domain.planmode;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.planmode.PlanModeState;
import org.wwz.ai.domain.agent.runtime.planmode.PlanModeToolPolicy;

import java.util.Map;

/**
 * ToolCall 只是转发器，plan 期必须按内层目标工具复检，否则写操作可以借桥绕过门禁。
 */
public class PlanModeToolCallPolicyTest {

    @Test
    public void toolCallCannotBypassPlanModeForMutatingTools() {
        PlanModeState state = planMode();

        Assert.assertNotNull("内层写工具必须被拦",
                deny(state, "workspace_write", Map.of("path", "src/Main.java")));
        Assert.assertNotNull("带副作用的命令工具必须被拦",
                deny(state, "bash", Map.of()));
        Assert.assertNotNull("目标名缺失时无法证明只读，必须拒绝",
                PlanModeToolPolicy.denyReason(state, "ToolCall", Map.of()));
        Assert.assertNotNull("ToolCall 不得嵌套调用桥工具",
                deny(state, "ToolCall", Map.of()));
    }

    @Test
    public void toolCallStillAllowsReadOnlyInnerTargets() {
        PlanModeState state = planMode();

        Assert.assertNull(deny(state, "workspace_read", Map.of("path", "src/Main.java")));
        Assert.assertNull("写计划文件仍是 plan 期唯一允许的写操作",
                deny(state, "workspace_write", Map.of("path", ".cute/plan.md")));
        Assert.assertNull("未知只读检索工具不受影响",
                deny(state, "web_search", Map.of("query", "x")));
    }

    private static PlanModeState planMode() {
        PlanModeState state = PlanModeState.builder().build();
        state.enterPlanMode();
        return state;
    }

    private static String deny(PlanModeState state, String target, Map<String, Object> targetArgs) {
        return PlanModeToolPolicy.denyReason(state, "ToolCall",
                Map.of("name", target, "arguments", targetArgs));
    }
}
