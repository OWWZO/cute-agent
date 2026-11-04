package org.wwz.ai.infrastructure.channel.qqbot;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import okio.ByteString;

import static org.junit.Assert.assertTrue;

/**
 * RESUMED 后必须重新启动心跳：旧 socket 的心跳任务会随连接丢失，不能假定仍在运行。
 */
public class QqBotResumeHeartbeatTest {

    @Test
    public void resumedDispatchRestartsHeartbeat() throws Exception {
        OkHttpClient httpClient = new OkHttpClient();
        CapturingWebSocket socket = new CapturingWebSocket();
        QqBotWsClient client = new QqBotWsClient(
                1L, "app-id", "app-secret", httpClient,
                new QqBotTokenService(httpClient), new long[]{1}, 3, null, null);
        try {
            setField(client, "heartbeatIntervalMillis", 30L);
            ((AtomicBoolean) getField(client, "running")).set(true);
            setField(client, "socket", socket);

            invokeHandlePayload(client, "{\"op\":0,\"t\":\"RESUMED\",\"s\":2,\"d\":{}}");

            assertTrue("RESUMED 后必须重新启动心跳", socket.awaitHeartbeat(2, TimeUnit.SECONDS));
        } finally {
            client.stop();
        }
    }

    private static Object getField(QqBotWsClient client, String name) throws Exception {
        Field field = QqBotWsClient.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(client);
    }

    private static void setField(QqBotWsClient client, String name, Object value) throws Exception {
        Field field = QqBotWsClient.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(client, value);
    }

    private static void invokeHandlePayload(QqBotWsClient client, String raw) throws Exception {
        Method method = QqBotWsClient.class.getDeclaredMethod("handlePayload", String.class);
        method.setAccessible(true);
        method.invoke(client, raw);
    }

    private static final class CapturingWebSocket implements WebSocket {
        private final AtomicInteger heartbeatCount = new AtomicInteger();

        @Override
        public Request request() {
            return new Request.Builder().url("wss://example.invalid").build();
        }

        @Override
        public long queueSize() {
            return 0;
        }

        @Override
        public boolean send(String text) {
            if (text.contains("\"op\":1")) {
                heartbeatCount.incrementAndGet();
            }
            return true;
        }

        @Override
        public boolean send(ByteString bytes) {
            return true;
        }

        @Override
        public boolean close(int code, String reason) {
            return true;
        }

        @Override
        public void cancel() {
        }

        private boolean awaitHeartbeat(long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (System.nanoTime() < deadline) {
                if (heartbeatCount.get() > 0) {
                    return true;
                }
                Thread.sleep(10);
            }
            return heartbeatCount.get() > 0;
        }
    }
}
