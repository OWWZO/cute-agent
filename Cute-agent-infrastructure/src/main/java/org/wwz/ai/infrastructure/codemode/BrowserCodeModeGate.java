package org.wwz.ai.infrastructure.codemode;

/**
 * 同一用户的浏览器命令串行闸门。
 * <p>
 * Browser Relay 侧没有命令级队列，同一用户并发下发命令会互相踩页面状态，
 * 因此 Code Mode 内的 browser 调用按 userId 串行；固定分段锁保证内存有界，
 * 极少数哈希碰撞的用户会短暂共享同一把锁。
 * </p>
 */
public final class BrowserCodeModeGate {

    private static final int LOCK_STRIPES = 256;
    private static final Object[] LOCKS = createLocks();

    private BrowserCodeModeGate() {
    }

    /**
     * 在用户级监视器内执行 supplier。
     *
     * <p>锁获取没有超时，等待线程也不会响应中断；supplier 执行过久会阻塞同一用户的后续命令。
     * 不同 userId 通常命中不同监视器；哈希碰撞时只增加短暂等待，不改变串行语义。</p>
     */
    public static <T> T withUserLock(String userId, java.util.function.Supplier<T> supplier) {
        String key = userId == null || userId.isBlank() ? "anonymous" : userId;
        Object lock = LOCKS[key.hashCode() & (LOCK_STRIPES - 1)];
        synchronized (lock) {
            return supplier.get();
        }
    }

    private static Object[] createLocks() {
        Object[] locks = new Object[LOCK_STRIPES];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
        return locks;
    }
}
