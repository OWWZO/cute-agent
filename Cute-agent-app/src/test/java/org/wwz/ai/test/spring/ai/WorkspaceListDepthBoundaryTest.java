package org.wwz.ai.test.spring.ai;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;
import org.wwz.ai.domain.agent.runtime.tool.skill.SkillRuntimeOptions;
import org.wwz.ai.domain.agent.runtime.tool.skill.SkillVirtualPaths;
import org.wwz.ai.domain.agent.runtime.tool.workspace.WorkspaceListTool;
import org.wwz.ai.domain.agent.runtime.tool.workspace.WorkspacePathGuard;
import org.wwz.ai.domain.agent.runtime.tool.workspace.WorkspaceRuntimeOptions;
import org.wwz.ai.domain.agent.runtime.tool.workspace.WorkspaceService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class WorkspaceListDepthBoundaryTest {

    @Test
    public void directoryAtMaxDepthIsReportedAsDirectory() throws Exception {
        Path workspaceRoot = Files.createTempDirectory("cute-workspace-list-boundary");
        Files.createDirectories(workspaceRoot.resolve("boundary-dir"));
        WorkspaceRuntimeOptions options = WorkspaceRuntimeOptions.builder()
                .enabled(true)
                .rootTemplate(workspaceRoot.toString())
                .build();
        WorkspaceService workspaceService = new WorkspaceService(
                options,
                new WorkspacePathGuard(),
                null,
                new SkillVirtualPaths(SkillRuntimeOptions.builder().enabled(false).build()));
        AgentContext context = AgentContext.builder()
                .requestId("req-workspace-list-boundary")
                .sessionId("session-workspace-list-boundary")
                .workspaceRoot(workspaceRoot.toString())
                .build();

        ToolResultPayload payload = (ToolResultPayload) new WorkspaceListTool(workspaceService, options)
                .execute(Map.of("path", workspaceRoot.toString(), "max_depth", 1), context);
        Map<?, ?> data = (Map<?, ?>) payload.getLlmData();
        List<?> entries = (List<?>) data.get("entries");
        Map<?, ?> boundary = entries.stream()
                .map(Map.class::cast)
                .filter(entry -> "boundary-dir".equals(entry.get("path")))
                .findFirst()
                .orElseThrow();

        Assert.assertEquals("DIR", boundary.get("type"));
    }
}
