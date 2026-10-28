package org.wwz.ai.domain.agent.channel.policy;

import org.apache.commons.lang3.StringUtils;

/**
 * 帧过滤：从投影帧流里挑出「可以发给 IM 用户」的那一帧。
 * <p>参数刻意用原始字段而不是 case 层的 frame 类型——domain 不能反向依赖 case。</p>
 */
public final class ChannelFrameFilter {

    /** 唯一要投递的 packageType：整轮对话的终答。 */
    public static final String PACKAGE_RESULT = "result";

    private ChannelFrameFilter() {
    }

    /**
     * 只认「终答 + 已结束 + 正文非空」。
     * <p>中间帧（{@code agent_stream} / {@code tool} / {@code plan} / {@code heartbeat} /
     * {@code stream_settle} / {@code follow_*}）一律丢弃：要么与终答重复，要么是 Web UI 专属展示，
     * 要么根本不含正文。</p>
     */
    public static boolean shouldDeliver(String packageType, boolean finished, String responseAll) {
        return PACKAGE_RESULT.equals(packageType)
                && finished
                && StringUtils.isNotBlank(responseAll);
    }
}
