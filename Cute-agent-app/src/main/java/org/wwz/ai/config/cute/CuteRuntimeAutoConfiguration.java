package org.wwz.ai.config.cute;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.TaskScheduler;
import org.wwz.ai.domain.agent.adapter.port.BrowserBskConnectionPort;
import org.wwz.ai.domain.agent.adapter.port.BrowserRelayPort;
import org.wwz.ai.domain.agent.adapter.port.AgentBrowserSessionPort;
import org.wwz.ai.domain.agent.adapter.port.cli.CliExecutionPort;
import org.wwz.ai.domain.agent.adapter.port.FileArtifactPort;
import org.wwz.ai.domain.agent.adapter.port.RemoteHttpPort;
import org.wwz.ai.domain.agent.adapter.port.RemoteStreamPort;
import org.wwz.ai.domain.agent.adapter.port.SubAgentSessionPort;
import org.wwz.ai.domain.agent.adapter.port.SubAgentSessionStreamPort;
import org.wwz.ai.domain.agent.runtime.llm.LlmCompletionPort;
import org.wwz.ai.domain.agent.runtime.llm.LlmModelCatalog;
import org.wwz.ai.domain.agent.runtime.llm.StreamResponseHandler;
import org.wwz.ai.domain.agent.runtime.llm.retry.LlmResilientCompletionPort;
import org.wwz.ai.domain.agent.runtime.tool.mcp.port.McpToolExecutor;
import org.wwz.ai.domain.agent.cute.config.CuteConfig;
import org.wwz.ai.domain.agent.runtime.CuteLlmDependencies;
import org.wwz.ai.domain.agent.memory.CompactionBudget;
import org.wwz.ai.domain.agent.memory.SessionContextCompactionService;
import org.wwz.ai.domain.agent.memory.ltm.BackgroundReviewService;
import org.wwz.ai.domain.agent.memory.ltm.CuratedMemoryStore;
import org.wwz.ai.domain.agent.memory.ltm.LtmManager;
import org.wwz.ai.domain.agent.memory.ltm.MemoryFlushService;
import org.wwz.ai.domain.agent.memory.ltm.SessionSearchService;
import org.wwz.ai.application.agent.run.BackgroundTaskWakeAdapter;
import org.wwz.ai.domain.agent.adapter.port.BackgroundTaskCompletionEvent;
import org.wwz.ai.domain.agent.adapter.port.BackgroundTaskWakePort;
import org.wwz.ai.domain.agent.runtime.CuteRuntimeDependencies;
import org.wwz.ai.domain.agent.runtime.tasklist.TasklistPersistencePort;
import org.springframework.beans.factory.ObjectProvider;
import org.wwz.ai.domain.agent.image.service.IImageGenerationExecutionKernel;
import org.wwz.ai.domain.agent.runtime.cancel.ActiveAgentRunRegistry;
import org.wwz.ai.domain.agent.runtime.planmode.PlanArtifactStore;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentConcurrencyGate;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentRegistry;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentRunner;
import org.wwz.ai.domain.agent.runtime.tool.codemode.CodeModeExecutorPort;
import org.wwz.ai.domain.agent.runtime.tool.workspace.WorkspaceService;
import org.wwz.ai.infrastructure.codemode.GraalJsCodeModeExecutor;
import org.wwz.ai.types.agent.config.AgentExecutorNames;
import org.wwz.ai.types.agent.config.AgentExecutorProperties;
import org.wwz.ai.types.agent.config.CodeModeProperties;
import org.wwz.ai.types.agent.config.HostCliProperties;
import org.wwz.ai.types.agent.config.ImageGenerationProperties;
import org.wwz.ai.types.agent.config.OpenCliProperties;
import org.wwz.ai.types.agent.config.WebSearchProperties;

import java.util.concurrent.Executor;

/**
 * Cute 运行时依赖装配。
 * app 负责把 Spring Bean 组装为 domain 可消费的 typed runtime bundle。
 * 不在此处承接执行编排、controller 协议适配。
 */
@Configuration
@EnableConfigurationProperties({
        CodeModeProperties.class,
        CompactionProperties.class,
        WebSearchProperties.class,
        ImageGenerationProperties.class})
public class CuteRuntimeAutoConfiguration {

