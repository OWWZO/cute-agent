package org.wwz.ai.application.agent.channel.binding;

import org.junit.Test;
import org.wwz.ai.domain.agent.channel.port.ChannelActivationListener;
import org.wwz.ai.domain.agent.channel.port.ChannelQrGateway;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 扫码状态机。
 * <p>重点是「COMPLETED 之后必须触发起连接」——漏掉这一步 binding 会永远停在 PENDING，
 * bot 永远不上线，而轮询本身看起来完全正常。</p>
 */
public class ChannelBindingQrServiceTest {

    /** 可编排的假网关：按脚本依次返回结果。 */
    private static final class FakeGateway implements ChannelQrGateway {
        private final AtomicInteger created = new AtomicInteger();
        private final List<ChannelQrGateway.PollOutcome> script = new ArrayList<>();
        private int cursor;

        @Override
        public String createTask(String ownerUserId) {
            return "task-" + created.incrementAndGet();
        }

        @Override
        public ChannelQrGateway.PollOutcome poll(String ownerUserId, String taskId) {
            if (cursor >= script.size()) {
                return ChannelQrGateway.PollOutcome.pending();
            }
            return script.get(cursor++);
        }

        @Override
        public String buildQrUrl(String taskId) {
            return "https://example.test/qr?task_id=" + taskId;
        }
    }

    private static final class RecordingListener
            implements ChannelActivationListener {
        private final List<Long> activated = new ArrayList<>();

        @Override
        public void onActivated(Long bindingId, String ownerUserId) {
            activated.add(bindingId);
        }
    }

    @Test
    public void completedTriggersConnectionStart() throws Exception {
        FakeGateway gateway = new FakeGateway();
        gateway.script.add(ChannelQrGateway.PollOutcome.pending());
        gateway.script.add(ChannelQrGateway.PollOutcome.completed(42L));
        RecordingListener listener = new RecordingListener();
        ChannelBindingQrService service = new ChannelBindingQrService(gateway, listener);
        try {
            ChannelBindingQrService.QrView view = service.createQr("owner-1");
            assertEquals("task-1", view.taskId());

            // 轮询由 2 秒调度驱动，最多等 6 秒。
            long deadline = System.currentTimeMillis() + 6_000;
            while (service.poll(view.taskId()).bindingId() == null
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }

            ChannelBindingQrService.QrStatus status = service.poll(view.taskId());
            assertEquals("COMPLETED", status.status());
            assertEquals(Long.valueOf(42L), status.bindingId());
            assertEquals(List.of(42L), listener.activated);
        } finally {
            service.shutdown();
        }
    }

    @Test
    public void expiredDoesNotStartConnection() throws Exception {
        FakeGateway gateway = new FakeGateway();
        gateway.script.add(ChannelQrGateway.PollOutcome.expired("二维码已过期"));
        RecordingListener listener = new RecordingListener();
        ChannelBindingQrService service = new ChannelBindingQrService(gateway, listener);
        try {
            ChannelBindingQrService.QrView view = service.createQr("owner-2");

            long deadline = System.currentTimeMillis() + 6_000;
            while ("PENDING".equals(service.poll(view.taskId()).status())
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }

            assertEquals("EXPIRED", service.poll(view.taskId()).status());
            assertTrue(listener.activated.isEmpty());
        } finally {
            service.shutdown();
        }
    }

    @Test
    public void createQrIsIdempotentForSameOwner() {
        FakeGateway gateway = new FakeGateway();
        ChannelBindingQrService service = new ChannelBindingQrService(gateway, new RecordingListener());
        try {
            String first = service.createQr("owner-3").taskId();
            String second = service.createQr("owner-3").taskId();
            assertEquals(first, second);
            // 不同用户各自独立。
            assertEquals("task-2", service.createQr("owner-4").taskId());
        } finally {
            service.shutdown();
        }
    }

    @Test
    public void unknownTaskIsRejected() {
        ChannelBindingQrService service = new ChannelBindingQrService(
                new FakeGateway(), new RecordingListener());
        try {
            service.poll("nonexistent");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("任务已失效"));
        } finally {
            service.shutdown();
        }
    }
}
