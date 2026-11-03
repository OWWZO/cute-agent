package org.wwz.ai.test.domain.tool.common.shell;

import org.junit.Assert;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.wwz.ai.domain.agent.adapter.port.RemoteHttpPort;
import org.wwz.ai.domain.agent.cute.config.CuteConfig;
import org.wwz.ai.domain.agent.runtime.CuteRuntimeDependencies;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;
import org.wwz.ai.domain.agent.runtime.tool.common.shell.BashTool;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * bash 沙箱的非零退出/超时是业务结果，但 ok 必须为 false，不能告诉模型「执行成功」。
 */
public class BashToolExitStatusTest {

    @Test
    public void nonZeroExitIsReportedAsNotOk() throws Exception {
        Map<?, ?> data = execute("{\"exitCode\":1,\"stdout\":\"oops\",\"stderr\":\"bad\"}");

        Assert.assertEquals(Boolean.FALSE, data.get("ok"));
        Assert.assertEquals(1, data.get("exit_code"));
    }

    @Test
    public void timedOutCommandIsReportedAsNotOk() throws Exception {
        Map<?, ?> data = execute("{\"exitCode\":0,\"timedOut\":true,\"stdout\":\"\"}");

        Assert.assertEquals(Boolean.FALSE, data.get("ok"));
        Assert.assertEquals(Boolean.TRUE, data.get("timed_out"));
    }

    @Test
    public void zeroExitIsReportedAsOk() throws Exception {
        Map<?, ?> data = execute("{\"exitCode\":0,\"stdout\":\"done\"}");

        Assert.assertEquals(Boolean.TRUE, data.get("ok"));
    }

    private static Map<?, ?> execute(String responseBody) throws Exception {
        RemoteHttpPort port = mock(RemoteHttpPort.class);
        when(port.execute(any())).thenReturn(responseBody);
        CuteConfig config = new CuteConfig();
        ReflectionTestUtils.setField(config, "codeInterpreterUrl", "http://test.local");
        CuteRuntimeDependencies dependencies = CuteRuntimeDependencies.builder()
                .cuteConfig(config)
                .remoteHttpPort(port)
                .build();
        AgentContext context = AgentContext.builder()
                .requestId("req-bash")
                .sessionId("sess-bash")
                .workspaceRoot("C:/tmp/bash-ws")
                .runtimeDependencies(dependencies)
                .build();

        Object result = new BashTool(null, null).execute(Map.of("command", "false"), context);

        ToolResultPayload payload = (ToolResultPayload) result;
        Assert.assertEquals(Boolean.FALSE, payload.getFailed());
        return (Map<?, ?>) payload.getLlmData();
    }
}
