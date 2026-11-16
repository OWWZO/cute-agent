package org.wwz.ai.test.domain;

import com.alibaba.fastjson.JSON;
import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.CuteRuntimeDependencies;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.tool.codemode.CodeModeExecutorPort;
import org.wwz.ai.domain.agent.runtime.tool.codemode.CodeModeRequest;
import org.wwz.ai.domain.agent.runtime.tool.codemode.CodeModeResult;
import org.wwz.ai.domain.agent.runtime.tool.codemode.CodeModeTool;
import org.wwz.ai.types.agent.config.CodeModeProperties;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class CodeModeToolIntentTest {

    @Test
    public void descriptionTeachesWorkspaceObjectArguments() {
        String description = new CodeModeTool(null).getDescription();
        Assert.assertTrue(description.contains("writeText({ path, content })"));
        Assert.assertTrue(description.contains("writeBytes({ path, base64 })"));
        Assert.assertTrue(description.contains("writeCsv({ path, columns, rows })"));
    }

    @Test
    public void intentDoesNotEnterCodeModeRequest() {
        AtomicReference<CodeModeRequest> captured = new AtomicReference<>();
        CodeModeExecutorPort executor = (request, context) -> {
            captured.set(request);
            return CodeModeResult.success(Map.of("ok", true), List.of(), 1, List.of(), 0, null);
        };
        CodeModeProperties properties = new CodeModeProperties();
        CuteRuntimeDependencies dependencies = CuteRuntimeDependencies.builder()
                .codeModeProperties(properties)
                .codeModeExecutorPort(executor)
                .build();
        AgentContext context = AgentContext.builder()
                .requestId("req-code-mode-intent")
                .sessionId("session-code-mode-intent")
                .runtimeDependencies(dependencies)
                .build();

        new CodeModeTool(properties).execute(Map.of(
                "intent", "批量读取商品页库存",
                "source", "async function main() { return { ok: true }; }"
        ), context);

        Assert.assertNotNull(captured.get());
        Assert.assertFalse(JSON.toJSONString(captured.get()).contains("\"intent\""));
    }
}
