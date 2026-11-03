package org.wwz.ai.test.domain.planmode;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;
import org.wwz.ai.domain.agent.runtime.tool.common.planmode.EnterPlanModeTool;
import org.wwz.ai.domain.agent.runtime.tool.common.planmode.ExitPlanModeTool;
import org.wwz.ai.domain.agent.runtime.tool.common.planmode.RequestDesktopControlTool;

import java.util.Map;

/**
 * 子 Agent 守卫必须读 AgentContext.isSubAgent()，而不是嗅探 requestId 字符串。
 */
public class PlanModeToolGuardTest {

    @Test
    public void enterPlanModeRejectsSubAgentContext() {
        AgentContext context = AgentContext.builder()
                .requestId("child-request")
                .subAgentId("agent-1")
                .build();

        ToolResultPayload payload = (ToolResultPayload) new EnterPlanModeTool().execute(Map.of(), context);

        Assert.assertTrue(payload.getFailed());
        Assert.assertTrue(payload.getToolResult().contains("子 Agent"));
    }

    @Test
    public void enterPlanModeDoesNotSniffRequestId() {
        AgentContext context = AgentContext.builder()
                .requestId("sub:agent:deadbeef")
                .build();

        ToolResultPayload payload = (ToolResultPayload) new EnterPlanModeTool().execute(Map.of(), context);

        Assert.assertFalse(payload.getFailed());
    }

    @Test
    public void desktopControlRejectsSubAgentContext() {
        AgentContext context = AgentContext.builder()
                .requestId("child-request")
                .subAgentId("agent-1")
                .build();

        ToolResultPayload payload = (ToolResultPayload) new RequestDesktopControlTool()
                .execute(Map.of("reason", "need help"), context);

        Assert.assertTrue(payload.getFailed());
        Assert.assertTrue(payload.getToolResult().contains("子 Agent"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void exitPlanModeSchemaDoesNotAdvertiseIgnoredPath() {
        Map<String, Object> params = new ExitPlanModeTool().toParams();
        Map<String, Object> properties = (Map<String, Object>) params.get("properties");

        Assert.assertTrue(properties.containsKey("plan"));
        Assert.assertFalse(properties.containsKey("planFilePath"));
    }
}
