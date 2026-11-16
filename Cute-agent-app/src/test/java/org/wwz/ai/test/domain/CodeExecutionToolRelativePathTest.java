package org.wwz.ai.test.domain;

import com.alibaba.fastjson.JSON;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.wwz.ai.domain.agent.adapter.port.RemoteHttpPort;
import org.wwz.ai.domain.agent.cute.config.CuteConfig;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.artifact.ToolArtifactSource;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;
import org.wwz.ai.domain.agent.runtime.tool.common.CodeExecutionTool;
import org.wwz.ai.test.domain.support.CuteRuntimeTestSupport;

import java.util.Map;

public class CodeExecutionToolRelativePathTest {

    @Test
    public void shouldPreserveRelativePathForGeneratedFile() {
        RemoteHttpPort httpPort = request -> """
                {"status":"ok","stdout":"chart created","stderr":"","result":null,
                 "fileInfo":[{"fileName":"chart.png","relativePath":"test-page/screens/chart.png",
                 "domainUrl":"https://file.example.com/preview/chart.png",
                 "ossUrl":"https://file.example.com/download/chart.png","fileSize":42}],
                 "workspaceSync":{"backend":"e2b","status":"ok","deletedLocal":["tmp/old.txt"]},
                 "deletedFiles":[{"relativePath":"tmp/old.txt","status":"synced"}]}
                """;
        CuteConfig config = new CuteConfig();
        ReflectionTestUtils.setField(config, "codeInterpreterUrl", "http://cute-tool");
        AgentContext context = AgentContext.builder()
                .requestId("req-code-relative")
                .sessionId("session-code-relative")
                .userId("user-code-relative")
                .runtimeDependencies(CuteRuntimeTestSupport.runtimeDependencies(config, httpPort))
                .build();
        ToolArtifactSource artifactSource = ToolArtifactSource.builder()
                .sessionId(context.getSessionId())
                .requestId(context.getRequestId())
                .toolCallId("call-code-relative")
                .toolName("code_execution")
                .build();

        ToolResultPayload payload;
        context.bindCurrentToolArtifactSource(artifactSource);
        try {
            payload = (ToolResultPayload) new CodeExecutionTool()
                    .execute(Map.of("source", "result = 1"), context);
        } finally {
            context.clearCurrentToolArtifactSource();
        }

        Assert.assertFalse(payload.getFailed());
        Assert.assertEquals("test-page/screens/chart.png",
                context.getVisibleArtifactFiles().get(0).getRelativePath());
        Assert.assertTrue(JSON.toJSONString(payload.getLlmData())
                .contains("\"relative_path\":\"test-page/screens/chart.png\""));
        Assert.assertTrue(JSON.toJSONString(payload.getLlmData())
                .contains("\"workspace_sync\""));
        Assert.assertTrue(JSON.toJSONString(payload.getLlmData())
                .contains("\"deleted_files\""));
    }
}
