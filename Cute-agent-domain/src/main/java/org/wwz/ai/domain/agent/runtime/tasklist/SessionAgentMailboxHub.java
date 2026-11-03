package org.wwz.ai.domain.agent.runtime.tasklist;

import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.runtime.cancel.PendingInjectMessage;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import java.util.Collection;

/**
 * 进程内按 session+agentId 的子 Agent inject 邮箱（主→子运行中指导）。
 * 同步阻塞派发期间主 Agent 无法发信；仅后台/仍在跑的子 Agent 可投递。
 */
public final class SessionAgentMailboxHub {

    private static final ConcurrentHashMap<String, Mailbox> BOXES = new ConcurrentHashMap<>();
    static final int MAX_QUEUE_SIZE = 256;
    static final long IDLE_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(30);
    /** 清理是 O(n) 全表扫描，投递热路径上最多每分钟触发一次。 */
    private static final long CLEANUP_INTERVAL_MS = TimeUnit.MINUTES.toMillis(1);
    private static final AtomicLong LAST_CLEANUP_AT_MS = new AtomicLong();
    /** {@link #offer} 返回值：目标邮箱已被 {@link #markActive} 关闭，消息未投递，调用方应回退。 */
    public static final int MAILBOX_CLOSED = -2;

    private SessionAgentMailboxHub() {
    }

    /**
     * 取出（必要时创建）该 agent 的注入队列。
     * <p><b>这是物化语义而非只读查询</b>：调用方先取队列再 {@link #markActive}，
     * 两步必须命中同一个 mailbox；因此这里不能改成「不存在就返回空队列」。</p>
     */
    public static ConcurrentLinkedQueue<PendingInjectMessage> queue(String sessionId, String agentId) {
        cleanupExpired();
        return box(sessionId, agentId).queue;
    }

    public static void markActive(String sessionId, String agentId, boolean active) {
        if (StringUtils.isBlank(agentId)) {
            return;
        }
        // 与 offer 共用同一把 per-key 锁：“置封 + 清空”必须和投递原子互斥。
        // 关闭后保留空邮箱作为 tombstone，后续 offer 能识别为已关闭，
        // 而不是 createIfAbsent 出一个永远没人消费的新队列。
        BOXES.compute(key(sessionId, agentId), (ignored, mailbox) -> {
            Mailbox box = mailbox == null ? new Mailbox() : mailbox;
            box.active.set(active);
            box.closed.set(!active);
            box.touch();
            if (!active) {
                box.queue.clear();
            }
            return box;
        });
    }

    /**
     * 原子占位：仅当邮箱当前不 active 时置为 active。
     *
     * @return false 表示该子 Agent 已被其他运行占用；调用方应拒绝重复启动
     */
    public static boolean tryMarkActive(String sessionId, String agentId) {
        if (StringUtils.isBlank(agentId)) {
            return false;
        }
        boolean[] claimed = {false};
        BOXES.compute(key(sessionId, agentId), (ignored, mailbox) -> {
            Mailbox box = mailbox == null ? new Mailbox() : mailbox;
            if (box.active.get()) {
                return box;
            }
            box.active.set(true);
            box.closed.set(false);
            box.touch();
            claimed[0] = true;
            return box;
        });
        return claimed[0];
    }

    public static boolean isActive(String sessionId, String agentId) {
        if (StringUtils.isBlank(agentId)) {
            return false;
        }
        cleanupExpired();
        Mailbox box = BOXES.get(key(sessionId, agentId));
        return box != null && box.active.get();
    }

    /**
     * @return 投递后的队列长度；-1 表示邮箱已满，{@link #MAILBOX_CLOSED} 表示邮箱已关闭（未投递）
     */
    public static int offer(String sessionId, String agentId, PendingInjectMessage message) {
        if (message == null || StringUtils.isBlank(message.getText())) {
            return 0;
        }
        cleanupExpired();
        int[] queued = {MAILBOX_CLOSED};
        BOXES.compute(key(sessionId, agentId), (ignored, mailbox) -> {
            Mailbox box = mailbox == null ? new Mailbox() : mailbox;
            if (box.closed.get()) {
                return box;
            }
            box.touch();
            queued[0] = box.queue.offerAndSize(message);
            return box;
        });
        return queued[0];
    }

