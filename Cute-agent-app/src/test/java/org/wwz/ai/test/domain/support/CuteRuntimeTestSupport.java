package org.wwz.ai.test.domain.support;

import okhttp3.OkHttpClient;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;
import org.wwz.ai.domain.agent.adapter.port.FileArtifactPort;
import org.wwz.ai.domain.agent.adapter.port.RemoteHttpPort;
import org.wwz.ai.domain.agent.adapter.port.RemoteStreamPort;
import org.wwz.ai.domain.agent.runtime.llm.LlmCompletionPort;
import org.wwz.ai.domain.agent.runtime.llm.LlmResponse;
import org.wwz.ai.domain.agent.runtime.llm.LlmStreamEvent;
import org.wwz.ai.domain.agent.runtime.llm.StreamResponseHandler;
import org.wwz.ai.domain.agent.runtime.tool.mcp.port.McpToolExecutor;
import org.wwz.ai.domain.agent.cute.config.CuteConfig;
import org.wwz.ai.domain.agent.runtime.CuteLlmDependencies;
import org.wwz.ai.domain.agent.runtime.CuteRuntimeDependencies;
import org.wwz.ai.domain.agent.image.service.IImageGenerationExecutionKernel;
import org.wwz.ai.infrastructure.adapter.port.OkHttpRemoteHttpAdapter;
import org.wwz.ai.infrastructure.adapter.port.OkHttpRemoteStreamAdapter;
import org.wwz.ai.infrastructure.adapter.port.CuteToolFileArtifactAdapter;
import org.wwz.ai.types.agent.config.ImageGenerationProperties;
import org.wwz.ai.types.agent.config.WebSearchProperties;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Cute 运行时测试夹具。
 * 统一为单测构造最小可用的 CuteRuntimeDependencies，避免测试回退到全局 Spring 上下文。
 */
public final class CuteRuntimeTestSupport {

    private CuteRuntimeTestSupport() {
    }

    /**
     * 构造带文件服务地址的过渡态配置。
     * {@code codeInterpreterUrl} 只有 {@code @Value} 注入，没有 setter，测试用反射补齐。
     */
    public static CuteConfig cuteConfigWithFileService(String codeInterpreterUrl) {
        CuteConfig cuteConfig = new CuteConfig();
        ReflectionTestUtils.setField(cuteConfig, "codeInterpreterUrl", codeInterpreterUrl);
        return cuteConfig;
    }

    public static CuteRuntimeDependencies runtimeDependencies(CuteConfig cuteConfig) {
        return runtimeDependencies(cuteConfig, null, new MockEnvironment(), null);
    }

    /**
     * 显式携带拆出去的 WebSearch / ImageGeneration 配置。
     * 这两组配置已从 CuteConfig 搬到 types 层的 properties，只被各自的单一消费方读取，
     * 因此不走默认装配的测试必须在这里显式传入，否则消费方拿到 null。
     */
    public static CuteRuntimeDependencies runtimeDependencies(CuteConfig cuteConfig,
                                                              WebSearchProperties webSearchProperties,
                                                              ImageGenerationProperties imageGenerationProperties) {
        return runtimeDependencies(cuteConfig, null, new MockEnvironment(), null,
                webSearchProperties, imageGenerationProperties);
    }

    /**
     * 同时指定 HTTP 端口与拆出去的配置。
     * WebSearch 用例必须走这个重载：漏传 remoteHttpPort 会回落到真实 OkHttp 适配器，
     * 测试就变成对 api.x.ai 发真请求。
     */
    public static CuteRuntimeDependencies runtimeDependencies(CuteConfig cuteConfig,
                                                              RemoteHttpPort remoteHttpPort,
                                                              WebSearchProperties webSearchProperties,
                                                              ImageGenerationProperties imageGenerationProperties) {
        return runtimeDependencies(cuteConfig, null, new MockEnvironment(), remoteHttpPort,
                webSearchProperties, imageGenerationProperties);
    }

