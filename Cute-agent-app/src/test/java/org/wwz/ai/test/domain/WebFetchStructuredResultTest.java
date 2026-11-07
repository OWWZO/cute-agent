package org.wwz.ai.test.domain;

import com.alibaba.fastjson.JSON;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.wwz.ai.domain.agent.adapter.port.RemoteHttpPort;
import org.wwz.ai.domain.agent.adapter.port.RemoteHttpRequest;
import org.wwz.ai.domain.agent.adapter.port.RemoteHttpResponse;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.tool.ToolObservationSerializer;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;
import org.wwz.ai.domain.agent.runtime.tool.common.WebFetchTool;
import org.wwz.ai.domain.agent.cute.config.CuteConfig;
import org.wwz.ai.test.domain.support.CuteRuntimeTestSupport;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;

/**
 * WebFetch 非 2xx 响应的结构化错误回归。
 */
public class WebFetchStructuredResultTest {

    @Test
    public void shouldDelegatePageFetchToPythonService() {
        AtomicReference<RemoteHttpRequest> captured = new AtomicReference<>();
        RemoteHttpPort httpPort = new RemoteHttpPort() {
            @Override
            public String execute(RemoteHttpRequest request) {
                return "";
            }

            @Override
            public RemoteHttpResponse executeDetailed(RemoteHttpRequest request) {
                captured.set(request);
                return RemoteHttpResponse.builder()
                        .statusCode(200)
                        .statusText("OK")
                        .headers(Map.of("Content-Type", "application/json"))
                        .body("""
                                {
                                  "code": 200,
                                  "data": {
                                    "title": "Example",
                                    "finalUrl": "https://example.com/article",
                                    "content": "article body",
                                    "contentFormat": "markdown",
                                    "contentSource": "trafilatura",
                                    "wordCount": 2,
                                    "statusCode": 200,
                                    "statusText": "OK",
                                    "metadata": {}
                                  }
                                }
                                """)
                        .finalUrl(request.getUrl())
                        .build();
            }
        };

        CuteConfig config = new CuteConfig();
        ReflectionTestUtils.setField(config, "webFetchUrl", "http://cute-tool:1601");
        WebFetchTool tool = new WebFetchTool();

        ToolResultPayload payload = (ToolResultPayload) tool.execute(Map.of(
                "url", "https://example.com/article",
                "prompt", "extract the title"
        ), AgentContext.builder()
                .requestId("req-web-fetch-proxy")
                .sessionId("session-web-fetch-proxy")
                .runtimeDependencies(CuteRuntimeTestSupport.runtimeDependencies(config, httpPort))
                .build());

        Assert.assertFalse(Boolean.TRUE.equals(payload.getFailed()));
        Assert.assertNotNull(captured.get());
        Assert.assertEquals("POST", captured.get().getMethod());
        Assert.assertEquals("http://cute-tool:1601/v1/tool/web_fetch", captured.get().getUrl());
        Assert.assertNull(captured.get().getProxy());
        Assert.assertEquals("https://example.com/article",
                JSON.parseObject(captured.get().getBody()).getString("url"));
        Assert.assertTrue(JSON.toJSONString(payload.getLlmData()).contains("article body"));
    }

    @Test
    public void shouldKeepHttpFailureDetailsInStructuredPayload() {
        RemoteHttpPort httpPort = new RemoteHttpPort() {
            @Override
            public String execute(RemoteHttpRequest request) {
                return "";
            }

            @Override
            public RemoteHttpResponse executeDetailed(RemoteHttpRequest request) {
                return RemoteHttpResponse.builder()
                        .statusCode(404)
                        .statusText("Not Found")
                        .headers(Map.of("Content-Type", "text/html"))
                        .body("fund page is missing")
                        .finalUrl(request.getUrl())
                        .build();
            }
        };

        CuteConfig config = new CuteConfig();
        ReflectionTestUtils.setField(config, "webFetchUrl", "http://cute-tool:1601");
        WebFetchTool tool = new WebFetchTool();

        ToolResultPayload payload = (ToolResultPayload) tool.execute(Map.of(
                "url", "https://example.com/missing",
                "prompt", "extract the title"
        ), AgentContext.builder()
                .requestId("req-web-fetch-404")
                .sessionId("session-web-fetch-404")
                .runtimeDependencies(CuteRuntimeTestSupport.runtimeDependencies(config, httpPort))
                .build());

        Assert.assertTrue(Boolean.TRUE.equals(payload.getFailed()));
        Assert.assertTrue(payload.getLlmData() instanceof Map<?, ?>);
        Map<?, ?> detail = (Map<?, ?>) payload.getLlmData();
        Assert.assertEquals("web_fetch", detail.get("tool"));
        Assert.assertEquals(404, detail.get("status"));
        Assert.assertEquals("Not Found", detail.get("statusText"));
        Assert.assertEquals("fund page is missing", detail.get("responseBody"));

        String observation = ToolObservationSerializer.serializePayload(payload);
        Assert.assertTrue(observation.contains("\"tool_ok\":false"));
        Assert.assertTrue(observation.contains("\"status\":404"));
    }

    @Test
    public void shouldOpenCircuitAfterRepeatedProxyFailures() {
        AtomicInteger calls = new AtomicInteger();
        RemoteHttpPort httpPort = new RemoteHttpPort() {
            @Override
            public String execute(RemoteHttpRequest request) {
                return "";
            }

            @Override
            public RemoteHttpResponse executeDetailed(RemoteHttpRequest request) {
                calls.incrementAndGet();
                return RemoteHttpResponse.builder()
                        .statusCode(502)
                        .statusText("Bad Gateway")
                        .headers(Map.of("Content-Type", "application/json"))
                        .body("""
                                {"code":502,"message":"Cannot connect to host 127.0.0.1:7890 ssl:default"}
                                """)
                        .finalUrl(request.getUrl())
                        .build();
            }
        };

        CuteConfig config = new CuteConfig();
        ReflectionTestUtils.setField(config, "webFetchUrl", "http://cute-tool:1601");
        WebFetchTool tool = new WebFetchTool();
        AgentContext context = AgentContext.builder()
                .requestId("req-web-fetch-circuit")
                .sessionId("session-web-fetch-circuit")
                .runtimeDependencies(CuteRuntimeTestSupport.runtimeDependencies(config, httpPort))
                .build();

        ToolResultPayload first = (ToolResultPayload) tool.execute(Map.of(
                "url", "https://example.com/a",
                "prompt", "extract"
        ), context);
        ToolResultPayload second = (ToolResultPayload) tool.execute(Map.of(
                "url", "https://example.com/b",
                "prompt", "extract"
        ), context);
        ToolResultPayload third = (ToolResultPayload) tool.execute(Map.of(
                "url", "https://example.com/c",
                "prompt", "extract"
        ), context);

        Map<?, ?> firstDetail = (Map<?, ?>) first.getLlmData();
        Map<?, ?> thirdDetail = (Map<?, ?>) third.getLlmData();
        Assert.assertEquals("network_proxy", firstDetail.get("failureClass"));
        Assert.assertEquals(Boolean.FALSE, firstDetail.get("retryable"));
        Assert.assertEquals("network_circuit_open", thirdDetail.get("failureClass"));
        Assert.assertEquals(Boolean.FALSE, thirdDetail.get("retryable"));
        Assert.assertEquals(2, calls.get());
        Assert.assertTrue(Boolean.TRUE.equals(second.getFailed()));
        Assert.assertTrue(Boolean.TRUE.equals(third.getFailed()));
    }
}
