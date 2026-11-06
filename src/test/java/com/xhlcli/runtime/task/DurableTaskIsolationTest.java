package com.xhlcli.runtime.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DurableTaskIsolationTest {
    @TempDir Path temp;

    private DurableTaskManager manager(Path db, Path workspace, TaskRunner runner) throws Exception {
        return new DurableTaskManager(db, runner, 1, workspace.toString());
    }

    @Test void workspaceScopesListFindCancelAndClaim() throws Exception {
        Path a = Files.createDirectory(temp.resolve("a"));
        Path b = Files.createDirectory(temp.resolve("b"));
        Path db = temp.resolve("tasks.db");
        AtomicInteger calls = new AtomicInteger();
        try (var first = manager(db, a, p -> "A"); var second = manager(db, b, p -> { calls.incrementAndGet(); return "B"; })) {
            var task = first.enqueue("只属于 A");
            assertTrue(second.list(20).isEmpty(), "不能泄露其他工作区任务");
            assertTrue(second.find(task.id()).isEmpty());
            assertFalse(second.cancel(task.id()));
            var own = second.enqueue("属于 B");
            second.start();
            awaitTerminal(second, own.id());
            assertEquals(1, calls.get());
            assertEquals(TaskStatus.ENQUEUED, first.find(task.id()).orElseThrow().status());
        }
    }

    @Test void openingAnotherManagerDoesNotRequeueLiveExecution() throws Exception {
        Path db = temp.resolve("tasks.db");
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var first = manager(db, temp, p -> { started.countDown(); release.await(); return "完成"; })) {
            first.start();
            var task = first.enqueue("执行一次");
            assertTrue(started.await(3, TimeUnit.SECONDS));
            try (var second = manager(db, temp, p -> "重复")) {
                assertEquals(TaskStatus.RUNNING, second.find(task.id()).orElseThrow().status());
            } finally { release.countDown(); }
            awaitTerminal(first, task.id());
        }
    }

    @Test void orphanIsFailedWithoutReplayingAndResultSurvivesReopen() throws Exception {
        Path db = temp.resolve("tasks.db");
        String id;
        try (var first = manager(db, temp, p -> "不执行")) { id = first.enqueue("失联任务").id(); }
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var s = c.createStatement()) {
            s.executeUpdate("UPDATE runtime_tasks SET status='running', result='已有部分结果'");
        }
        AtomicInteger calls = new AtomicInteger();
        try (var second = manager(db, temp, p -> { calls.incrementAndGet(); return "错误重放"; })) {
            var task = second.find(id).orElseThrow();
            assertEquals(TaskStatus.FAILED, task.status());
            assertEquals("已有部分结果", task.result());
            assertTrue(task.error().contains("不确定"));
            second.start();
            var fresh = second.enqueue("新任务");
            awaitTerminal(second, fresh.id());
            assertEquals(1, calls.get());
        }
        try (var third = manager(db, temp, p -> "不执行")) {
            assertEquals("已有部分结果", third.find(id).orElseThrow().result());
        }
    }

    @Test void runningCancellationDoesNotAcknowledgeBeforeRunnerExits() throws Exception {
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var manager = manager(temp.resolve("tasks.db"), temp, p -> {
            started.countDown();
            try { release.await(); } catch (InterruptedException e) { interrupted.countDown(); release.await(); }
            return "迟到成功";
        })) {
            manager.start();
            var task = manager.enqueue("需要清理");
            assertTrue(started.await(3, TimeUnit.SECONDS));
            try {
                assertTrue(manager.cancel(task.id()));
                assertTrue(interrupted.await(3, TimeUnit.SECONDS));
                assertFalse(manager.find(task.id()).orElseThrow().terminal(), "退出前只能显示取消请求");
            } finally { release.countDown(); }
            assertEquals(TaskStatus.CANCELED, awaitTerminal(manager, task.id()).status());
        }
    }

    @Test void queuedCancellationNeverRunsAndWorkspaceAliasResolves() throws Exception {
        Path root = Files.createDirectory(temp.resolve("root"));
        Path alias = temp.resolve("alias");
        Files.createSymbolicLink(alias, root);
        Path db = temp.resolve("tasks.db");
        AtomicInteger calls = new AtomicInteger();
        String id;
        try (var first = manager(db, alias, p -> { calls.incrementAndGet(); return "完成"; })) {
            var task = first.enqueue("取消排队");
            id = task.id();
            assertEquals(root.toRealPath().toString(), task.workspace());
            assertTrue(first.cancel(id));
            first.start();
            awaitTerminal(first, first.enqueue("用于等待调度").id());
            assertEquals(1, calls.get());
        }
        try (var second = manager(db, root, p -> "完成")) {
            assertEquals(TaskStatus.CANCELED, second.find(id).orElseThrow().status());
        }
    }

    @Test void closeCancelsEveryTaskEvenWhenDatabaseIsWriteLocked() throws Exception {
        Path db = temp.resolve("locked.db");
        var started = new CountDownLatch(2);
        var canceled = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var manager = new DurableTaskManager(db, temp, execution -> {
            try (var registration = execution.cancellation().onCancel(canceled::countDown)) {
                started.countDown();
                release.await();
                return new TaskOutcome(TaskStatus.CANCELED, "", "用户取消");
            }
        }, 2);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor();
             var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement()) {
            manager.start();
            manager.enqueue("关闭一");
            manager.enqueue("关闭二");
            assertTrue(started.await(3, TimeUnit.SECONDS));
            statement.execute("BEGIN IMMEDIATE");
            var closing = pool.submit(manager::close);
            try {
                assertTrue(canceled.await(5, TimeUnit.SECONDS), "数据库故障不能阻止取消全部本地执行");
            } finally {
                statement.execute("ROLLBACK");
                release.countDown();
            }
            assertDoesNotThrow(() -> closing.get(10, TimeUnit.SECONDS));
            assertFalse(manager.isRunning());
        } finally { release.countDown(); manager.close(); }
    }

    @Test void incompatibleSchemaIsRejectedWithoutChangingExistingData() throws Exception {
        Path db = temp.resolve("legacy.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE runtime_tasks (id TEXT PRIMARY KEY, prompt TEXT)");
            statement.execute("INSERT INTO runtime_tasks VALUES ('old', '保留历史内容')");
            var error = assertThrows(SQLException.class, () -> manager(db, temp, p -> "不能执行"));
            assertTrue(error.getMessage().contains("结构不兼容"));
            try (var result = statement.executeQuery("SELECT prompt FROM runtime_tasks WHERE id = 'old'")) {
                assertTrue(result.next());
                assertEquals("保留历史内容", result.getString(1));
            }
            try (var result = statement.executeQuery("SELECT count(*) FROM pragma_table_info('runtime_tasks')")) {
                assertTrue(result.next());
                assertEquals(2, result.getInt(1));
            }
        }
    }

    static DurableTask awaitTerminal(DurableTaskManager manager, String id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var task = manager.find(id).orElseThrow();
            if (task.terminal()) return task;
            Thread.sleep(10);
        }
        throw new AssertionError("任务未进入终态: " + manager.find(id));
    }
}
