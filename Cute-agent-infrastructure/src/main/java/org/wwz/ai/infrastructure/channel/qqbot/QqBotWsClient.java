package org.wwz.ai.infrastructure.channel.qqbot;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.channel.model.ChannelChatType;
import org.wwz.ai.domain.agent.channel.model.ChannelInboundEvent;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * QQ Bot 的 WS 长连接客户端。
 * <p>生命周期：{@code GET /gateway} 拿地址 → 建连 → 收 {@code op=10 Hello} → 发 {@code op=2 Identify}
 * → 收 {@code op=0 READY} → 按 Hello 给的间隔 × 0.8 发心跳。断线后退避重连，
 * 有 session 时先尝试 {@code op=6 Resume}。</p>
 */
@Slf4j
public class QqBotWsClient {

    private static final int OP_DISPATCH = 0;
    private static final int OP_HEARTBEAT = 1;
    private static final int OP_IDENTIFY = 2;
    private static final int OP_RESUME = 6;
    private static final int OP_RECONNECT = 7;
    private static final int OP_INVALID_SESSION = 9;
    private static final int OP_HELLO = 10;
    private static final int OP_HEARTBEAT_ACK = 11;

    private final Long bindingId;
    private final String appId;
    private final String appSecret;
    private final OkHttpClient httpClient;
    private final QqBotTokenService tokenService;
    private final long[] backoffSeconds;
    private final int maxReconnectAttempts;
    private final Consumer<ChannelInboundEvent> inboundConsumer;
    private final Runnable onFatal;

    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<String, Instant> seenMessageIds = Collections.synchronizedMap(
            new LinkedHashMap<>(QqBotConstants.DEDUP_MAX_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Instant> eldest) {
                    return size() > QqBotConstants.DEDUP_MAX_SIZE;
                }
            });

    private volatile WebSocket socket;
    private volatile String sessionId;
    private volatile Integer lastSeq;
    private volatile long heartbeatIntervalMillis = 30_000L;
    private volatile int reconnectAttempts;
    private volatile ScheduledFuture<?> heartbeatFuture;

    public QqBotWsClient(Long bindingId,
                         String appId,
                         String appSecret,
                         OkHttpClient httpClient,
                         QqBotTokenService tokenService,
                         long[] backoffSeconds,
                         int maxReconnectAttempts,
                         Consumer<ChannelInboundEvent> inboundConsumer,
                         Runnable onFatal) {
        this.bindingId = bindingId;
        this.appId = appId;
        this.appSecret = appSecret;
        this.httpClient = httpClient;
        this.tokenService = tokenService;
        this.backoffSeconds = backoffSeconds == null || backoffSeconds.length == 0
                ? new long[]{1, 2, 5, 10, 30}
                : backoffSeconds;
        this.maxReconnectAttempts = maxReconnectAttempts;
        this.inboundConsumer = inboundConsumer;
        this.onFatal = onFatal;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "qqbot-ws-" + bindingId);
            thread.setDaemon(true);
            return thread;
        });
    }

    public Long bindingId() {
        return bindingId;
    }

    /** 建立首条连接。失败会走退避重连，不向调用方抛异常。 */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        scheduler.execute(this::connect);
    }

    /** 优雅关闭：先停调度，再关 WS。 */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        scheduler.shutdownNow();
        heartbeatFuture = null;
        WebSocket current = socket;
        socket = null;
        if (current != null) {
            try {
                current.close(1000, "client shutdown");
            } catch (Exception e) {
                log.debug("qqbot ws close failed bindingId={}", bindingId, e);
            }
        }
        log.info("qqbot ws stopped bindingId={}", bindingId);
    }

    public boolean isRunning() {
        return running.get();
    }

    private void connect() {
        if (!running.get()) {
            return;
        }
        try {
            String gatewayUrl = fetchGatewayUrl();
            Request request = new Request.Builder().url(gatewayUrl).build();
            socket = httpClient.newWebSocket(request, new Listener());
            log.info("qqbot ws connecting bindingId={} url={}", bindingId, gatewayUrl);
        } catch (Exception e) {
            log.warn("qqbot ws connect failed bindingId={}", bindingId, e);
            scheduleReconnect();
        }
    }

    private String fetchGatewayUrl() throws Exception {
        String token = tokenService.getAccessToken(appId, appSecret);
        Request request = new Request.Builder()
                .url(QqBotConstants.API_BASE + QqBotConstants.GATEWAY_PATH)
                .header("Authorization", "QQBot " + token)
                .header("Accept", "application/json")
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            String text = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IllegalStateException("获取 WS 网关失败 HTTP " + response.code());
            }
            JSONObject json = JSON.parseObject(text);
            String url = json == null ? null : json.getString("url");
            if (StringUtils.isBlank(url)) {
                throw new IllegalStateException("WS 网关响应缺少 url");
            }
            return url;
        }
    }

    private void sendIdentify() {
        JSONObject properties = new JSONObject();
        properties.put("$os", "linux");
        properties.put("$browser", "cute-agent");
        properties.put("$device", "cute-agent");

        JSONArray shard = new JSONArray();
        shard.add(0);
        shard.add(1);

        JSONObject d = new JSONObject();
        d.put("token", "QQBot " + tokenService.getAccessToken(appId, appSecret));
        d.put("intents", QqBotConstants.INTENTS);
        d.put("shard", shard);
        d.put("properties", properties);

        JSONObject payload = new JSONObject();
        payload.put("op", OP_IDENTIFY);
        payload.put("d", d);
        send(payload.toJSONString());
        log.info("qqbot Identify sent bindingId={} intents={}", bindingId, QqBotConstants.INTENTS);
    }

    private void sendResume() {
        JSONObject d = new JSONObject();
        d.put("token", "QQBot " + tokenService.getAccessToken(appId, appSecret));
        d.put("session_id", sessionId);
        d.put("seq", lastSeq);

        JSONObject payload = new JSONObject();
        payload.put("op", OP_RESUME);
        payload.put("d", d);
        send(payload.toJSONString());
        log.info("qqbot Resume sent bindingId={} sessionId={} seq={}", bindingId, sessionId, lastSeq);
    }

    private void send(String text) {
        WebSocket current = socket;
        if (current == null) {
            log.warn("qqbot ws send skipped, socket is null bindingId={}", bindingId);
            return;
        }
        current.send(text);
    }

    private void startHeartbeat() {
        ScheduledFuture<?> previous = heartbeatFuture;
        if (previous != null) {
            previous.cancel(false);
        }
        heartbeatFuture = scheduler.scheduleAtFixedRate(() -> {
            if (!running.get() || socket == null) {
                return;
            }
            JSONObject payload = new JSONObject();
            payload.put("op", OP_HEARTBEAT);
            payload.put("d", lastSeq);
            send(payload.toJSONString());
        }, heartbeatIntervalMillis, heartbeatIntervalMillis, TimeUnit.MILLISECONDS);
    }

    private void scheduleReconnect() {
        if (!running.get()) {
            return;
        }
        reconnectAttempts++;
        if (reconnectAttempts > maxReconnectAttempts) {
            log.error("qqbot reconnect attempts exhausted bindingId={} attempts={}", bindingId, reconnectAttempts);
            running.set(false);
            if (onFatal != null) {
                onFatal.run();
            }
            return;
        }
        long delay = backoffSeconds[Math.min(reconnectAttempts - 1, backoffSeconds.length - 1)];
        log.info("qqbot reconnect in {}s bindingId={} attempt={}", delay, bindingId, reconnectAttempts);
        scheduler.schedule(this::connect, delay, TimeUnit.SECONDS);
    }

    private void handlePayload(String raw) {
        JSONObject payload;
        try {
            payload = JSON.parseObject(raw);
        } catch (Exception e) {
            log.warn("qqbot payload parse failed bindingId={}", bindingId, e);
            return;
        }
        if (payload == null) {
            return;
        }
        Integer op = payload.getInteger("op");
        Integer seq = payload.getInteger("s");
        if (seq != null && (lastSeq == null || seq > lastSeq)) {
            lastSeq = seq;
        }
        if (op == null) {
            return;
        }

        switch (op) {
            case OP_HELLO -> {
                JSONObject d = payload.getJSONObject("d");
                long interval = d == null ? 30_000L : d.getLongValue("heartbeat_interval");
                if (interval <= 0) {
                    interval = 30_000L;
                }
                // 按服务端间隔的 80% 发心跳，留出网络抖动余量。
                heartbeatIntervalMillis = (long) (interval * 0.8);
                log.info("qqbot Hello bindingId={} heartbeatInterval={}ms", bindingId, interval);
                if (sessionId != null && lastSeq != null) {
                    sendResume();
                } else {
                    sendIdentify();
                }
            }
            case OP_DISPATCH -> handleDispatch(payload);
            case OP_HEARTBEAT_ACK -> {
                // 心跳确认，无需动作。
            }
            case OP_RECONNECT -> {
                log.info("qqbot server requested reconnect bindingId={}", bindingId);
                closeQuietly();
            }
            case OP_INVALID_SESSION -> {
                boolean resumable = Boolean.TRUE.equals(payload.get("d"));
                if (!resumable) {
                    sessionId = null;
                    lastSeq = null;
                }
                log.info("qqbot invalid session bindingId={} resumable={}", bindingId, resumable);
                closeQuietly();
            }
            default -> log.debug("qqbot unhandled op={} bindingId={}", op, bindingId);
        }
    }

    private void handleDispatch(JSONObject payload) {
        String type = payload.getString("t");
        JSONObject d = payload.getJSONObject("d");
        if (type == null || d == null) {
            return;
        }
        switch (type) {
            case "READY" -> {
                sessionId = d.getString("session_id");
                reconnectAttempts = 0;
                startHeartbeat();
                log.info("qqbot Ready bindingId={} sessionId={}", bindingId, sessionId);
            }
            case "RESUMED" -> {
                reconnectAttempts = 0;
                // Resume 复用了 session，但旧 socket 的心跳任务已随连接失效；必须重新挂载。
                startHeartbeat();
                log.info("qqbot session resumed bindingId={}", bindingId);
            }
            case "C2C_MESSAGE_CREATE" -> emitC2c(d);
            case "GROUP_AT_MESSAGE_CREATE" -> emitGroup(d);
            default -> log.debug("qqbot ignored dispatch type={} bindingId={}", type, bindingId);
        }
    }

    private void emitC2c(JSONObject d) {
        String openid = readAuthorOpenid(d);
        String messageId = d.getString("id");
        String content = d.getString("content");
        if (StringUtils.isAnyBlank(openid, messageId)) {
            return;
        }
        if (!markSeen(messageId)) {
            return;
        }
        emit(new ChannelInboundEvent(
                QqBotAdapter.PLATFORM,
                bindingId,
                ChannelChatType.C2C,
                openid,
                openid,
                StringUtils.trimToEmpty(content),
                messageId,
                Instant.now()));
    }

    private void emitGroup(JSONObject d) {
        String groupOpenid = d.getString("group_openid");
        String memberOpenid = readAuthorOpenid(d);
        String messageId = d.getString("id");
        if (StringUtils.isAnyBlank(groupOpenid, memberOpenid, messageId)) {
            return;
        }
        if (!markSeen(messageId)) {
            return;
        }
        String content = stripAtMention(StringUtils.trimToEmpty(d.getString("content")));
        emit(new ChannelInboundEvent(
                QqBotAdapter.PLATFORM,
                bindingId,
                ChannelChatType.GROUP,
                groupOpenid,
                memberOpenid,
                content,
                messageId,
                Instant.now()));
    }

    private static String readAuthorOpenid(JSONObject d) {
        JSONObject author = d.getJSONObject("author");
        if (author == null) {
            return null;
        }
        String userOpenid = author.getString("user_openid");
        return StringUtils.isNotBlank(userOpenid) ? userOpenid : author.getString("member_openid");
    }

    /**
     * 剥离群消息里的 @机器人 前缀。
     * <p>QQ 群消息的 content 可能带 {@code @机器人名} 或 {@code <@!xxx>} 两种形态，直接喂给模型
     * 会污染 prompt。正则要求以 @ 或 &lt;@ 开头，所以不含前缀的正文不会被误伤。</p>
     */
    static String stripAtMention(String content) {
        if (StringUtils.isBlank(content)) {
            return "";
        }
        return content.replaceFirst("^(?:<@!?[^>]*>|@\\S+)\\s*", "").trim();
    }

    /** @return true 表示首次见到该消息，false 表示重复投递 */
    private boolean markSeen(String messageId) {
        Instant now = Instant.now();
        Instant previous = seenMessageIds.put(messageId, now);
        if (previous != null
                && previous.plusSeconds(QqBotConstants.DEDUP_WINDOW_SECONDS).isAfter(now)) {
            log.debug("qqbot duplicate message dropped bindingId={} msgId={}", bindingId, messageId);
            return false;
        }
        return true;
    }

    private void emit(ChannelInboundEvent event) {
        if (inboundConsumer == null) {
            return;
        }
        try {
            inboundConsumer.accept(event);
        } catch (Exception e) {
            log.warn("qqbot inbound consumer failed bindingId={}", bindingId, e);
        }
    }

    private void closeQuietly() {
        WebSocket current = socket;
        socket = null;
        if (current != null) {
            try {
                current.close(1000, "reconnect");
            } catch (Exception e) {
                log.debug("qqbot ws close failed bindingId={}", bindingId, e);
            }
        }
    }

    private final class Listener extends WebSocketListener {

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            log.info("qqbot ws opened bindingId={}", bindingId);
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            try {
                handlePayload(text);
            } catch (Exception e) {
                log.warn("qqbot payload handling failed bindingId={}", bindingId, e);
            }
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            log.warn("qqbot ws failure bindingId={}", bindingId, t);
            socket = null;
            scheduleReconnect();
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            log.info("qqbot ws closing bindingId={} code={} reason={}", bindingId, code, reason);
            socket = null;
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            socket = null;
            if (running.get()) {
                scheduleReconnect();
            }
        }
    }
}
