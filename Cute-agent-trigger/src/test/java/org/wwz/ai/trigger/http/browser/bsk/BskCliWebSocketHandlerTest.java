package org.wwz.ai.trigger.http.browser.bsk;

import com.alibaba.fastjson.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 锁定 CLI 入口的控制面绕队列与排队取消。 */
public class BskCliWebSocketHandlerTest {

    @Test
    public void waitMsBypassesABusySessionLane() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            TestContext context = context(pool);
            CountDownLatch laneEntered = new CountDownLatch(1);
            CountDownLatch releaseLane = new CountDownLatch(1);
            context.queue.submit("s1", () -> {
                laneEntered.countDown();
                awaitQuietly(releaseLane);
            });
            Assert.assertTrue(laneEntered.await(2, TimeUnit.SECONDS));

            context.handler.handleTextMessage(context.cliSession,
                    new TextMessage(waitRequest("rpc-wait")));
            Assert.assertTrue(awaitFrame(context.cliSession, "rpc-wait", 2000));

            releaseLane.countDown();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void cancelBypassesTheLaneAndStopsAQueuedCommand() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            TestContext context = context(pool);
            CountDownLatch laneEntered = new CountDownLatch(1);
            CountDownLatch releaseLane = new CountDownLatch(1);
            context.queue.submit("s1", () -> {
                laneEntered.countDown();
                awaitQuietly(releaseLane);
            });
            Assert.assertTrue(laneEntered.await(2, TimeUnit.SECONDS));

            context.handler.handleTextMessage(context.cliSession,
                    new TextMessage(navigateRequest("rpc-queued")));
            Assert.assertTrue(awaitQueued(context.cliRelay, 2000));

            context.handler.handleTextMessage(context.cliSession,
                    new TextMessage(cancelRequest("rpc-cancel", "rpc-queued")));
            Assert.assertTrue(awaitFrame(context.cliSession, "rpc-cancel", 2000));

            releaseLane.countDown();
            Assert.assertTrue(awaitFrame(context.cliSession, "rpc-queued", 2000));
            Assert.assertNull(awaitMethod(context.extensionSink, "tool.navigate", 200));
        } finally {
            pool.shutdownNow();
        }
    }

    private static TestContext context(ExecutorService pool) {
        RecordingSink extensionSink = new RecordingSink();
        BskSessionQueue queue = new BskSessionQueue(pool);
        BskFrameRelay relay = new BskFrameRelay(queue, new BskHandshakeResponder(), 0);
        relay.registerSession("ext-1", null, "user-1", extensionSink);
        relay.handleHandshake("ext-1", handshakeFrame("hs-1"));
        BskCliRelay cliRelay = new BskCliRelay(relay, 2);
        BskCliWebSocketHandler handler = new BskCliWebSocketHandler(cliRelay, queue);
        FakeSession cliSession = new FakeSession("cli-1");
        cliSession.attributes.put(BskCliAuthInterceptor.ATTR_USER_ID, "user-1");
        handler.afterConnectionEstablished(cliSession);
        return new TestContext(handler, cliRelay, queue, cliSession, extensionSink);
    }

    private static String waitRequest(String id) {
        JSONObject params = new JSONObject();
        params.put("session_id", "s1");
        params.put("duration_ms", 0);
        JSONObject frame = new JSONObject();
        frame.put("id", id);
        frame.put("method", "tool.wait_ms");
        frame.put("params", params);
        return frame.toJSONString();
    }

    private static String navigateRequest(String id) {
        JSONObject params = new JSONObject();
        params.put("session_id", "s1");
        params.put("url", "https://example.test");
        JSONObject frame = new JSONObject();
        frame.put("id", id);
        frame.put("method", "tool.navigate");
        frame.put("params", params);
        return frame.toJSONString();
    }

    private static String cancelRequest(String id, String targetRpcId) {
        JSONObject params = new JSONObject();
        params.put("rpc_id", targetRpcId);
        JSONObject frame = new JSONObject();
        frame.put("id", id);
        frame.put("method", "cancel");
        frame.put("params", params);
        return frame.toJSONString();
    }

    private static JSONObject handshakeFrame(String id) {
        JSONObject browser = new JSONObject();
        browser.put("name", "chrome");
        browser.put("version", "131.0");
        JSONObject params = new JSONObject();
        params.put("client", "browser-skill-extension");
        params.put("version", "0.4.2");
        params.put("protocol_version", "1.8");
        params.put("instance_id", "inst-1");
        params.put("browser", browser);
        params.put("label", "Test Chrome");
        JSONObject frame = new JSONObject();
        frame.put("id", id);
        frame.put("method", "system.handshake");
        frame.put("params", params);
        return frame;
    }

    private static boolean awaitQueued(BskCliRelay relay, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (relay.queuedCount() > 0) {
                return true;
            }
            sleep();
        }
        return false;
    }

    private static boolean awaitFrame(FakeSession session, String id, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            synchronized (session.sent) {
                for (String payload : session.sent) {
                    JSONObject frame = JSONObject.parseObject(payload);
                    if (id.equals(frame.getString("id"))) {
                        return true;
                    }
                }
            }
            sleep();
        }
        return false;
    }

    private static String awaitMethod(RecordingSink sink, String method, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            synchronized (sink.frames) {
                for (String payload : sink.frames) {
                    if (method.equals(JSONObject.parseObject(payload).getString("method"))) {
                        return payload;
                    }
                }
            }
            sleep();
        }
        return null;
    }

    private static void sleep() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    private record TestContext(BskCliWebSocketHandler handler, BskCliRelay cliRelay,
                               BskSessionQueue queue, FakeSession cliSession,
                               RecordingSink extensionSink) {
    }

    private static final class RecordingSink implements BskSink {
        final List<String> frames = new ArrayList<>();

        @Override
        public void send(String json) {
            synchronized (frames) {
                frames.add(json);
            }
        }

        @Override
        public void close() {
        }
    }

    private static final class FakeSession implements WebSocketSession {
        final Map<String, Object> attributes = new HashMap<>();
        final List<String> sent = new ArrayList<>();
        private final String id;

        FakeSession(String id) {
            this.id = id;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public Map<String, Object> getAttributes() {
            return attributes;
        }

        @Override
        public void sendMessage(org.springframework.web.socket.WebSocketMessage<?> message) {
            synchronized (sent) {
                sent.add(String.valueOf(message.getPayload()));
            }
        }

        @Override
        public void close() {
        }

        @Override
        public void close(CloseStatus status) {
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public URI getUri() {
            return URI.create("ws://localhost/api/agent/browser/bsk-cli");
        }

        @Override
        public org.springframework.http.HttpHeaders getHandshakeHeaders() {
            return new org.springframework.http.HttpHeaders();
        }

        @Override
        public Principal getPrincipal() {
            return null;
        }

        @Override
        public java.net.InetSocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public java.net.InetSocketAddress getRemoteAddress() {
            return null;
        }

        @Override
        public String getAcceptedProtocol() {
            return null;
        }

        @Override
        public void setTextMessageSizeLimit(int messageSizeLimit) {
        }

        @Override
        public int getTextMessageSizeLimit() {
            return 0;
        }

        @Override
        public void setBinaryMessageSizeLimit(int messageSizeLimit) {
        }

        @Override
        public int getBinaryMessageSizeLimit() {
            return 0;
        }

        @Override
        public List<org.springframework.web.socket.WebSocketExtension> getExtensions() {
            return List.of();
        }
    }
}
