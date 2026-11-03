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
     * <p><b>契约可执行化：</b>本默认方法保证「MUST NOT 抛异常」这一不变量——实现方只需覆写
     * {@link #doPushText}，任何从它逃逸的 {@link Throwable} 都会在模板方法内被转换为
     * {@link ChannelPushResult#fail(String, boolean)}，不可能再冒泡到 projection 观察者循环。
     * 调用方因此可以安全地直接调用 {@code pushText}，无须再自行 try/catch。</p>
     * <p><b>分块与重试语义：</b>返回的 {@link ChannelPushResult} 描述整次调用的聚合结果，
     * 不区分单个分块。调用方对 {@code retryable=true} 的重试必须重发完整 {@code text}；
     * 实现需保证重发不会因已投递的分块产生不可接受的重复，做不到时须返回 {@code retryable=false}。</p>
     */
    default ChannelPushResult pushText(ChannelTarget target, String text) {
        try {
            return doPushText(target, text);
        } catch (Throwable t) {
            return ChannelPushResult.fail("pushText escaped: " + t, false);
        }
    }

    /**
     * 平台推送实现。允许抛异常；失败由 {@link #pushText} 统一转为
     * {@link ChannelPushResult#fail(String, boolean)}（不可重试），实现自身也可以按
     * 平台语义返回更精确的 {@code retryable} 判定。
     */
    ChannelPushResult doPushText(ChannelTarget target, String text);
}
