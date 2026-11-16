package org.wwz.ai.test.domain;

import com.alibaba.fastjson.JSON;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.wwz.ai.domain.agent.adapter.port.RemoteHttpPort;
import org.wwz.ai.domain.agent.cute.config.CuteConfig;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;
import org.wwz.ai.domain.agent.runtime.tool.common.shell.BashTool;
import org.wwz.ai.domain.agent.runtime.tool.skill.SkillRuntimeOptions;
import org.wwz.ai.domain.agent.runtime.tool.skill.SkillVirtualPaths;
import org.wwz.ai.test.domain.support.CuteRuntimeTestSupport;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class BashToolIntentTest {

    @Test
    public void intentDoesNotEnterSandboxRequest() {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        RemoteHttpPort httpPort = request -> {
            capturedBody.set(request.getBody());
            return """
                    {"exitCode":0,"stdout":"ok","stderr":"","truncated":false,"timedOut":false,"durationMs":1}
                    """;
        };
        CuteConfig config = new CuteConfig();
        ReflectionTestUtils.setField(config, "codeInterpreterUrl", "http://cute-tool");
        AgentContext context = AgentContext.builder()
                .requestId("req-bash-intent")
                .sessionId("session-bash-intent")
                .runtimeDependencies(CuteRuntimeTestSupport.runtimeDependencies(config, httpPort))
                .build();
        SkillRuntimeOptions skillOptions = SkillRuntimeOptions.builder().enabled(false).build();
        BashTool tool = new BashTool(skillOptions, new SkillVirtualPaths(skillOptions));

        ToolResultPayload payload = (ToolResultPayload) tool.execute(Map.of(
                "intent", "确认 bash 只发送命令本身",
                "command", "echo ok"
        ), context);

        Assert.assertFalse(Boolean.TRUE.equals(payload.getFailed()));
        Assert.assertFalse(JSON.parseObject(capturedBody.get()).containsKey("intent"));
    }
}
