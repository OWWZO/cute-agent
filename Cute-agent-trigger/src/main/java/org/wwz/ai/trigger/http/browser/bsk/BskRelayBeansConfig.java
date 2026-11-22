package org.wwz.ai.trigger.http.browser.bsk;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * bsk 中继的依赖 bean 装配（queue / responder / relay）。
 *
 * <p>从 {@link BskRelayWebSocketConfig} 拆出来单独放：如果这些 @Bean 与
 * 端点注册同处一个 Config，会形成
 * Config → Handler → Relay → Config 的构造器循环依赖，
 * Spring Boot 3 默认禁止（启动即失败）。</p>
 */
@Configuration
@EnableConfigurationProperties(BskProperties.class)
@ConditionalOnProperty(prefix = "cute.browser-bsk", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BskRelayBeansConfig {

    @Bean
    public BskSessionQueue bskSessionQueue(BskProperties properties) {
        // 021 spec 语义：同 session 排队串行。如需对齐 bsk daemon 的
        // session_busy 拒绝语义，换用 BskSessionQueue.newRejectingWhenBusy()。
        return new BskSessionQueue();
    }

    @Bean
    public BskHandshakeResponder bskHandshakeResponder(BskProperties properties) {
        return new BskHandshakeResponder(
                "0.0.0",
                properties.getProtocolVersion(),
                properties.getMinCompatibleProtocol());
    }

    @Bean
    public BskFrameRelay bskFrameRelay(BskProperties properties,
                                       @Qualifier("bskSessionQueue") BskSessionQueue queue,
                                       BskHandshakeResponder responder) {
        return new BskFrameRelay(queue, responder, properties.getSessionTimeoutSeconds());
    }

    /**
     * CLI 侧转发用的路由：把 CLI 的请求帧投给目标扩展，把扩展的应答送回 CLI。
     *
     * <p>等待预算固定为 {@link BskCliRelay#defaultAwaitTimeoutSeconds()}，不取
     * {@code session-timeout-seconds}：网关必须比 CLI 侧最长的命令预算
     * （{@code screenshot --full-page} 的 120s + 5s）更耐等，否则 CLI 先放弃、
     * 网关后到点，请求会挂成孤儿。</p>
     */
    @Bean
    public BskCliRelay bskCliRelay(BskFrameRelay frameRelay) {
        return new BskCliRelay(frameRelay, BskCliRelay.defaultAwaitTimeoutSeconds());
    }

    /**
     * CLI 侧的串行车道。
     *
     * <p>与扩展侧的队列分开：CLI 车道使用按任务创建的虚拟线程，
     * 控制面仍保留 8 并发 + 64 排队上限。两条链路都会阻塞等待扩展应答，
     * 不应占用公共 ForkJoinPool。</p>
     */
    @Bean
    public BskSessionQueue bskCliSessionQueue(
            @Qualifier("bskCliExecutor") ExecutorService executor,
            @Qualifier("bskCliControlExecutor") ExecutorService controlExecutor) {
        return new BskSessionQueue(executor, controlExecutor);
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService bskCliExecutor() {
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("bsk-cli-relay-", 1).factory());
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService bskCliControlExecutor() {
        return new ThreadPoolExecutor(
                8, 8, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(64),
                Thread.ofVirtual().name("bsk-cli-control-", 1).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }
}