    public static void evict(String sessionId, String agentId) {
        Mailbox mailbox = BOXES.remove(key(sessionId, agentId));
        if (mailbox != null) {
            mailbox.queue.clear();
        }
    }

    public static void clearAll() {
        BOXES.values().forEach(mailbox -> mailbox.queue.clear());
        BOXES.clear();
    }

    private static Mailbox box(String sessionId, String agentId) {
        return BOXES.computeIfAbsent(key(sessionId, agentId), k -> new Mailbox());
    }

    private static void cleanupExpired() {
        long now = System.currentTimeMillis();
        long last = LAST_CLEANUP_AT_MS.get();
        if (now - last < CLEANUP_INTERVAL_MS || !LAST_CLEANUP_AT_MS.compareAndSet(last, now)) {
            return;
        }
        BOXES.forEach((key, mailbox) -> {
            if (!mailbox.active.get() && now - mailbox.lastAccessMs > IDLE_TIMEOUT_MS
                    && BOXES.remove(key, mailbox)) {
                mailbox.queue.clear();
            }
        });
    }

    private static String key(String sessionId, String agentId) {
        String sid = StringUtils.defaultIfBlank(sessionId, "default").trim();
        String aid = StringUtils.defaultIfBlank(agentId, "unknown").trim();
        return sid + ":" + aid;
    }

    private static final class Mailbox {
        private final BoundedQueue<PendingInjectMessage> queue = new BoundedQueue<>(MAX_QUEUE_SIZE);
        private final AtomicBoolean active = new AtomicBoolean(false);
        /** 已结束的邮箱保留为 tombstone：拒绝后续投递，避免消息落入无人消费的新队列。 */
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private volatile long lastAccessMs = System.currentTimeMillis();

        private void touch() {
            lastAccessMs = System.currentTimeMillis();
        }
    }

    private static final class BoundedQueue<E> extends ConcurrentLinkedQueue<E> {
        private final int capacity;
        /** 计数器维护队列长度，size() 不再每次遍历链表（上限 256 仍是 O(n)）。 */
        private final AtomicInteger queued = new AtomicInteger();

        private BoundedQueue(int capacity) {
            this.capacity = capacity;
        }

        @Override
        public synchronized boolean offer(E element) {
            if (queued.get() >= capacity) {
                return false;
            }
            if (!super.offer(element)) {
                return false;
            }
            queued.incrementAndGet();
            return true;
        }

        /**
         * 在投递锁内返回投递后的长度：-1 表示队列已满。
         * 与 {@link #offer} 共用同一把锁，调用方不会看到「已投递但长度未计入」的中间态。
         */
        synchronized int offerAndSize(E element) {
            return offer(element) ? queued.get() : -1;
        }

        @Override
        public E poll() {
            E element = super.poll();
            if (element != null) {
                queued.decrementAndGet();
            }
            return element;
        }

        @Override
        public synchronized boolean remove(Object element) {
            boolean removed = super.remove(element);
            if (removed) {
                queued.decrementAndGet();
            }
            return removed;
        }

        @Override
        public synchronized void clear() {
            super.clear();
            queued.set(0);
        }

        @Override
        public int size() {
            return Math.max(0, queued.get());
        }

        @Override
        public synchronized boolean add(E element) {
            return offer(element);
        }

        @Override
        public synchronized boolean addAll(Collection<? extends E> elements) {
            if (elements == this) {
                throw new IllegalArgumentException("cannot add a queue to itself");
            }
            boolean changed = false;
            for (E element : elements) {
                if (!offer(element)) {
                    break;
                }
                changed = true;
            }
            return changed;
        }
    }
}
