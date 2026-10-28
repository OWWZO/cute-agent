package org.wwz.ai.config.channel;

import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.wwz.ai.domain.agent.channel.port.ChannelActivationListener;
import org.wwz.ai.domain.agent.channel.port.ChannelBindingOwnershipPort;
import org.wwz.ai.domain.agent.channel.port.ChannelInboundPort;
import org.wwz.ai.domain.agent.channel.port.ChannelQrGateway;
import org.wwz.ai.application.agent.query.IGptQueryApplicationService;
import org.wwz.ai.application.agent.run.AgentRunLaunchGate;
import org.wwz.ai.application.agent.stream.SessionProjectionRegistry;
import org.wwz.ai.application.agent.channel.binding.ChannelBindingApplicationService;
import org.wwz.ai.application.agent.channel.binding.ChannelBindingQrService;
import org.wwz.ai.domain.agent.channel.port.ChannelConnectionPort;
import org.wwz.ai.application.agent.channel.binding.ChannelOwnerQueryService;
import org.wwz.ai.application.agent.channel.inbound.ChannelInboundDispatchService;
import org.wwz.ai.application.agent.channel.inbound.ChannelSessionBinder;
import org.wwz.ai.application.agent.channel.outbound.ChannelArtifactResolver;
import org.wwz.ai.application.agent.channel.outbound.ChannelObserverAttacher;
import org.wwz.ai.types.agent.config.ChannelProperties;
import org.wwz.ai.domain.agent.channel.policy.ChannelUrlPolicy;
import org.wwz.ai.domain.agent.channel.port.ChannelBindingRepository;
import org.wwz.ai.domain.agent.channel.port.ChannelIdentityRepository;
import org.wwz.ai.infrastructure.channel.dao.IAiAgentChannelBindingDao;
import org.wwz.ai.infrastructure.channel.dao.IAiAgentChannelIdentityDao;
import org.wwz.ai.infrastructure.channel.qqbot.QqBotOnboardClient;
import org.wwz.ai.infrastructure.channel.qqbot.QqBotRestClient;
import org.wwz.ai.infrastructure.channel.qqbot.QqBotTokenService;
import org.wwz.ai.infrastructure.adapter.repository.channel.MyBatisChannelBindingRepository;
import org.wwz.ai.infrastructure.adapter.repository.channel.MyBatisChannelIdentityRepository;
import org.wwz.ai.infrastructure.channel.ChannelAppIdLock;
import org.wwz.ai.infrastructure.channel.ChannelBindingOwnershipAdapter;
import org.wwz.ai.infrastructure.channel.QqBotConnectionManager;
import org.wwz.ai.infrastructure.channel.QqBotQrGateway;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerReadRepository;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerWriteRepository;