    public static CuteRuntimeDependencies runtimeDependencies(CuteConfig cuteConfig,
                                                                  RemoteHttpPort remoteHttpPort) {
        return runtimeDependencies(cuteConfig, null, new MockEnvironment(), remoteHttpPort);
    }

    public static CuteRuntimeDependencies runtimeDependencies(CuteConfig cuteConfig,
                                                                  IImageGenerationExecutionKernel imageKernel) {
        return runtimeDependencies(cuteConfig, imageKernel, new MockEnvironment(), null);
    }

    public static CuteRuntimeDependencies runtimeDependencies(CuteConfig cuteConfig,
                                                                  IImageGenerationExecutionKernel imageKernel,
                                                                  Environment environment) {
        return runtimeDependencies(cuteConfig, imageKernel, environment, null);
    }

    public static CuteRuntimeDependencies runtimeDependencies(CuteConfig cuteConfig,
                                                                  IImageGenerationExecutionKernel imageKernel,
                                                                  Environment environment,
                                                                  RemoteHttpPort overrideRemoteHttpPort) {
        return runtimeDependencies(cuteConfig, imageKernel, environment, overrideRemoteHttpPort, null, null);
    }

    public static CuteRuntimeDependencies runtimeDependencies(CuteConfig cuteConfig,
                                                                  IImageGenerationExecutionKernel imageKernel,
                                                                  Environment environment,
                                                                  RemoteHttpPort overrideRemoteHttpPort,
                                                                  WebSearchProperties webSearchProperties,
                                                                  ImageGenerationProperties imageGenerationProperties) {
        StreamResponseHandler streamResponseHandler = new StreamResponseHandler();
        ReflectionTestUtils.setField(streamResponseHandler, "cuteConfig", cuteConfig);
        CuteLlmDependencies llmDependencies = CuteLlmDependencies.builder()
                .completionPort(new TestLlmCompletionPort())
                .streamResponseHandler(streamResponseHandler)
                .build();
        RemoteHttpPort remoteHttpPort = overrideRemoteHttpPort != null
                ? overrideRemoteHttpPort
                : new OkHttpRemoteHttpAdapter();
        RemoteStreamPort remoteStreamPort = new OkHttpRemoteStreamAdapter();
        FileArtifactPort fileArtifactPort = new CuteToolFileArtifactAdapter(
                overrideRemoteHttpPort != null ? overrideRemoteHttpPort : new OkHttpRemoteHttpAdapter(),
                new OkHttpClient(),
                CuteToolFileArtifactAdapter.MODE_REGISTER,
                System.getProperty("java.io.tmpdir"));
        Executor sameThreadExecutor = Runnable::run;

        return CuteRuntimeDependencies.builder()
                .cuteConfig(cuteConfig)
                .environment(environment)
                .llmDependencies(llmDependencies)
                .mcpToolExecutor(null)
                .imageGenerationExecutionKernel(imageKernel)
                .remoteHttpPort(remoteHttpPort)
                .remoteStreamPort(remoteStreamPort)
                .fileArtifactPort(fileArtifactPort)
                .llmExecutor(sameThreadExecutor)
                .taskExecutor(sameThreadExecutor)
                .toolExecutor(sameThreadExecutor)
                .heartbeatScheduler(new ConcurrentTaskScheduler())
                .webSearchProperties(webSearchProperties)
                .imageGenerationProperties(imageGenerationProperties)
                .build();
    }

    private static final class TestLlmCompletionPort implements LlmCompletionPort {

        @Override
        public LlmResponse complete(org.wwz.ai.domain.agent.runtime.llm.LlmRequest request) {
            return LlmResponse.builder().content("test response").build();
        }

        @Override
        public StreamCall stream(org.wwz.ai.domain.agent.runtime.llm.LlmRequest request) {
            return new StreamCall() {
                @Override
                public void subscribe(java.util.function.Consumer<LlmStreamEvent> onEvent,
                                      java.util.function.Consumer<Throwable> onError,
                                      Runnable onComplete) {
                    onEvent.accept(LlmStreamEvent.builder().content("test response").build());
                    onComplete.run();
                }

                @Override
                public void cancel() {
                }
            };
        }
    }
}
