package org.wwz.ai.infrastructure.channel;

import lombok.extern.slf4j.Slf4j;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 同一 appId 的进程级互斥。
 * <p>项目是单实例内存架构，但开发时可能同时跑 IDE 和命令行；两个进程用同一个 bot 会双投消息
 * 或被 QQ 踢连接。用本地文件锁以最小成本堵住这个场景。</p>
 */
@Slf4j
public class ChannelAppIdLock {

    private final Path lockDir;
    private final Map<String, LockHandle> held = new ConcurrentHashMap<>();

    public ChannelAppIdLock(String lockDir) {
        this.lockDir = Paths.get(lockDir);
    }

    /** @return true 表示拿到锁（含重入：同进程内同一 appId 二次调用直接成功） */
    public synchronized boolean tryLock(String appId) {
        if (appId == null || appId.isBlank()) {
            return false;
        }
        if (held.containsKey(appId)) {
            return true;
        }
        try {
            Files.createDirectories(lockDir);
            Path lockPath = lockDir.resolve("qq_" + sanitize(appId) + ".lock");
            FileChannel channel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                log.warn("appId lock held by another process appId={}", appId);
                return false;
            }
            held.put(appId, new LockHandle(channel, lock));
            log.info("appId lock acquired appId={}", appId);
            return true;
        } catch (IOException e) {
            log.warn("appId lock failed appId={}", appId, e);
            return false;
        }
    }

    public synchronized void unlock(String appId) {
        if (appId == null) {
            return;
        }
        LockHandle handle = held.remove(appId);
        if (handle == null) {
            return;
        }
        try {
            handle.lock.release();
        } catch (IOException e) {
            log.debug("appId lock release failed appId={}", appId, e);
        }
        try {
            handle.channel.close();
        } catch (IOException e) {
            log.debug("appId lock channel close failed appId={}", appId, e);
        }
    }

    @PreDestroy
    public synchronized void releaseAll() {
        for (String appId : held.keySet().toArray(new String[0])) {
            unlock(appId);
        }
    }

    private static String sanitize(String appId) {
        return appId.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private record LockHandle(FileChannel channel, FileLock lock) {
    }
}