/**
 * IM 渠道装配。
 * <p>整个模块由 {@code cute.channel.enabled} 控制：关闭时渠道 Bean 与 Mapper 全部不注册，
 * Web UI 与 API 完全不受影响。渠道类刻意不标 {@code @Component}——主应用的组件扫描范围是
 * {@code org.wwz.ai}，如果它们带构造型注解，这个开关就形同虚设。</p>
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "cute.channel", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ChannelProperties.class)
@MapperScan("org.wwz.ai.infrastructure.channel.dao")
public class ChannelAutoConfiguration {

    // ---- 基础设施 ----

    @Bean
    public QqBotTokenService qqBotTokenService(OkHttpClient okHttpClient) {
        return new QqBotTokenService(okHttpClient);
    }

    @Bean
    public QqBotRestClient qqBotRestClient(OkHttpClient okHttpClient, QqBotTokenService tokenService) {
        return new QqBotRestClient(okHttpClient, tokenService);
    }

    @Bean
    public QqBotOnboardClient qqBotOnboardClient(OkHttpClient okHttpClient) {
        return new QqBotOnboardClient(okHttpClient);
    }

    @Bean
    public ChannelAppIdLock channelAppIdLock() {
        String home = System.getProperty("user.home", ".");
        return new ChannelAppIdLock(home + "/.cute-agent/locks");
    }

    @Bean
    public MyBatisChannelBindingRepository channelBindingRepository(IAiAgentChannelBindingDao dao) {
        return new MyBatisChannelBindingRepository(dao);
    }

    @Bean
    public MyBatisChannelIdentityRepository channelIdentityRepository(IAiAgentChannelIdentityDao dao) {
        return new MyBatisChannelIdentityRepository(dao);
    }

    // ---- 纯规则 ----

    @Bean
    public ChannelUrlPolicy channelUrlPolicy(ChannelProperties properties) {
        return new ChannelUrlPolicy(properties.getPublicBaseUrl());
    }

    // ---- 用例 ----

    @Bean
    public ChannelArtifactResolver channelArtifactResolver(IExecutionLedgerReadRepository ledgerReadRepository,
                                                           ChannelUrlPolicy channelUrlPolicy) {
        return new ChannelArtifactResolver(ledgerReadRepository, channelUrlPolicy);
    }

    @Bean
    public ChannelObserverAttacher channelObserverAttacher(SessionProjectionRegistry sessionProjectionRegistry,
                                                           IExecutionLedgerReadRepository ledgerReadRepository,
                                                           ChannelArtifactResolver channelArtifactResolver) {
        return new ChannelObserverAttacher(
                sessionProjectionRegistry, ledgerReadRepository, channelArtifactResolver);
    }

    @Bean
    public ChannelSessionBinder channelSessionBinder(IExecutionLedgerReadRepository ledgerReadRepository,
                                                     IExecutionLedgerWriteRepository ledgerWriteRepository) {
        return new ChannelSessionBinder(ledgerReadRepository, ledgerWriteRepository);
    }

    @Bean
    public ChannelInboundDispatchService channelInboundDispatchService(
            IGptQueryApplicationService gptQueryApplicationService,
            AgentRunLaunchGate agentRunLaunchGate,
            ChannelIdentityRepository channelIdentityRepository,
            ChannelObserverAttacher channelObserverAttacher,
            ChannelSessionBinder channelSessionBinder) {
        return new ChannelInboundDispatchService(
                gptQueryApplicationService,
                agentRunLaunchGate,
                channelIdentityRepository,
                channelObserverAttacher,
                channelSessionBinder);
    }

    @Bean
    public ChannelOwnerQueryService channelOwnerQueryService(
            ChannelBindingApplicationService channelBindingApplicationService,
            IExecutionLedgerReadRepository ledgerReadRepository) {
        return new ChannelOwnerQueryService(channelBindingApplicationService, ledgerReadRepository);
    }

    @Bean
    public QqBotQrGateway qqBotQrGateway(QqBotOnboardClient onboardClient,
                                         ChannelBindingRepository channelBindingRepository) {
        return new QqBotQrGateway(onboardClient, channelBindingRepository);
    }

    @Bean
    public ChannelBindingQrService channelBindingQrService(
            ChannelQrGateway channelQrGateway,
            ChannelActivationListener channelActivationListener) {
        return new ChannelBindingQrService(channelQrGateway, channelActivationListener);
    }

    // ---- 连接生命周期与入口 ----

    @Bean
    public ChannelBindingOwnershipPort channelBindingOwnershipPort(
            ChannelBindingRepository channelBindingRepository) {
        return new ChannelBindingOwnershipAdapter(channelBindingRepository);
    }

    @Bean
    public QqBotConnectionManager qqBotConnectionManager(
            ChannelBindingRepository channelBindingRepository,
            ChannelInboundPort channelInboundPort,
            QqBotTokenService qqBotTokenService,
            QqBotRestClient qqBotRestClient,
            ChannelProperties channelProperties,
            ChannelAppIdLock channelAppIdLock,
            OkHttpClient okHttpClient) {
        return new QqBotConnectionManager(
                channelBindingRepository,
                channelInboundPort,
                qqBotTokenService,
                qqBotRestClient,
                channelProperties,
                channelAppIdLock,
                okHttpClient);
    }

    @Bean
    public ChannelBindingApplicationService channelBindingApplicationService(
            ChannelBindingRepository channelBindingRepository,
            ChannelConnectionPort channelConnectionPort,
            ChannelProperties channelProperties) {
        return new ChannelBindingApplicationService(
                channelBindingRepository, channelConnectionPort, channelProperties);
    }
}
