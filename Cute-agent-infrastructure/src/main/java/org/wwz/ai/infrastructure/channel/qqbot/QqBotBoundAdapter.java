package org.wwz.ai.infrastructure.channel.qqbot;

import lombok.extern.slf4j.Slf4j;
import org.wwz.ai.domain.agent.channel.model.ChannelPushResult;
import org.wwz.ai.domain.agent.channel.model.ChannelTarget;
import org.wwz.ai.types.agent.config.ChannelProperties;
import org.wwz.ai.domain.agent.channel.model.ChannelBinding;

/**
 * 绑定到具体凭证的 QQ 适配器。
 * <p>一个绑定一份实例：appId / appSecret 在构造时固定，出站不再需要调用方传凭证。</p>
 */
@Slf4j
public class QqBotBoundAdapter extends QqBotAdapter {

    private final QqBotRestClient restClient;
    private final String appId;
    private final String appSecret;

    public QqBotBoundAdapter(QqBotRestClient restClient,
                             ChannelProperties properties,
                             ChannelBinding binding) {
        super(restClient, properties);
        if (binding == null || binding.getAppId() == null || binding.getAppSecret() == null) {
            throw new IllegalArgumentException("绑定缺少 appId / appSecret");
        }
        this.restClient = restClient;
        this.appId = binding.getAppId();
        this.appSecret = binding.getAppSecret();
    }

    @Override
    protected ChannelPushResult sendOne(ChannelTarget target, String chunk) {
        return restClient.sendText(appId, appSecret, target, chunk);
    }
}
