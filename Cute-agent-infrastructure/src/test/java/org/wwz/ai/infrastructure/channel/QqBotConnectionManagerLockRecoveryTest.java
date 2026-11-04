package org.wwz.ai.infrastructure.channel;

import org.junit.Test;
import org.wwz.ai.domain.agent.channel.model.ChannelBinding;
import org.wwz.ai.domain.agent.channel.port.ChannelBindingRepository;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class QqBotConnectionManagerLockRecoveryTest {

    @Test
    public void stopConnectionUsesCachedAppIdAfterBindingWasDeleted() throws Exception {
        Path dir = Files.createTempDirectory("qq-lock-recovery");
        ChannelAppIdLock processA = new ChannelAppIdLock(dir.toString());
        ChannelAppIdLock processB = new ChannelAppIdLock(dir.toString());
        QqBotConnectionManager manager = null;
        try {
            assertTrue("前置：A 应先拿到锁", processA.tryLock("app-1"));
            manager = managerWith(repository(bindingId -> Optional.empty()), processA);
            cacheBindingAppId(manager, 1L, "app-1");

            manager.stopConnection(1L);

            assertTrue("绑定行已删除时仍应使用缓存归还 appId 锁", processB.tryLock("app-1"));
        } finally {
            if (manager != null) {
                manager.shutdown();
            }
            processB.unlock("app-1");
            processA.unlock("app-1");
        }
    }

    @Test
    public void repositoryFailureDoesNotEscapeAndCachedAppIdIsRemoved() throws Exception {
        Path dir = Files.createTempDirectory("qq-lock-recovery-error");
        ChannelAppIdLock processA = new ChannelAppIdLock(dir.toString());
        ChannelAppIdLock processB = new ChannelAppIdLock(dir.toString());
        AtomicInteger lookups = new AtomicInteger();
        ChannelBindingRepository repository = repository(bindingId -> {
            lookups.incrementAndGet();
            throw new IllegalStateException("database unavailable");
        });
        QqBotConnectionManager manager = managerWith(repository, processA);
        try {
            assertTrue("前置：A 应先拿到锁", processA.tryLock("app-1"));
            cacheBindingAppId(manager, 1L, "app-1");

            manager.stopConnection(1L);

            assertEquals("缓存命中时不应查询数据库", 0, lookups.get());
            assertTrue("数据库不可用也不能阻止锁归还", processB.tryLock("app-1"));

            manager.stopConnection(1L);

            assertEquals("第二次 stop 应回查数据库，证明缓存条目已清理", 1, lookups.get());
        } finally {
            manager.shutdown();
            processB.unlock("app-1");
            processA.unlock("app-1");
        }
    }

    @SuppressWarnings("unchecked")
    private static void cacheBindingAppId(QqBotConnectionManager manager, Long bindingId, String appId)
            throws Exception {
        Field field = QqBotConnectionManager.class.getDeclaredField("bindingAppIds");
        field.setAccessible(true);
        Map<Long, String> bindingAppIds = (Map<Long, String>) field.get(manager);
        bindingAppIds.put(bindingId, appId);
    }

    private static ChannelBindingRepository repository(FindById findById) {
        return (ChannelBindingRepository) Proxy.newProxyInstance(
                ChannelBindingRepository.class.getClassLoader(),
                new Class<?>[]{ChannelBindingRepository.class},
                (proxy, method, args) -> {
                    if ("findById".equals(method.getName())) {
                        return findById.find((Long) args[0]);
                    }
                    Class<?> returnType = method.getReturnType();
                    if (returnType == boolean.class) {
                        return false;
                    }
                    if (returnType == long.class) {
                        return 0L;
                    }
                    if (returnType == int.class) {
                        return 0;
                    }
                    return null;
                });
    }

    private static QqBotConnectionManager managerWith(ChannelBindingRepository repository,
                                                      ChannelAppIdLock appIdLock) {
        return new QqBotConnectionManager(repository, null, null, null, null, appIdLock, null);
    }

    @FunctionalInterface
    private interface FindById {
        Optional<ChannelBinding> find(Long bindingId) throws Exception;
    }
}
