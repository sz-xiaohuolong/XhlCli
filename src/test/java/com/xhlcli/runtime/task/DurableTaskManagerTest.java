package com.xhlcli.runtime.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurableTaskManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void enqueueAndListTasks() throws Exception {
        Path db = tempDir.resolve("tasks.db");
        try (DurableTaskManager manager = new DurableTaskManager(db, p -> "done", 1)) {
            DurableTask t1 = manager.enqueue("task one");
            DurableTask t2 = manager.enqueue("task two");

            assertNotNull(t1.id());
            assertEquals(TaskStatus.ENQUEUED, t1.status());
            assertEquals("task one", t1.prompt());

            List<DurableTask> list = manager.list(10);
            assertEquals(2, list.size());
            assertEquals(t2.id(), list.get(0).id()); // DESC order
            assertEquals(t1.id(), list.get(1).id());

            Optional<DurableTask> found = manager.find(t1.id());
            assertTrue(found.isPresent());
            assertEquals(t1.id(), found.get().id());
        }
    }

    @Test
    void workerExecutesTaskToCompletion() throws Exception {
        Path db = tempDir.resolve("exec.db");
        CountDownLatch doneLatch = new CountDownLatch(1);
        TaskRunner runner = prompt -> {
            doneLatch.countDown();
            return "RESULT_FOR_" + prompt;
        };

        try (DurableTaskManager manager = new DurableTaskManager(db, runner, 2)) {
            manager.start();
            DurableTask task = manager.enqueue("hello async task");

            assertTrue(doneLatch.await(3, TimeUnit.SECONDS));

            // Wait a little for DB update to complete
            DurableTask completed = null;
            for (int i = 0; i < 30; i++) {
                completed = manager.find(task.id()).orElse(null);
                if (completed != null && completed.terminal()) {
                    break;
                }
                Thread.sleep(50);
            }

            assertNotNull(completed);
            assertEquals(TaskStatus.COMPLETED, completed.status());
            assertEquals("RESULT_FOR_hello async task", completed.result());
            assertNotNull(completed.startedAt());
            assertNotNull(completed.finishedAt());
            assertTrue(completed.durationMs() >= 0);
        }
    }

    @Test
    void cancelRunningTask() throws Exception {
        Path db = tempDir.resolve("cancel.db");
        CountDownLatch startLatch = new CountDownLatch(1);
        TaskRunner runner = prompt -> {
            startLatch.countDown();
            Thread.sleep(5000);
            return "never returned";
        };

        try (DurableTaskManager manager = new DurableTaskManager(db, runner, 1)) {
            manager.start();
            DurableTask task = manager.enqueue("cancel me");

            assertTrue(startLatch.await(3, TimeUnit.SECONDS));

            boolean canceled = manager.cancel(task.id());
            assertTrue(canceled);

            DurableTask finalTask = null;
            for (int i = 0; i < 30; i++) {
                finalTask = manager.find(task.id()).orElse(null);
                if (finalTask != null && finalTask.status() == TaskStatus.CANCELED) {
                    break;
                }
                Thread.sleep(50);
            }

            assertNotNull(finalTask);
            assertEquals(TaskStatus.CANCELED, finalTask.status());
            assertFalse(manager.cancel(task.id()), "Canceling already terminal task should return false");
        }
    }

    @Test
    void recoversOrphanRunningTasksOnStartup() throws Exception {
        Path db = tempDir.resolve("recover.db");
        // 1. Manually insert a RUNNING task to simulate crash
        try (DurableTaskManager setupManager = new DurableTaskManager(db, p -> "ok", 1)) {
            setupManager.enqueue("task that was running when process crashed");
        }

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             PreparedStatement ps = conn.prepareStatement("UPDATE runtime_tasks SET status = 'running'")) {
            ps.executeUpdate();
        }

        // 2. Start a new manager on the same db - it should recover RUNNING tasks back to ENQUEUED
        try (DurableTaskManager newManager = new DurableTaskManager(db, p -> "recovered result", 1)) {
            List<DurableTask> list = newManager.list(10);
            assertEquals(1, list.size());
            assertEquals(TaskStatus.ENQUEUED, list.get(0).status(), "Orphan running task must be recovered to ENQUEUED");

            // Now start the manager and verify it executes
            CountDownLatch latch = new CountDownLatch(1);
            // Replace runner behavior by waiting for completion
            newManager.start();

            DurableTask completed = null;
            for (int i = 0; i < 30; i++) {
                completed = newManager.find(list.get(0).id()).orElse(null);
                if (completed != null && completed.terminal()) {
                    break;
                }
                Thread.sleep(50);
            }

            assertNotNull(completed);
            assertEquals(TaskStatus.COMPLETED, completed.status());
            assertEquals("recovered result", completed.result());
        }
    }

    @Test
    void rejectsBlankPrompt() throws Exception {
        Path db = tempDir.resolve("err.db");
        try (DurableTaskManager manager = new DurableTaskManager(db, p -> "ok", 1)) {
            assertThrows(IllegalArgumentException.class, () -> manager.enqueue("  "));
            assertThrows(IllegalArgumentException.class, () -> manager.enqueue(null));
        }
    }
}
