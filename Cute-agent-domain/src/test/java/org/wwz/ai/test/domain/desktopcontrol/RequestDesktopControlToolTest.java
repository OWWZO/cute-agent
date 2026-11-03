package org.wwz.ai.test.domain.desktopcontrol;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.CuteRuntimeDependencies;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.desktopcontrol.DesktopControlRequiredException;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;
import org.wwz.ai.domain.agent.runtime.tool.common.planmode.RequestDesktopControlTool;
import org.wwz.ai.types.agent.user.UserRequestContext;

import java.util.Map;

public class RequestDesktopControlToolTest {

    @Test
    public void missingContextDoesNotThrowYield() {
        RequestDesktopControlTool tool = new RequestDesktopControlTool();
        try {
            Object result = tool.execute(Map.of("reason", "请登录"), null);
            Assert.assertTrue(result instanceof ToolResultPayload);
            Assert.assertEquals(Boolean.TRUE, ((ToolResultPayload) result).getFailed());
        } catch (DesktopControlRequiredException e) {
            Assert.fail("should not yield without context");
        }
    }

    @Test
    public void anonymousOwnerIsRejectedBeforeOpeningSharedDesktop() {
        UserRequestContext.clear();
        AgentContext context = AgentContext.builder()
                .requestId("req-anonymous")
                .runtimeDependencies(CuteRuntimeDependencies.builder().build())
                .build();

        Object result;
        try {
            result = new RequestDesktopControlTool().execute(Map.of("reason", "请登录"), context);
        } finally {
            UserRequestContext.clear();
        }

        Assert.assertTrue(result instanceof ToolResultPayload);
        ToolResultPayload payload = (ToolResultPayload) result;
        Assert.assertEquals(Boolean.TRUE, payload.getFailed());
        Assert.assertTrue("无真实 owner 时必须快速失败，实际: " + payload.getErrorMsg(),
                payload.getErrorMsg().contains("无法识别当前用户"));
    }
}
