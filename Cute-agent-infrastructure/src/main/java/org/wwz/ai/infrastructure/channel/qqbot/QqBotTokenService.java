package org.wwz.ai.infrastructure.channel.qqbot;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * access_token 缓存与获取。
 * <p>token 有效期约 2 小时。提前 60 秒刷新，避免临界点上带着过期 token 发请求。
 * 每个 appId 一份缓存；401 时由调用方 {@link #invalidate} 后重试一次。</p>
 */
@Slf4j
public class QqBotTokenService {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final ConcurrentHashMap<String, CachedToken> cache = new ConcurrentHashMap<>();

    public QqBotTokenService(OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /** 取 token；缓存命中且未临近过期时直接返回。 */
    public String getAccessToken(String appId, String appSecret) {
        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
            throw new IllegalArgumentException("appId / appSecret 不能为空");
        }
        CachedToken cached = cache.get(appId);
        if (cached != null && !cached.isExpiring()) {
            return cached.token;
        }
        synchronized (lockFor(appId)) {
            CachedToken again = cache.get(appId);
            if (again != null && !again.isExpiring()) {
                return again.token;
            }
            CachedToken fetched = fetch(appId, appSecret);
            cache.put(appId, fetched);
            return fetched.token;
        }
    }

    /** 401 后调用，强制下次重新取。 */
    public void invalidate(String appId) {
        if (appId != null) {
            cache.remove(appId);
        }
    }

    private CachedToken fetch(String appId, String appSecret) {
        JSONObject body = new JSONObject();
        body.put("appId", appId);
        body.put("clientSecret", appSecret);
        Request request = new Request.Builder()
                .url(QqBotConstants.TOKEN_URL)
                .post(RequestBody.create(body.toJSONString(), JSON_MEDIA_TYPE))
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            String text = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IllegalStateException("获取 access_token 失败 HTTP " + response.code());
            }
            JSONObject json = JSON.parseObject(text);
            String token = json == null ? null : json.getString("access_token");
            if (token == null || token.isBlank()) {
                // 不打印响应体全文，避免把凭证写进日志。
                throw new IllegalStateException("access_token 响应缺少字段");
            }
            long expiresIn = json.getLongValue("expires_in");
            if (expiresIn <= 0) {
                expiresIn = 7200;
            }
            log.info("qqbot access_token acquired appId={} expiresIn={}s", maskAppId(appId), expiresIn);
            return new CachedToken(token, Instant.now().plusSeconds(expiresIn));
        } catch (IOException e) {
            throw new IllegalStateException("获取 access_token 网络异常", e);
        }
    }

    private Object lockFor(String appId) {
        // 用 appId 的字符串常量池做分段锁即可：并发只发生在同一 appId 的首次获取。
        return ("qqbot-token-lock-" + appId).intern();
    }

    private static String maskAppId(String appId) {
        if (appId == null || appId.length() <= 4) {
            return "****";
        }
        return appId.substring(0, 4) + "****";
    }

    private static final class CachedToken {
        private final String token;
        private final Instant expiresAt;

        private CachedToken(String token, Instant expiresAt) {
            this.token = token;
            this.expiresAt = expiresAt;
        }

        private boolean isExpiring() {
            return Instant.now().plusSeconds(QqBotConstants.TOKEN_REFRESH_SKEW_SECONDS).isAfter(expiresAt);
        }
    }
}