    /**
     * 工作记忆压缩预算（domain 值对象）。
     * <p>压缩配置的唯一运行时来源：{@link CompactionProperties} 绑定
     * {@code autobots.autoagent.compaction.*}，此处构建不可变 {@code CompactionBudget}
     * 注入 {@code SessionContextCompactionServiceImpl}，取代原构造器上的 25 个 {@code @Value}。</p>
     */
    @Bean
    public CompactionBudget compactionBudget(CompactionProperties properties) {
        return CompactionBudget.builder()
                .enabled(properties.isEnabled())
                .llmEnabled(properties.isLlmEnabled())
                .microEnabled(properties.isMicroEnabled())
                .sessionMemoryEnabled(properties.isSessionMemoryEnabled())
                .contextWindow(CompactionBudget.DEFAULT_CONTEXT_WINDOW)
                .thresholdPercent(properties.getThresholdPercent())
                .keepMinTokens(properties.getKeepMinTokens())
                .keepMinTextMessages(properties.getKeepMinTextMessages())
                .keepMaxTokens(properties.getKeepMaxTokens())
                .maxConsecutiveFailures(properties.getMaxConsecutiveFailures())
                .temperature(properties.getTemperature())
                .messageContentCharLimit(properties.getMessageContentCharLimit())
                .summaryTargetRatio(properties.getSummaryTargetRatio())
                .protectFirstN(properties.getProtectFirstN())
                .protectLastN(properties.getProtectLastN())
                .contentMaxChars(properties.getContentMaxChars())
                .contentHeadChars(properties.getContentHeadChars())
                .contentTailChars(properties.getContentTailChars())
                .summaryInputMaxChars(properties.getSummaryInputMaxChars())
                .summarizerTimeoutSeconds(properties.getSummarizerTimeoutSeconds())
                .microKeepRecentToolResults(properties.getMicroKeepRecentToolResults())
                .microToolResultMaxChars(properties.getMicroToolResultMaxChars())
                .midRunEnabled(properties.isMidRunEnabled())
                .persistProjection(properties.isPersistProjection())
                .auditEnabled(properties.isAuditEnabled())
                .auditStoreMessages(properties.isAuditStoreMessages())
                .build();
    }

    /**
     * Code Mode 执行器（GraalJS 同 JVM）。
     * <p>
     * 只有 {@code cute.code-mode.enabled=true} 时才创建；配置关闭时
     * {@link CuteRuntimeDependencies} 拿不到执行器，{@code code_mode} 工具也就不会注册。
     * </p>
     */
    @Bean
    @ConditionalOnProperty(prefix = "cute.code-mode", name = "enabled", havingValue = "true")
    public CodeModeExecutorPort codeModeExecutorPort(CodeModeProperties codeModeProperties,
                                                     ObjectProvider<WorkspaceService> workspaceServiceProvider) {
        return new GraalJsCodeModeExecutor(codeModeProperties, workspaceServiceProvider.getIfAvailable());
    }

    /**
     * StreamResponseHandler 已按 domain 边界要求移除 Spring 注解（不再 import Spring AI），
     * 因此必须由 app 侧显式注册；其 @Resource CuteConfig 字段由容器完成注入。
     */
    @Bean
    public StreamResponseHandler streamResponseHandler() {
        return new StreamResponseHandler();
    }

    /**
     * Domain 侧只认 {@link LlmCompletionPort}，因此在这里统一套一层韧性装饰器：
     * 空补全重试（200 但无产出时补一次）即对所有出站调用路径生效，无需改动调用编排代码。
     * 原始 Bean 不受影响（连接测试等仍可直接注入裸端口）。
     * 可通过 {@code CUTE_LLM_EMPTY_COMPLETION_RETRY_ENABLED=false} 关闭空补全重试。
     */
    @Bean
    public CuteLlmDependencies cuteLlmDependencies(LlmCompletionPort completionPort,
                                                         StreamResponseHandler streamResponseHandler,
                                                         ObjectProvider<LlmModelCatalog> modelCatalogProvider) {
        return CuteLlmDependencies.builder()
                .completionPort(LlmResilientCompletionPort.wrap(completionPort))
                .streamResponseHandler(streamResponseHandler)
                .modelCatalog(modelCatalogProvider.getIfAvailable())
                .build();
    }

