package org.wwz.ai.types.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 渠道配置，对应 {@code cute.channel.*}。
 */
@Data
@ConfigurationProperties(prefix = "cute.channel")
public class ChannelProperties {

    /** 总开关，默认开启。关闭时渠道相关 Bean 全部不注册。 */
    private boolean enabled = true;

    /** 全局绑定上限（一个绑定 = 一条 WS 连接）。 */
    private int maxBindings = 10;

    /**
     * 产物链接对外基地址，例如 {@code https://xxx.ngrok.io/tool}。
     * <p>留空 = 直接透传 DB 里的 preview_url（生产推荐）。本地开发留空会自动降级为「只显示文件名」。</p>
     */
    private String publicBaseUrl = "";

    private QqBot qqbot = new QqBot();

    @Data
    public static class QqBot {

        /**
         * 被动回复窗口（秒）。QQ 官方未公开确切值，默认 300 为保守估计，
         * Phase 0 实测后按结论校准。
         */
        private long replyWindowSeconds = 300;

        /** WS 重连退避（秒）。 */
        private long[] reconnectBackoffSeconds = {1, 2, 5, 10, 30};

        /** 连续重连失败达到该次数后把 binding 标记为 ERROR 并停止重连。 */
        private int maxReconnectAttempts = 10;
    }
}
