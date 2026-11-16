package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.tool.workspace.WorkspaceReadTool;

public class WorkspaceReadToolDescriptionTest {

    @Test
    public void descriptionRequiresWorkspaceReadForImageInspection() {
        String description = new WorkspaceReadTool(null, null).getDescription();

        Assert.assertTrue(description.contains(
                "需要查看截图/图片内容时，必须调用 workspace_read 读取图片路径；不要用 shell 代替。"));
    }
}
