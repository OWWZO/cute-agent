package org.wwz.ai.test.domain.tasklist;

import org.junit.Assert;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTaskRegistry;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionBackgroundTaskHub;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionBackgroundTaskHub.Claim;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * getOrCreate 返回后到任务注册之间，刚领取的 registry 不能被另一路溢出淘汰清掉。
 */
public class SessionBackgroundTaskHubClaimGraceTest {

    private static final int MAX_SESSIONS = 1024;

    @Test
    public void claimedRegistrySurvivesConcurrentOverflowEviction() throws Exception {
        CountDownLatch claimedHeld = new CountDownLatch(1);
        CountDownLatch overflowDone = new CountDownLatch(1);
        AtomicReference<RuntimeBackgroundTaskRegistry> claimedRegistry = new AtomicReference<>();
        AtomicReference<Throwable> overflowError = new AtomicReference<>();
        Thread overflow = new Thread(() -> {
            try {
                Assert.assertTrue(claimedHeld.await(5, TimeUnit.SECONDS));
                SessionBackgroundTaskHub.getOrCreate("overflow-trigger", null);
            } catch (Throwable e) {
                overflowError.set(e);
            } finally {
                overflowDone.countDown();
            }
        }, "hub-claim-grace-overflow");
        try {
            SessionBackgroundTaskHub.clearAll();
            fillRunningRegistries();
            overflow.start();
            claimedRegistry.set(SessionBackgroundTaskHub.getOrCreate("claimed", null));
            claimedHeld.countDown();
            Assert.assertTrue("并发溢出线程必须在 30 秒内结束", overflowDone.await(30, TimeUnit.SECONDS));
            Assert.assertNull("并发溢出线程失败", overflowError.get());
            RuntimeBackgroundTaskRegistry registry = claimedRegistry.get();
            Assert.assertNotNull(registry);
            registry.register("local_agent", "claimed but not yet registered", null);
            Assert.assertTrue("刚领取的 registry 必须仍在 hub 中",
                    SessionBackgroundTaskHub.hasRunning("claimed"));
        } finally {
            claimedHeld.countDown();
            overflow.join(30_000L);
            SessionBackgroundTaskHub.clearAll();
        }
    }

    @Test
    public void expiredClaimedRegistryCanBeEvictedByOverflow() throws Exception {
        CountDownLatch expiredReady = new CountDownLatch(1);
        AtomicReference<Throwable> overflowError = new AtomicReference<>();
        Thread overflow = new Thread(() -> {
            try {
                Assert.assertTrue(expiredReady.await(5, TimeUnit.SECONDS));
                SessionBackgroundTaskHub.getOrCreate("overflow-trigger", null);
            } catch (Throwable e) {
                overflowError.set(e);
            }
        }, "hub-expired-claim-overflow");
        try {
            SessionBackgroundTaskHub.clearAll();
            fillRunningRegistries();
            SessionBackgroundTaskHub.getOrCreate("expired-claim", null);
            expireClaim("expired-claim");
            overflow.start();
            expiredReady.countDown();
            overflow.join(30_000L);
            Assert.assertNull("并发溢出线程失败", overflowError.get());
            Assert.assertFalse("超过宽限期的空闲 registry 必须仍可被淘汰",
                    registries().containsKey("expired-claim"));
            Assert.assertFalse("淘汰后的领取时间戳必须同步清理",
                    claimedAt().values().stream().anyMatch(claim -> "expired-claim".equals(claim.key())));
            Assert.assertEquals("超过宽限期后仍须有淘汰额度", MAX_SESSIONS, registries().size() - 1);
            SessionBackgroundTaskHub.getOrCreate("running-protected", null)
                    .register("local_agent", "running", null);
            for (int i = 0; i < 64; i++) {
                SessionBackgroundTaskHub.getOrCreate("post-expiry-" + i, null);
            }
            Assert.assertTrue("运行中的 registry 始终不得淘汰",
                    SessionBackgroundTaskHub.hasRunning("running-protected"));
        } finally {
            expiredReady.countDown();
            overflow.join(30_000L);
            SessionBackgroundTaskHub.clearAll();
        }
    }

    private static void fillRunningRegistries() {
        for (int i = 0; i < MAX_SESSIONS; i++) {
            SessionBackgroundTaskHub.getOrCreate("running-" + i, null)
                    .register("local_agent", "running", null);
        }
    }

    private static void expireClaim(String key) {
        Claim claim = claimedAt().get(Thread.currentThread().threadId());
        Assert.assertNotNull("missing claim for " + key, claim);
        Assert.assertEquals(key, claim.key());
        ReflectionTestUtils.setField(claim, "claimedAtMs",
                System.currentTimeMillis() - SessionBackgroundTaskHub.CLAIM_GRACE_MS - 1);
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, Claim> claimedAt() {
        try {
            Field field = SessionBackgroundTaskHub.class.getDeclaredField("CLAIMED_AT");
            field.setAccessible(true);
            return (Map<Long, Claim>) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, RuntimeBackgroundTaskRegistry> registries() throws Exception {
        Field field = SessionBackgroundTaskHub.class.getDeclaredField("BY_SESSION");
        field.setAccessible(true);
        return (Map<String, RuntimeBackgroundTaskRegistry>) field.get(null);
    }
}
