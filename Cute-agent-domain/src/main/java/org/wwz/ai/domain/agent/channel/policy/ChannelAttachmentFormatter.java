package org.wwz.ai.domain.agent.channel.policy;

import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.channel.model.ChannelAttachmentRef;

import java.util.List;

/**
 * 附件段落拼装。
 * <p>有可达链接时贴链接（QQ 自动识别为可点击）；链接不可达时明说「当前环境文件链接不可用」，
 * 而不是让用户点一个 127.0.0.1 地址。</p>
 */
public final class ChannelAttachmentFormatter {

    private static final String TITLE = "📎 附件";
    private static final String UNREACHABLE_HINT = "（当前环境文件链接不可用）";

    private ChannelAttachmentFormatter() {
    }

    /**
     * @return 以两个换行开头的段落；列表为空（或全部文件名为空）时返回空串，不产生空标题
     */
    public static String format(List<ChannelAttachmentRef> refs) {
        if (refs == null || refs.isEmpty()) {
            return "";
        }
        StringBuilder body = new StringBuilder();
        int index = 0;
        for (ChannelAttachmentRef ref : refs) {
            if (ref == null || StringUtils.isBlank(ref.fileName())) {
                continue;
            }
            index++;
            if (body.length() > 0) {
                body.append('\n');
            }
            body.append(index).append(". ").append(ref.fileName().trim());
            if (ref.hasUrl()) {
                body.append("\n   ").append(ref.clickableUrl().trim());
            } else {
                body.append(UNREACHABLE_HINT);
            }
        }
        if (index == 0) {
            return "";
        }
        return "\n\n" + TITLE + "\n" + body;
    }
}
