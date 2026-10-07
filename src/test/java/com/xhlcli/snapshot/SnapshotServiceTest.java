package com.xhlcli.snapshot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void runTurnExecutesSupplierAndWritesSnapshots() throws Exception {
        Path project = tempDir.resolve("proj");
        Path snapshots = tempDir.resolve("snaps");
        Files.createDirectories(project);

        SnapshotConfig config = new SnapshotConfig(true, snapshots, 50, List.of(".git"));
        try (SnapshotService service = SnapshotService.forProject(project, config)) {
            String res = service.runTurn("react", "create file", () -> {
                Files.writeString(project.resolve("test.txt"), "hello world");
                return "completed";
            });

            assertEquals("completed", res);
            service.awaitIdle();

            List<TurnSnapshot> all = service.listSnapshots(10);
            assertEquals(2, all.size());
            assertEquals(SnapshotPhase.POST_TURN, all.get(0).phase());
            assertEquals(SnapshotPhase.PRE_TURN, all.get(1).phase());

            String status = service.status();
            assertTrue(status.contains("Side-Git 快照状态"));

            // Test restore
            RestoreResult restoreRes = service.restorePreTurn(1);
            assertTrue(restoreRes.success());
            assertFalse(Files.exists(project.resolve("test.txt")));

            // Test clean
            String cleanMsg = service.clean();
            assertTrue(cleanMsg.contains("已清理"));
        }
    }

    @Test
    void disabledServiceDoesNotCreateSnapshots() throws Exception {
        Path project = tempDir.resolve("proj-dis");
        Path snapshots = tempDir.resolve("snaps");
        Files.createDirectories(project);

        SnapshotConfig config = new SnapshotConfig(false, snapshots, 50, List.of(".git"));
        try (SnapshotService service = SnapshotService.forProject(project, config)) {
            String res = service.runTurn("chat", "some prompt", () -> "answer");
            assertEquals("answer", res);
            service.awaitIdle();

            List<TurnSnapshot> all = service.listSnapshots(10);
            assertTrue(all.isEmpty());
        }
    }
}
