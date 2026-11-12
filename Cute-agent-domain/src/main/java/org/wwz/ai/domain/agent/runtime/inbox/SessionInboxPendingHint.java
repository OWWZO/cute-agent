package org.wwz.ai.domain.agent.runtime.inbox;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内「本会话可能仍有待认领 Inbox 行」提示。数据库仍是唯一事实源：
 * 本类只允许跳过一次空查询，绝不能阻止新 run / fork 的首次查询。
 */
public final class SessionInboxPendingHint {

    private static final Object LOCK = new Object();
    private static final Set<String> CLEAN_SESSIONS = ConcurrentHashMap.newKeySet();
    private static long activeWrites;
    private static long writeSequence;
    private static boolean externalWritesPossible;

    private SessionInboxPendingHint() {
    }

    /** 写入方进入 DB 前登记；快照之后发生写入时，空 claim 不得标 clean。 */
    public static WriteState beginWrite() {
        synchronized (LOCK) {
            activeWrites++;
            return new WriteState(activeWrites, ++writeSequence);
        }
    }

    /** 写入方离开 DB 后登记；wrotePending 为 true 时本会话必须重新查库。 */
    public static void finishWrite(String sessionId, WriteState state, boolean wrotePending) {
        synchronized (LOCK) {
            if (wrotePending) {
                CLEAN_SESSIONS.remove(key(sessionId));
            }
            activeWrites--;
            writeSequence++;
            externalWritesPossible = true;
        }
    }

    /** 认领到行或收到其它待投递状态变化时，强制后续 step 重新查库。 */
    public static void markPending(String sessionId) {
        synchronized (LOCK) {
            CLEAN_SESSIONS.remove(key(sessionId));
        }
    }

    public static boolean externalWritesPossible() {
        synchronized (LOCK) {
            return externalWritesPossible;
        }
    }

    public static WriteState snapshot() {
        synchronized (LOCK) {
            return new WriteState(activeWrites, writeSequence);
        }
    }

    /** 仅当快照之后没有写入方进入时，空 claim 才允许把会话标记为 clean。 */
    public static boolean markCleanIfUnchanged(String sessionId, WriteState state) {
        synchronized (LOCK) {
            if (externalWritesPossible || state == null || state.activeWrites() != 0
                    || activeWrites != 0
                    || writeSequence != state.sequence()) {
                return false;
            }
            CLEAN_SESSIONS.add(key(sessionId));
            return true;
        }
    }

    public static boolean mayHavePending(String sessionId) {
        return !CLEAN_SESSIONS.contains(key(sessionId));
    }

    /** 快照之后只要有写入方进入，就强制本 step 重新查库。 */
    public static boolean hasWriteSince(WriteState state) {
        synchronized (LOCK) {
            return state == null || state.activeWrites() != 0
                    || activeWrites != 0 || writeSequence != state.sequence();
        }
    }

    /** 只供测试隔离使用；生产进程不能借此把提示当事实源。 */
    public static void clearAll() {
        synchronized (LOCK) {
            CLEAN_SESSIONS.clear();
            externalWritesPossible = false;
        }
    }

    private static String key(String sessionId) {
        return sessionId == null ? "" : sessionId.trim();
    }

    public record WriteState(long activeWrites, long sequence) {
    }
}
