package org.wwz.ai.infrastructure.channel.qqbot;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.wwz.ai.domain.agent.channel.model.ChannelChatType;
import org.wwz.ai.domain.agent.channel.model.ChannelPushResult;
import org.wwz.ai.domain.agent.channel.model.ChannelTarget;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * QQ Bot 出站 REST 客户端。
 * <p>端点按会话形态选择：私聊走 {@code /v2/users/{openid}/messages}，
 * 群聊走 {@code /v2/groups/{group_openid}/messages}。</p>
 */
@Slf4j
public class QqBotRestClient {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    /** msg_seq 上限，超过归零。 */
    private static final int MSG_SEQ_MODULUS = 65536;

    private final OkHttpClient httpClient;
    private final QqBotTokenService tokenService;

    /**
     * 同一 msg_id 的回复序号必须递增，否则 QQ 会把后续消息当成重复丢弃。
     * <p>Hermes 用「时间戳 XOR 随机数」，不保证递增；这里改成每 msgId 独立自增。</p>
     */
    private final ConcurrentHashMap<String, AtomicInteger> msgSeqByMsgId = new ConcurrentHashMap<>();

    public QqBotRestClient(OkHttpClient httpClient, QqBotTokenService tokenService) {
        this.httpClient = httpClient;
        this.tokenService = tokenService;
    }

    /**
     * 发送一条纯文本。
     *
     * @param appId     凭证所属机器人
     * @param appSecret 明文 AppSecret
     * @param target    目标；{@code lastMsgId} 非空时走被动回复
     * @param content   已切块、已转换的正文
     */
    public ChannelPushResult sendText(String appId, String appSecret, ChannelTarget target, String content) {
        if (target == null || target.targetId() == null || target.targetId().isBlank()) {
            return ChannelPushResult.fail("缺少发送目标", false);
        }
        if (content == null || content.isBlank()) {
            return ChannelPushResult.fail("正文为空", false);
        }
        String path = resolvePath(target.chatType(), target.targetId());
        if (path == null) {
            return ChannelPushResult.fail("不支持的会话形态：" + target.chatType(), false);
        }

        JSONObject body = new JSONObject();
        body.put("content", content);
        body.put("msg_type", QqBotConstants.MSG_TYPE_TEXT);
        if (target.lastMsgId() != null && !target.lastMsgId().isBlank()) {
            body.put("msg_id", target.lastMsgId());
            body.put("msg_seq", nextMsgSeq(target.lastMsgId()));
        }

        try {
            return post(appId, appSecret, path, body);
        } catch (TokenExpiredException e) {
            // token 被服务端判定失效：清缓存后重试一次，不重试第二次。
            tokenService.invalidate(appId);
            try {
                return post(appId, appSecret, path, body);
            } catch (Exception retryFailure) {
                log.warn("qqbot send retry after 401 failed appId={} target={}", appId, target.targetId(), retryFailure);
                return ChannelPushResult.fail("发送失败：" + retryFailure.getMessage(), false);
            }
        } catch (RateLimitedException e) {
            return ChannelPushResult.fail("发送被限流：" + e.getMessage(), true);
        } catch (Exception e) {
            log.warn("qqbot send failed appId={} target={}", appId, target.targetId(), e);
            return ChannelPushResult.fail("发送失败：" + e.getMessage(), true);
        }
    }

    private ChannelPushResult post(String appId,
                                   String appSecret,
                                   String path,
                                   JSONObject body) throws Exception {
        String token = tokenService.getAccessToken(appId, appSecret);
        Request request = new Request.Builder()
                .url(QqBotConstants.API_BASE + path)
                .header("Authorization", "QQBot " + token)
                .header("Accept", "application/json")
                .post(RequestBody.create(body.toJSONString(), JSON_MEDIA_TYPE))
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            String text = response.body() == null ? "" : response.body().string();
            int code = response.code();
            if (code == 401) {
                throw new TokenExpiredException();
            }
            if (code == 429) {
                throw new RateLimitedException(String.valueOf(response.header("Retry-After")));
            }
            if (code >= 400) {
                // 4xx 是参数/权限问题，重试没有意义，还可能触发风控。
                log.warn("qqbot send rejected status={} path={} body={}", code, path, truncate(text));
                return ChannelPushResult.fail("QQ 接口返回 " + code, false);
            }
            JSONObject json = JSON.parseObject(text);
            String messageId = json == null ? null : json.getString("id");
            return ChannelPushResult.ok(messageId);
        } catch (IOException e) {
            throw new IOException("网络异常：" + e.getMessage(), e);
        }
    }

    private String resolvePath(ChannelChatType chatType, String targetId) {
        if (chatType == ChannelChatType.C2C) {
            return "/v2/users/" + targetId + "/messages";
        }
        if (chatType == ChannelChatType.GROUP) {
            return "/v2/groups/" + targetId + "/messages";
        }
        return null;
    }

    private int nextMsgSeq(String msgId) {
        AtomicInteger counter = msgSeqByMsgId.computeIfAbsent(msgId, ignored -> new AtomicInteger());
        return Math.floorMod(counter.getAndIncrement(), MSG_SEQ_MODULUS);
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }

    private static final class TokenExpiredException extends RuntimeException {
    }

    private static final class RateLimitedException extends RuntimeException {
        private RateLimitedException(String retryAfter) {
            super("retryAfter=" + retryAfter);
        }
    }
}
