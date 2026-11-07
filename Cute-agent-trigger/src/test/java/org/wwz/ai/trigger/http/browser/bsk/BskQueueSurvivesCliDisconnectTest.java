package org.wwz.ai.trigger.http.browser.bsk;

import com.alibaba.fastjson.JSONObject;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 回归：CLI 断开后，已经排进会话车道、还没开始执行的命令不得送达浏览器。
 *
 * <p>队列任务会带着原 CLI 连接到车道轮到时才调用
 * {@link BskCliRelay#forward(BskCliConnection, String)}。中继必须在真正
 * 转发前确认该连接仍然是当前注册连接，否则用户 Ctrl-C 后残留命令仍会
 * 操作浏览器。</p>
 */
public class BskQueueSurvivesCliDisconnectTest {

    /** 单线程执行，第二条任务会真实停在队列里，直到第一条放开。 */
    private final ExecutorService serial = Executors.newSingleThreadExecutor();

    private final CapturingSink extensionSink = new CapturingSink();
    private final BskSessionQueue queue = new BskSessionQueue(serial);
    private final BskFrameRelay frameRelay =
            new BskFrameRelay(queue, new BskHandshakeResponder(), 0);
    private final BskCliRelay cliRelay = new BskCliRelay(frameRelay, 2);

    private static JSONObject handshakeFrame(String id, String instanceId) {
        JSONObject browser = new JSONObject();
        browser.put("name", "chrome");
        browser.put("version", "131.0");
        JSONObject params = new JSONObject();
        params.put("client", "browser-skill-extension");
        params.put("version", "0.3.1");
        params.put("protocol_version", "1.3");
        params.put("instance_id", instanceId);
        params.put("browser", browser);
        params.put("label", "Test Chrome");
        JSONObject frame = new JSONObject();
        frame.put("id", id);
        frame.put("method", "system.handshake");
        frame.put("params", params);
        return frame;
    }

    private static String toolRequest(String id, String sessionId) {
        JSONObject params = new JSONObject();
        params.put("session_id", sessionId);
        params.put("url", "https://example.test/" + id);
        JSONObject frame = new JSONObject();
        frame.put("id", id);
        frame.put("method", "tool.navigate");
        frame.put("params", params);
        return frame.toJSONString();
    }

    /** 先把一条命令占住车道，再排入第二条；CLI 断开后，第二条不得执行。 */
    @Test
    public void queuedCommandDoesNotReachTheBrowserAfterCliDisconnects() throws Exception {
        try {
            frameRelay.registerSession("ext-1", null, "user-1", extensionSink);
            Assert.assertTrue(frameRelay.handleHandshake("ext-1", handshakeFrame("hs-1", "inst-1")));
            BskCliConnection cli = new BskCliConnection("cli-1", "user-1", null, new CapturingSink());
            cliRelay.register(cli);

            CountDownLatch firstRunning = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            CountDownLatch secondFinished = new CountDownLatch(1);

            // 第一条：占住车道，等测试放开。
            queue.submit("s1", () -> {
                firstRunning.countDown();
                try {
                    releaseFirst.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            Assert.assertTrue(firstRunning.await(2, TimeUnit.SECONDS));

            // 第二条：排在同一车道，还没轮到它。
            queue.submit("s1", () -> {
                try {
                    cliRelay.forward(cli, toolRequest("rpc-2", "s1"));
                } finally {
                    secondFinished.countDown();
                }
            });

            // CLI 断开：用户按 Ctrl-C。
            cliRelay.unregister("cli-1");

            // 放开车道，让排队的第二条开始执行。
            releaseFirst.countDown();
            Assert.assertTrue(secondFinished.await(2, TimeUnit.SECONDS));

            boolean secondReachedBrowser = false;
            synchronized (extensionSink.frames) {
                for (String frame : extensionSink.frames) {
                    JSONObject parsed = JSONObject.parseObject(frame);
                    if ("tool.navigate".equals(parsed.getString("method"))
                            && "rpc-2".equals(parsed.getString("id"))) {
                        secondReachedBrowser = true;
                    }
                }
            }

            Assert.assertFalse(
                    "CLI 断开后，排在它后面的 rpc-2 不应再打到浏览器上",
                    secondReachedBrowser);
        } finally {
            serial.shutdownNow();
        }
    }

    /** 记录写出的每一帧，供断言检查浏览器到底收到了什么。 */
    private static final class CapturingSink implements BskSink {
        final java.util.List<String> frames = new java.util.ArrayList<>();
        volatile boolean closed;

        @Override
        public void send(String json) {
            synchronized (frames) {
                frames.add(json);
            }
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
