package org.wwz.ai.test.domain.planmode;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.planmode.PendingPlanApproval;
import org.wwz.ai.domain.agent.runtime.planmode.PlanModeState;
import org.wwz.ai.domain.agent.runtime.planmode.PlanModeToolPolicy;

import java.util.Map;

/**
 * Plan mode 路径门禁回归：修复前 {@code endsWith(".cute/plan.md")} 允许在任意子目录写同名文件，
 * 也允许绝对路径与 {@code ..} 绕行。
 */
public class PlanModePathGuardTest {

    private PlanModeState planMode() {
        PlanModeState state = PlanModeState.builder().build();
        state.enterPlanMode();
        return state;
    }

    @Test
    public void allowsOnlyWorkspaceRootPlanFile() {
        PlanModeState state = planMode();
        Assert.assertNull(PlanModeToolPolicy.denyReason(state, "workspace_write",
                Map.of("path", ".cute/plan.md")));
        Assert.assertNull(PlanModeToolPolicy.denyReason(state, "workspace_write",
                Map.of("path", "./.cute/plan.md")));
        Assert.assertNull(PlanModeToolPolicy.denyReason(state, "workspace_write",
                Map.of("path", ".cute\\plan.md")));
    }

    @Test
    public void rejectsLookalikeAndEscapingPaths() {
        PlanModeState state = planMode();
        assertDenied(state, "foo.cute/plan.md");
        assertDenied(state, "sub/.cute/plan.md");
        assertDenied(state, "/other/.cute/plan.md");
        assertDenied(state, "C:/other/.cute/plan.md");
        assertDenied(state, ".cute/../../x/.cute/plan.md");
        assertDenied(state, "../.cute/plan.md");
    }

    @Test
    public void clientPayloadExposesLogicalPlanPathOnly() {
        PendingPlanApproval approval = PendingPlanApproval.builder()
                .planFilePath("/srv/app/workspaces/sess-1/.cute/plan.md")
                .build();
        Object exposed = approval.toClientPayload().get("planFilePath");
        Assert.assertEquals(".cute/plan.md", exposed);
    }

    private void assertDenied(PlanModeState state, String path) {
        Assert.assertNotNull("应拒绝路径: " + path,
                PlanModeToolPolicy.denyReason(state, "workspace_write", Map.of("path", path)));
    }
}
