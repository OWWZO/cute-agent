package org.wwz.ai.test.domain.planmode;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.planmode.PlanArtifactStore;
import org.wwz.ai.domain.agent.runtime.planmode.PlanModePromptInjector;
import org.wwz.ai.domain.agent.runtime.planmode.PlanModeState;
import org.wwz.ai.domain.agent.runtime.planmode.PlanModeToolPolicy;

import java.util.List;

/**
 * Plan Mode 的安全边界：写操作工具识别与绝对路径脱敏。
 */
public class PlanModeSecurityTest {

    @Test
    public void mutatingToolNamesCannotBypassWithCamelCaseOrShellNames() {
        PlanModeState state = PlanModeState.builder().build();
        state.enterPlanMode();

        for (String toolName : List.of(
                "runCommand", "removeFile", "uploadArtifact", "copy_file",
                "move_file", "createIssue", "shell", "powershell", "patchFile")) {
            Assert.assertNotNull("plan mode 必须拦住写操作工具: " + toolName,
                    PlanModeToolPolicy.denyReason(state, toolName, null));
        }
    }

    @Test
    public void readOnlyToolsStillPass() {
        PlanModeState state = PlanModeState.builder().build();
        state.enterPlanMode();

        for (String toolName : List.of(
                "web_search", "deep_search", "web_fetch", "read_editor_config", "get_updated_time")) {
            Assert.assertNull("只读工具不应被 plan mode 误伤: " + toolName,
                    PlanModeToolPolicy.denyReason(state, toolName, null));
        }
    }

    @Test
    public void absolutePlanPathsAreRedacted() {
        for (String absolute : List.of(
                "/data/ws/session-1/.cute/plan.md",
                "/opt/cute/workspaces/.cute/plan.md",
                "C:\\cute\\workspaces\\.cute\\plan.md",
                "//server/share/.cute/plan.md")) {
            String result = PlanModePromptInjector.buildApprovedPlanToolResult(
                    "plan body", absolute, "default");
            Assert.assertFalse("绝对路径不得进入模型可见结果: " + absolute,
                    result.contains(absolute.replace('\\', '/')));
            Assert.assertTrue(result.contains(PlanArtifactStore.RELATIVE_PLAN_PATH));
        }
    }

    @Test
    public void relativePlanPathIsPreserved() {
        String result = PlanModePromptInjector.buildApprovedPlanToolResult(
                "plan body", PlanArtifactStore.RELATIVE_PLAN_PATH, "default");
        Assert.assertTrue(result.contains(PlanArtifactStore.RELATIVE_PLAN_PATH));
    }
}
