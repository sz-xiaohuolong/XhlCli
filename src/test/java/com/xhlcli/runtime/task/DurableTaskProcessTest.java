package com.xhlcli.runtime.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static com.xhlcli.runtime.task.DurableTaskIsolationTest.awaitTerminal;

class DurableTaskProcessTest {
    @TempDir Path temp;

    @Test void secondJvmCannotStealLiveTaskAndCanRequestCancellation() throws Exception {
        Process child = child();
        try {
            String id = awaitStarted(child);
            try (var manager = new DurableTaskManager(temp.resolve("tasks.db"), temp,
                    execution -> { throw new AssertionError("不能重复执行"); }, 1)) {
                assertEquals(TaskStatus.RUNNING, manager.find(id).orElseThrow().status());
                manager.start();
                assertTrue(manager.cancel(id));
                assertEquals(TaskStatus.CANCELED, awaitTerminal(manager, id).status());
                assertTrue(child.isAlive());
            }
        } finally { child.destroyForcibly(); assertTrue(child.waitFor(5, TimeUnit.SECONDS)); }
    }

    @Test void processDeathReleasesOwnershipAndRecoveryKeepsPartialResult() throws Exception {
        Process child = child();
        String id;
        try { id = awaitStarted(child); }
        finally { child.destroyForcibly(); assertTrue(child.waitFor(5, TimeUnit.SECONDS)); }
        try (var manager = new DurableTaskManager(temp.resolve("tasks.db"), temp, e -> {
            throw new AssertionError("不允许重放");
        }, 1)) {
            var task = manager.find(id).orElseThrow();
            assertEquals(TaskStatus.FAILED, task.status());
            assertEquals("崩溃前的结果", task.result());
            assertTrue(task.error().contains("不确定"));
        }
    }

    @Test void oldUnknownWorkspaceRowsAreRetainedButNeverClaimed() throws Exception {
        Path db = temp.resolve("tasks.db");
        AtomicInteger calls = new AtomicInteger();
        try (var manager = new DurableTaskManager(db, temp, e -> {
            calls.incrementAndGet(); return new TaskOutcome(TaskStatus.COMPLETED, "完成", "");
        }, 1)) {
            try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var s = c.createStatement()) {
                s.executeUpdate("INSERT INTO runtime_tasks(id,status,prompt,created_at) VALUES ('old','enqueued','未知归属','2026-01-01T00:00:00Z')");
            }
            manager.start();
            awaitTerminal(manager, manager.enqueue("当前工作区").id());
            assertEquals(1, calls.get());
            assertTrue(manager.find("old").isEmpty());
            assertEquals(1, manager.list(20).size());
        }
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var s = c.createStatement();
             var rs = s.executeQuery("SELECT prompt,status FROM runtime_tasks WHERE id='old'")) {
            assertTrue(rs.next());
            assertEquals("未知归属", rs.getString(1));
            assertEquals("enqueued", rs.getString(2));
        }
    }

    private Process child() throws Exception {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-cp", System.getProperty("java.class.path"), TaskProcessFixture.class.getName(),
                temp.resolve("tasks.db").toString(), temp.toString(), temp.resolve("started").toString())
                .redirectErrorStream(true).redirectOutput(temp.resolve("child.log").toFile()).start();
    }

    private String awaitStarted(Process child) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && child.isAlive()) {
            Path started = temp.resolve("started");
            if (Files.exists(started) && !Files.readString(started).isBlank()) return Files.readString(started);
            Thread.sleep(10);
        }
        throw new AssertionError("子进程未就绪: " + Files.readString(temp.resolve("child.log")));
    }
}
