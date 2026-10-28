package org.wwz.ai.domain.agent.channel.port;

import org.wwz.ai.domain.agent.channel.model.ChannelPushResult;
import org.wwz.ai.domain.agent.channel.model.ChannelTarget;


/**
 * 平台无关的出站契约。每个 IM 平台实现一份，case 层只依赖此接口。
 * <p>本期只有 {@link #pushText}：产物以「文件名 + 可点击链接」的文本形式随正文投递。</p>
 */
public interface ChannelAdapter {

    /** 平台标识，与 {@code ai_agent_channel_identity.platform} 一致。 */
    String platform();

    /**
     * 推纯文本。实现方负责按平台上限切块与格式转换。
     * <p>实现 MUST NOT 抛异常：所有失败以 {@link ChannelPushResult#success()} 为 false 返回，
     * 否则异常会冒泡到 projection 观察者循环。</p>
     */
    ChannelPushResult pushText(ChannelTarget target, String text);
}
