package com.xhlcli.runtime.task;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;

/** 本机执行所有权。锁文件不删除，避免不同进程锁住不同 inode。 */
final class TaskFileLock implements AutoCloseable {
    private static final Set<Path> HELD = new HashSet<>();
    private final Path path;
    private final FileChannel channel;
    private final FileLock lock;
    private boolean closed;

    private TaskFileLock(Path path, FileChannel channel, FileLock lock) {
        this.path = path;
        this.channel = channel;
        this.lock = lock;
    }

    static synchronized TaskFileLock tryAcquire(Path path) throws IOException {
        // 同一 JVM 不重复打开已锁文件，避免关闭另一个 channel 释放进程锁。
        if (HELD.contains(path)) return null;
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                return null;
            }
            HELD.add(path);
            return new TaskFileLock(path, channel, lock);
        } catch (OverlappingFileLockException e) {
            channel.close();
            return null;
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    @Override public void close() throws IOException {
        synchronized (TaskFileLock.class) {
            if (closed) return;
            closed = true;
            try { lock.release(); } finally {
                try { channel.close(); } finally { HELD.remove(path); }
            }
        }
    }
}
