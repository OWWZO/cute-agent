package org.wwz.ai.infrastructure.channel;

import org.junit.Test;
import org.wwz.ai.domain.agent.channel.model.ChannelBinding;
import org.wwz.ai.domain.agent.channel.port.ChannelBindingRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class QqBotConnectionManagerStopLockTest {

    @Test
    public void stopConnectionReleasesAppIdLockWhenClientIsNull() throws Exception {
        Path dir = Files.createTempDirectory("qq-lock");
        ChannelAppIdLock processA = new ChannelAppIdLock(dir.toString());
        ChannelAppIdLock processB = new ChannelAppIdLock(dir.toString());
        QqBotConnectionManager manager = null;
        try {
            assertTrue("前置：A 应先拿到锁", processA.tryLock("app-1"));

            ChannelBinding binding = ChannelBinding.builder()
                    .id(1L)
                    .appId("app-1")
                    .build();
            manager = managerWith(fakeRepository(binding), processA);

            // connections 里没有 client，正是「建连未完成就被 stop」的路径。
            manager.stopConnection(1L);

            assertTrue("stop 必须在 client 为空时也归还 appId 锁", processB.tryLock("app-1"));
        } finally {
            if (manager != null) {
                manager.shutdown();
            }
            processB.unlock("app-1");
            processA.unlock("app-1");
        }
    }

    @Test
    public void stopConnectionWithNullIdDoesNotTouchLock() throws Exception {
        Path dir = Files.createTempDirectory("qq-lock-null");
        ChannelAppIdLock processA = new ChannelAppIdLock(dir.toString());
        ChannelAppIdLock processB = new ChannelAppIdLock(dir.toString());
        QqBotConnectionManager manager = managerWith(null, processA);
        try {
            assertTrue("前置：A 应先拿到锁", processA.tryLock("app-1"));

            manager.stopConnection(null);

            assertFalse("null bindingId 不应归还任何锁", processB.tryLock("app-1"));
            processA.unlock("app-1");
            assertTrue("清理 A 后 B 应能拿到锁", processB.tryLock("app-1"));
        } finally {
            manager.shutdown();
            processB.unlock("app-1");
            processA.unlock("app-1");
        }
    }

    private static QqBotConnectionManager managerWith(ChannelBindingRepository repository,
                                                      ChannelAppIdLock appIdLock) {
        return new QqBotConnectionManager(repository, null, null, null, null, appIdLock, null);
    }

    private static ChannelBindingRepository fakeRepository(ChannelBinding binding) {
        return new ChannelBindingRepository() {
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
            }

            @Override
            public void disable(Long bindingId) {
            }
        };
    }
}
