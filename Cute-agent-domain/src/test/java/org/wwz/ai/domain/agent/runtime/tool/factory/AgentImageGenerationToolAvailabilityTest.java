package org.wwz.ai.domain.agent.runtime.tool.factory;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.cute.config.CuteConfig;
import org.wwz.ai.domain.agent.runtime.CuteRuntimeDependencies;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.command.AgentExecutionCommand;
import org.wwz.ai.domain.agent.runtime.tool.ToolCollection;
import org.wwz.ai.domain.agent.runtime.tool.mcp.port.McpToolExecutor;
import org.wwz.ai.domain.agent.runtime.tool.workspace.WorkspacePathGuard;
import org.wwz.ai.domain.agent.runtime.tool.workspace.WorkspaceRuntimeOptions;
import org.wwz.ai.domain.agent.runtime.tool.workspace.WorkspaceService;
import org.wwz.ai.domain.agent.runtime.tool.skill.SkillRuntimeOptions;
import org.wwz.ai.domain.agent.runtime.tool.skill.DefaultSkillRegistry;
import org.wwz.ai.domain.agent.runtime.tool.skill.SkillMarkdownParser;
import org.wwz.ai.domain.agent.runtime.tool.skill.SkillPathGuard;
import org.wwz.ai.domain.agent.runtime.tool.skill.SkillScriptDiscoverer;
import org.wwz.ai.domain.agent.runtime.tool.skill.SkillVirtualPaths;
import org.wwz.ai.types.agent.config.ImageGenerationProperties;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentRegistry;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentRunner;
import org.mockito.Mockito;

public class AgentImageGenerationToolAvailabilityTest {

    @Test
    public void imageGenerationToolIsNotRegisteredWithoutConfiguredCredentials() {
        CuteConfig cuteConfig = new CuteConfig();
        cuteConfig.setMultiAgentToolList("{\"default\":\"image_generation\"}");

        AgentContext context = AgentContext.builder()
                .requestId("req-image-config")
                .sessionId("session-image-config")
                .query("tool availability")
                .runtimeDependencies(CuteRuntimeDependencies.builder()
                        .imageGenerationProperties(ImageGenerationProperties.builder().build())
                        .subAgentRegistry(new SubAgentRegistry())
                        .build())
                .build();
        AgentExecutionCommand request = AgentExecutionCommand.builder()
                .requestId("req-image-config")
                .sessionId("session-image-config")
                .query("tool availability")
                .build();

        ToolCollection tools = new AgentToolCollectionFactory(
                cuteConfig, Mockito.mock(McpToolExecutor.class),
                new DefaultSkillRegistry(
                        SkillRuntimeOptions.builder().enabled(false).build(),
                        new SkillMarkdownParser(),
                        new SkillScriptDiscoverer(new SkillPathGuard()),
                        new SkillPathGuard()),
                null, null,
                SkillRuntimeOptions.builder().enabled(false).build(), null, null,
                new WorkspaceService(
                        WorkspaceRuntimeOptions.builder().enabled(false).build(),
                        new WorkspacePathGuard(),
                        null,
                        new SkillVirtualPaths(SkillRuntimeOptions.builder().enabled(false).build())),
                WorkspaceRuntimeOptions.builder().enabled(false).build(),
                null)
                .buildForReact(context, request);

        Assert.assertFalse(tools.getToolMap().containsKey("image_generation_tool"));
    }
}
