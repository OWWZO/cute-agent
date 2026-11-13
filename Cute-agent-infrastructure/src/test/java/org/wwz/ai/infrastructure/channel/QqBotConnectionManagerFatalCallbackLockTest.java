package org.wwz.ai.infrastructure.channel;

import org.junit.Test;
import org.wwz.ai.domain.agent.channel.model.ChannelBinding;
import org.wwz.ai.domain.agent.channel.port.ChannelBindingRepository;
import org.wwz.ai.types.agent.config.ChannelProperties;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class QqBotConnectionManagerFatalCallbackLockTest {

    private static final String APP_ID = "app-reconnect-race";

    @Test
    public void fatalCallbackAndOwnerRetryAreSerialized() throws Exception {
        Path dir = Files.createTempDirectory("qq-fatal-callback-lock");
        ChannelBinding binding = ChannelBinding.builder()
                .id(1L)
                .appId(APP_ID)
                .appSecret("secret")
                .build();
        RaceRepository repository = new RaceRepository(binding);
        ChannelAppIdLock processA = new ChannelAppIdLock(dir.toString());
        ChannelAppIdLock processB = new ChannelAppIdLock(dir.toString());
        ChannelProperties properties = new ChannelProperties();
        properties.getQqbot().setReconnectBackoffSeconds(new long[]{3600});
        properties.getQqbot().setMaxReconnectAttempts(0);
        QqBotConnectionManager manager = new QqBotConnectionManager(
                repository, null, null, null, properties, processA, null);
        try {
            manager.startConnection(binding);
            assertTrue("重连耗尽回调应进入 ERROR 更新",
                    repository.errorUpdateEntered.await(5, TimeUnit.SECONDS));
            assertTrue("首次建连应标记 ACTIVE",
                    repository.initialActive.await(5, TimeUnit.SECONDS));

            // 后续重试使用长退避，避免第二次 client 的后台重连干扰最终状态断言。
            properties.getQqbot().setMaxReconnectAttempts(10);
            manager.startConnection(binding);

            // 未加锁时 owner 能抢在回调完成前建连；加锁后 owner 应等回调退出 monitor 再执行。
            repository.retryActive.await(1, TimeUnit.SECONDS);
            repository.allowErrorUpdate.countDown();
            assertTrue("ERROR 更新应完成",
                    repository.errorUpdateFinished.await(5, TimeUnit.SECONDS));
            assertTrue("owner 重试应最终标记 ACTIVE",
                    repository.retryActive.await(5, TimeUnit.SECONDS));

            List<String> events = List.copyOf(repository.statusEvents);
            String finalStatus = repository.finalStatus.get();
            assertTrue("最终状态必须是 ERROR 或 ACTIVE，实际=" + finalStatus,
                    ChannelBinding.STATUS_ERROR.equals(finalStatus)
                            || ChannelBinding.STATUS_ACTIVE.equals(finalStatus));
            assertTrue("旧回调不能在 owner 重试标 ACTIVE 后再覆盖 ERROR，事件=" + events,
                    events.lastIndexOf(ChannelBinding.STATUS_ERROR)
                            < events.lastIndexOf(ChannelBinding.STATUS_ACTIVE));

            Map<?, ?> connections = readMap(manager, "connections");
            Map<?, ?> adapters = readMap(manager, "adapters");
            boolean managerLockHeld = !processB.tryLock(APP_ID);
            if (ChannelBinding.STATUS_ACTIVE.equals(finalStatus)) {
                assertEquals("ACTIVE 必须保留新连接", 1, connections.size());
                assertEquals("ACTIVE 必须保留新适配器", 1, adapters.size());
                assertTrue("ACTIVE 必须持有 appId 锁", managerLockHeld);
            } else {
                assertTrue("ERROR 不应残留连接或适配器", connections.isEmpty() && adapters.isEmpty());
                assertFalse("ERROR 必须归还 appId 锁", managerLockHeld);
            }
        } finally {
            repository.allowErrorUpdate.countDown();
            manager.shutdown();
            processA.releaseAll();
            processB.releaseAll();
        }
    }

    private static Map<?, ?> readMap(QqBotConnectionManager manager, String fieldName) throws Exception {
        Field field = QqBotConnectionManager.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Map<?, ?>) field.get(manager);
    }

    private static final class RaceRepository implements ChannelBindingRepository {

        private final ChannelBinding binding;
        private final CountDownLatch errorUpdateEntered = new CountDownLatch(1);
        private final CountDownLatch allowErrorUpdate = new CountDownLatch(1);
        private final CountDownLatch errorUpdateFinished = new CountDownLatch(1);
        private final CountDownLatch initialActive = new CountDownLatch(1);
        private final CountDownLatch retryActive = new CountDownLatch(1);
        private final AtomicBoolean errorGate = new AtomicBoolean(true);
        private final AtomicInteger activeUpdates = new AtomicInteger();
        private final AtomicReference<String> finalStatus = new AtomicReference<>();
        private final CopyOnWriteArrayList<String> statusEvents = new CopyOnWriteArrayList<>();

        private RaceRepository(ChannelBinding binding) {
            this.binding = binding;
        }

        @Override
        public Optional<ChannelBinding> findById(Long bindingId) {
            return binding.getId().equals(bindingId) ? Optional.of(binding) : Optional.empty();
        }

        @Override
        public Optional<ChannelBinding> findByOwner(String ownerUserId, String platform) {
            return Optional.empty();
        }

        @Override
        public Optional<ChannelBinding> findByAppId(String platform, String appId) {
            return Optional.empty();
        }

        @Override
        public List<ChannelBinding> listByStatus(String status, int limit) {
            return List.of();
        }

        @Override
        public long countAll() {
            return 0;
        }

        @Override
        public void insert(ChannelBinding ignored) {
        }

        @Override
        public void updateStatus(Long bindingId, String status, String lastError, LocalDateTime connectedAt) {
            if (ChannelBinding.STATUS_ERROR.equals(status) && errorGate.compareAndSet(true, false)) {
                errorUpdateEntered.countDown();
                try {
                    if (!allowErrorUpdate.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("ERROR 更新等待超时");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            statusEvents.add(status);
            finalStatus.set(status);
            if (ChannelBinding.STATUS_ACTIVE.equals(status)) {
                int count = activeUpdates.incrementAndGet();
                if (count == 1) {
                    initialActive.countDown();
                } else {
                    retryActive.countDown();
                }
            }
            if (ChannelBinding.STATUS_ERROR.equals(status)) {
                errorUpdateFinished.countDown();
            }
        }

        @Override
        public void disable(Long bindingId) {
        }
    }
}
