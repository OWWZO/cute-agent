package org.wwz.ai.infrastructure.channel.qqbot;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.channel.port.ChannelAdapter;
import org.wwz.ai.domain.agent.channel.model.ChannelPushResult;
import org.wwz.ai.domain.agent.channel.model.ChannelTarget;
import org.wwz.ai.types.agent.config.ChannelProperties;
import org.wwz.ai.domain.agent.channel.policy.MarkdownToPlainText;
import org.wwz.ai.domain.agent.channel.policy.ReplyWindow;
import org.wwz.ai.domain.agent.channel.policy.TextChunker;

import java.time.Instant;
import java.util.List;

/**
 * QQ 平台的 {@link ChannelAdapter} 实现。
 * <p>切块与 Markdown 降级都在这里完成——调用方只给「一段想说的话」，不关心平台上限。</p>
 */
@Slf4j
public class QqBotAdapter implements ChannelAdapter {

    public static final String PLATFORM = "qqbot";

    private final ReplyWindow replyWindow;

    public QqBotAdapter(QqBotRestClient restClient, ChannelProperties properties) {
        this.replyWindow = ReplyWindow.ofSeconds(properties.getQqbot().getReplyWindowSeconds());
    }

    @Override
    public String platform() {
        return PLATFORM;
    }

    /**
     * 推文本。契约要求不抛异常、不截断（异常兜底由 {@code ChannelAdapter#pushText} 模板方法执行）。
     * <p>顺序是「先降级 Markdown，再切块」：反过来会让代码块 fence 在切块时被破坏。</p>
     */
    @Override
    public ChannelPushResult doPushText(ChannelTarget target, String text) {
        if (target == null) {
            return ChannelPushResult.fail("target 为空", false);
        }
        if (StringUtils.isBlank(text)) {
            return ChannelPushResult.fail("正文为空", false);
        }
        String plain = MarkdownToPlainText.convert(text);
        if (StringUtils.isBlank(plain)) {
            return ChannelPushResult.fail("正文为空", false);
        }
        ChannelTarget effective = resolveTarget(target);
        List<String> chunks = TextChunker.split(plain, QqBotConstants.MAX_MESSAGE_LENGTH);
        if (chunks.isEmpty()) {
            return ChannelPushResult.fail("正文为空", false);
        }

        String lastMessageId = null;
        for (int i = 0; i < chunks.size(); i++) {
            ChannelPushResult result = sendOne(effective, chunks.get(i));
            if (!result.success()) {
                // 中途失败就停下：继续发后面的块只会让用户看到断章。
                log.warn("qqbot push aborted at chunk {}/{} target={} error={}",
                        i + 1, chunks.size(), effective.targetId(), result.error());
                return result;
            }
            lastMessageId = result.messageId();
        }
        return ChannelPushResult.ok(lastMessageId);
    }

    /** 需要凭证的发送由 connection manager 注入；这里仅暴露给上层做实际投递。 */
    protected ChannelPushResult sendOne(ChannelTarget target, String chunk) {
        throw new UnsupportedOperationException(
                "QqBotAdapter 必须由 QqBotConnectionManager 绑定凭证后使用");
    }

    /**
     * 被动窗口已关闭时去掉 {@code msg_id}——带着过期 msg_id 发送会被 QQ 拒绝，
     * 不带反而有机会作为主动消息发出去（取决于是否申请了主动消息权限）。
     */
    private ChannelTarget resolveTarget(ChannelTarget target) {
        if (target.lastMsgId() == null || target.receivedAt() == null) {
            return target;
        }
        Instant now = Instant.now();
        if (replyWindow.isOpen(target.receivedAt(), now)) {
            return target;
        }
        log.warn("qqbot reply outside passive window age={}s ttl={}s target={}, degrading to active message",
                replyWindow.age(target.receivedAt(), now).toSeconds(),
                replyWindow.ttl().toSeconds(),
                target.targetId());
        return target.withoutReply();
    }
}
