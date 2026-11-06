package com.xhlcli.desktop;

import com.fasterxml.jackson.databind.node.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.UUID;

/** 桌面私有状态；损坏时保留原文件并拒绝覆盖。 */
final class DesktopStore implements AutoCloseable {
    final ObjectNode state;
    private final Path file;
    private final FileChannel channel;
    private final FileLock lock;
    DesktopStore(Path home) throws Exception {
        Path directory = home.resolve(".xhlcli/desktop");
        Files.createDirectories(directory);
        channel = FileChannel.open(directory.resolve("state.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try { acquired = channel.tryLock(); } catch (Exception failure) { channel.close(); throw failure; }
        if (acquired == null) { channel.close(); throw new IllegalStateException("已有 XhlCLI 桌面进程正在使用会话数据"); }
        lock = acquired;
        file = directory.resolve("state.json");
        try {
            if (Files.exists(file)) {
                var parsed = DesktopJson.MAPPER.readTree(file.toFile());
                if (!(parsed instanceof ObjectNode object) || !object.path("sessions").isArray() || !object.path("projects").isArray())
                    throw new IllegalStateException("桌面会话文件损坏，原文件已保留：" + file);
                state = object;
            } else {
                state = DesktopJson.MAPPER.createObjectNode();
                state.put("version", 1); state.putArray("projects"); state.putArray("sessions");
            }
            for (var session : state.withArray("sessions")) {
                for (var turn : session.withArray("turns")) {
                    String status = turn.path("status").asText();
                    if (status.equals("running") || status.equals("approval") || status.equals("canceling")) {
                        ((ObjectNode) turn).put("status", "interrupted");
                        ((ObjectNode) turn).put("error", "上次执行已中断，未自动重跑");
                    }
                }
            }
            save();
        } catch (Exception failure) { lock.release(); channel.close(); throw failure; }
    }
    synchronized ObjectNode session(String id) {
        for (var item : state.withArray("sessions")) if (item.path("id").asText().equals(id)) return (ObjectNode) item;
        throw new IllegalArgumentException("会话不存在");
    }
    synchronized ObjectNode project(String id) {
        for (var item : state.withArray("projects")) if (item.path("id").asText().equals(id)) return (ObjectNode) item;
        throw new IllegalArgumentException("项目不存在");
    }
    synchronized void save() throws Exception { DesktopJson.writePrivate(file, state); }
    static String id() { return UUID.randomUUID().toString(); }
    @Override public void close() throws Exception { lock.release(); channel.close(); }
}
