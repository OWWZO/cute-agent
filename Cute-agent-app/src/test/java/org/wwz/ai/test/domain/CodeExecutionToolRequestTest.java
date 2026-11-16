package org.wwz.ai.test.domain;

import com.alibaba.fastjson.JSON;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.wwz.ai.domain.agent.adapter.port.RemoteHttpPort;
import org.wwz.ai.domain.agent.cute.config.CuteConfig;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.artifact.ToolArtifactSource;
import org.wwz.ai.domain.agent.runtime.tool.common.CodeExecutionTool;
import org.wwz.ai.test.domain.support.CuteRuntimeTestSupport;

import java.util.Map;

public class CodeExecutionToolRequestTest {

    @Test
    public void workspaceFileRequestAlwaysIncludesNonBlankStringSource() {
        java.util.concurrent.atomic.AtomicReference<String> capturedBody = new java.util.concurrent.atomic.AtomicReference<>();
        RemoteHttpPort httpPort = request -> {
            capturedBody.set(request.getBody());
            return """
                    {"status":"ok","stdout":"","stderr":"","result":null,"fileInfo":[]}
                    """;
        };
        CuteConfig config = new CuteConfig();
        ReflectionTestUtils.setField(config, "codeInterpreterUrl", "http://cute-tool");
        AgentContext context = AgentContext.builder()
                .requestId("req-code-workspace-file")
                .sessionId("session-code-workspace-file")
                .runtimeDependencies(CuteRuntimeTestSupport.runtimeDependencies(config, httpPort))
                .build();
        ToolArtifactSource artifactSource = ToolArtifactSource.builder()
                .sessionId(context.getSessionId())
                .requestId(context.getRequestId())
                .toolCallId("call-code-workspace-file")
                .toolName("code_execution")
                .build();

        context.bindCurrentToolArtifactSource(artifactSource);
        try {
            new CodeExecutionTool().execute(Map.of(
                    "intent", "运行工作区脚本并生成结果文件",
                    "workspaceFile", "scripts/run.py"
            ), context);
        } finally {
            context.clearCurrentToolArtifactSource();
        }

        com.alibaba.fastjson.JSONObject request = JSON.parseObject(capturedBody.get());
        Assert.assertTrue("source 必须是必填字段", request.containsKey("source"));
        Assert.assertTrue("source 必须是字符串", request.get("source") instanceof String);
        Assert.assertFalse("source 必须满足远端 min_length=1", request.getString("source").isBlank());
        Assert.assertEquals("scripts/run.py", request.getString("workspaceFile"));
        Assert.assertFalse("intent 只属于工具调用元数据，不能传给沙箱", request.containsKey("intent"));
    }
}
