package org.wwz.ai.domain.agent.channel.model;

/**
 * 产物在 IM 消息里的呈现形态。
 * <p>本期策略是「文件名 + 可点击链接」的纯文本，不实现原生文件消息上传。</p>
 *
 * @param fileName     展示用文件名
 * @param clickableUrl 可直接点开的预览地址；空串表示链接不可用（本地开发 / 私网地址），仅展示文件名
 */
public record ChannelAttachmentRef(
        String fileName,
        String clickableUrl
) {

    public boolean hasUrl() {
        return clickableUrl != null && !clickableUrl.isBlank();
    }
}