    /**
     * 惰性代理：CuteRuntimeDependencies 构造期不能解析 BackgroundTaskWakeAdapter，
     * 否则会经 dispatch → execute → assembly 反向依赖正在创建的 dependencies，形成循环。
     */
    @Bean
    public BackgroundTaskWakePort backgroundTaskWakePort(
            ObjectProvider<BackgroundTaskWakeAdapter> adapterProvider) {
        return new BackgroundTaskWakePort() {
            @Override
            public boolean onBackgroundTaskCompleted(BackgroundTaskCompletionEvent event) {
                BackgroundTaskWakeAdapter adapter = adapterProvider.getIfAvailable();
                return adapter != null && adapter.onBackgroundTaskCompleted(event);
            }

            @Override
            public void onRunSettled(String sessionId) {
                BackgroundTaskWakeAdapter adapter = adapterProvider.getIfAvailable();
                if (adapter != null) {
                    adapter.onRunSettled(sessionId);
                }
            }
        };
    }

    @Bean
    public CuteRuntimeDependencies cuteRuntimeDependencies(CuteConfig cuteConfig,
                                                                 Environment environment,
                                                                 CuteLlmDependencies cuteLlmDependencies,
                                                                 McpToolExecutor mcpToolExecutor,
                                                                 IImageGenerationExecutionKernel imageGenerationExecutionKernel,
                                                                 RemoteHttpPort remoteHttpPort,
                                                                 RemoteStreamPort remoteStreamPort,
                                                                 FileArtifactPort fileArtifactPort,
                                                                   ObjectProvider<BrowserRelayPort> browserRelayPortProvider,
                                                                   ObjectProvider<BrowserBskConnectionPort> browserBskConnectionPortProvider,
                                                                   ObjectProvider<CliExecutionPort> cliExecutionPortProvider,
                                                                     ObjectProvider<AgentBrowserSessionPort> agentBrowserSessionPortProvider,
                                                                   ObjectProvider<OpenCliProperties> openCliPropertiesProvider,
                                                                   ObjectProvider<HostCliProperties> hostCliPropertiesProvider,
                                                                   ObjectProvider<CodeModeProperties> codeModePropertiesProvider,
                                                                   ObjectProvider<CodeModeExecutorPort> codeModeExecutorPortProvider,
                                                                 ObjectProvider<WebSearchProperties> webSearchPropertiesProvider,
                                                                 ObjectProvider<ImageGenerationProperties> imageGenerationPropertiesProvider,
                                                                  @Qualifier(AgentExecutorNames.LLM_EXECUTOR) Executor llmExecutor,
                                                                  @Qualifier(AgentExecutorNames.TASK_EXECUTOR) Executor taskExecutor,
                                                                  @Qualifier(AgentExecutorNames.TOOL_EXECUTOR) Executor toolExecutor,
                                                                  @Qualifier(AgentExecutorNames.HEARTBEAT_SCHEDULER) TaskScheduler heartbeatScheduler,
                                                                   @Lazy SessionContextCompactionService sessionContextCompactionService,
                                                                   ObjectProvider<LtmManager> ltmManagerProvider,
                                                                   ObjectProvider<CuratedMemoryStore> curatedMemoryStoreProvider,
                                                                   ObjectProvider<SessionSearchService> sessionSearchServiceProvider,
                                                                   ObjectProvider<MemoryFlushService> memoryFlushServiceProvider,
                                                                   ObjectProvider<BackgroundReviewService> backgroundReviewServiceProvider,
                                                                   ObjectProvider<LtmProperties> ltmPropertiesProvider,
                                                                   ObjectProvider<TasklistPersistencePort> tasklistPersistencePortProvider,
                                                                   ObjectProvider<SubAgentSessionPort> subAgentSessionPortProvider,
                                                                   ObjectProvider<SubAgentSessionStreamPort> subAgentSessionStreamPortProvider,
                                                                   ObjectProvider<SubAgentRunner> subAgentRunnerProvider,
                                                                   ObjectProvider<SubAgentRegistry> subAgentRegistryProvider,
                                                                   ObjectProvider<ActiveAgentRunRegistry> activeAgentRunRegistryProvider,
                                                                   ObjectProvider<PlanArtifactStore> planArtifactStoreProvider,
                                                                   @Qualifier("backgroundTaskWakePort") BackgroundTaskWakePort backgroundTaskWakePort,
                                                                   AgentExecutorProperties agentExecutorProperties) {
        // SessionContextCompactionService 是接口，@Lazy 可走 JDK 代理；
        // 反向依赖用 ObjectProvider，避免对 final 的 CuteRuntimeDependencies 做 CGLIB 代理。
        return CuteRuntimeDependencies.builder()
                .cuteConfig(cuteConfig)
                .environment(environment)
                .llmDependencies(cuteLlmDependencies)
                .mcpToolExecutor(mcpToolExecutor)
                .imageGenerationExecutionKernel(imageGenerationExecutionKernel)
                .remoteHttpPort(remoteHttpPort)
                .remoteStreamPort(remoteStreamPort)
                .fileArtifactPort(fileArtifactPort)
                .browserRelayPort(browserRelayPortProvider.getIfAvailable())
                .browserBskConnectionPort(browserBskConnectionPortProvider.getIfAvailable())
                .cliExecutionPort(cliExecutionPortProvider.getIfAvailable())
                 .agentBrowserSessionPort(agentBrowserSessionPortProvider.getIfAvailable())
                .openCliProperties(openCliPropertiesProvider.getIfAvailable())
                .hostCliProperties(hostCliPropertiesProvider.getIfAvailable())
                .codeModeProperties(codeModePropertiesProvider.getIfAvailable())
                .codeModeExecutorPort(codeModeExecutorPortProvider.getIfAvailable())
                .webSearchProperties(webSearchPropertiesProvider.getIfAvailable())
                .imageGenerationProperties(imageGenerationPropertiesProvider.getIfAvailable())
                .llmExecutor(llmExecutor)
                .taskExecutor(taskExecutor)
                .toolExecutor(toolExecutor)
                .heartbeatScheduler(heartbeatScheduler)
                .toolBatchTimeoutSeconds(agentExecutorProperties.getToolBatchTimeoutSeconds())
                .sessionContextCompactionService(sessionContextCompactionService)
                .ltmManager(ltmManagerProvider.getIfAvailable())
                .curatedMemoryStore(curatedMemoryStoreProvider.getIfAvailable())
                .sessionSearchService(sessionSearchServiceProvider.getIfAvailable())
                .memoryFlushService(memoryFlushServiceProvider.getIfAvailable())
                .backgroundReviewService(backgroundReviewServiceProvider.getIfAvailable())
                .tasklistPersistencePort(tasklistPersistencePortProvider.getIfAvailable())
                .subAgentSessionPort(subAgentSessionPortProvider.getIfAvailable())
                .subAgentSessionStreamPort(subAgentSessionStreamPortProvider.getIfAvailable())
                .subAgentRunner(subAgentRunnerProvider.getIfAvailable())
                .subAgentRegistry(subAgentRegistryProvider.getIfAvailable())
                .activeAgentRunRegistry(activeAgentRunRegistryProvider.getIfAvailable())
                .planArtifactStore(planArtifactStoreProvider.getIfAvailable())
                .backgroundTaskWakePort(backgroundTaskWakePort)
                .ltmFlushMinTurns(ltmPropertiesProvider.getIfAvailable() == null
                        ? 6
                        : ltmPropertiesProvider.getIfAvailable().getFlushMinTurns())
                .build();
    }

    @Bean
    public SubAgentConcurrencyGate subAgentConcurrencyGate(AgentExecutorProperties agentExecutorProperties) {
        int max = agentExecutorProperties.getMaxConcurrentSubAgents() == null
                ? SubAgentConcurrencyGate.DEFAULT_MAX_CONCURRENT
                : agentExecutorProperties.getMaxConcurrentSubAgents();
        long acquireTimeout = agentExecutorProperties.getSubAgentAcquireTimeoutSeconds() == null
                ? SubAgentConcurrencyGate.DEFAULT_ACQUIRE_TIMEOUT_SECONDS
                : agentExecutorProperties.getSubAgentAcquireTimeoutSeconds();
        return new SubAgentConcurrencyGate(max, acquireTimeout);
    }
}
