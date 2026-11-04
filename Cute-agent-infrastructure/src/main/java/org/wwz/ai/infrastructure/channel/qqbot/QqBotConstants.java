package org.wwz.ai.infrastructure.channel.qqbot;

/**
 * QQ Bot API v2 常量。取值依据 Hermes 的 {@code gateway/platforms/qqbot/constants.py}。
 */
public final class QqBotConstants {

    /** access_token 端点。 */
    public static final String TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken";

    /** 业务 API 基地址。 */
    public static final String API_BASE = "https://api.sgroup.qq.com";

    /** WS 网关地址发现端点。 */
    public static final String GATEWAY_PATH = "/gateway";

    /** 扫码绑定端点所在主机。 */
    public static final String PORTAL_HOST = "q.qq.com";

    public static final String ONBOARD_CREATE_PATH = "/lite/create_bind_task";

    public static final String ONBOARD_POLL_PATH = "/lite/poll_bind_result";

    /**
     * 扫码连接页。{@code source} 参数是否被校验未知，先保持上游原值，Phase 0 实测后再定。
     */
    public static final String QR_URL_TEMPLATE =
            "https://q.qq.com/qqbot/openclaw/connect.html?task_id={taskId}&_wv=2&source=hermes";

    private static final int INTENT_C2C_GROUP_AT_MESSAGES = 1 << 25;
    private static final int INTENT_PUBLIC_GUILD_MESSAGES = 1 << 30;
    private static final int INTENT_DIRECT_MESSAGE = 1 << 12;
    private static final int INTENT_INTERACTION = 1 << 26;

    /**
     * Identify 的 intents 位组合：
     * {@code 1<<25} C2C_GROUP_AT_MESSAGES、{@code 1<<30} PUBLIC_GUILD_MESSAGES、
     * {@code 1<<12} DIRECT_MESSAGE、{@code 1<<26} INTERACTION。
     * <p>漏任何一位都会导致对应事件收不到。</p>
     */
    public static final int INTENTS = INTENT_C2C_GROUP_AT_MESSAGES
            | INTENT_PUBLIC_GUILD_MESSAGES
            | INTENT_DIRECT_MESSAGE
            | INTENT_INTERACTION;

    /** 单条消息字符上限。 */
    public static final int MAX_MESSAGE_LENGTH = 4000;

    public static final int MSG_TYPE_TEXT = 0;

    /** 去重窗口（秒）。 */
    public static final int DEDUP_WINDOW_SECONDS = 300;

    public static final int DEDUP_MAX_SIZE = 1000;

    /** token 提前刷新的余量（秒）。 */
    public static final long TOKEN_REFRESH_SKEW_SECONDS = 60;

    private QqBotConstants() {
    }
}
