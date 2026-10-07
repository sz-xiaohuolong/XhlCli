package com.xhlcli.snapshot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SideGitRestoreTest {

    @TempDir
    Path tempDir;

    @Test
    void multiTurnRollbackWithOffset() throws Exception {
        Path project = tempDir.resolve("proj");
        Path snapshots = tempDir.resolve("snaps");
        Files.createDirectories(project);

        Path mainFile = project.resolve("Main.java");
        Files.writeString(mainFile, "version 0");

        SideGitManager manager = new SideGitManager(project,
                new SnapshotConfig(true, snapshots, 50, List.of(".git", "target")));

        // Turn 1
        manager.preTurnSnapshot("turn-1", "start turn 1");
        Files.writeString(mainFile, "version 1");
        manager.postTurnSnapshot("turn-1", "finish turn 1");

        // Turn 2
        manager.preTurnSnapshot("turn-2", "start turn 2");
        Files.writeString(mainFile, "version 2");
        Path extraFile = project.resolve("sub").resolve("Extra.java");
        Files.createDirectories(extraFile.getParent());
        Files.writeString(extraFile, "extra file in turn 2");
        manager.postTurnSnapshot("turn-2", "finish turn 2");

        // Restore to turn 2 start (offset 1)
        RestoreResult res1 = manager.restorePreTurn(1);
        assertTrue(res1.success());
        assertEquals("version 1", Files.readString(mainFile));
        assertFalse(Files.exists(extraFile)); // Extra.java created in turn 2 must be deleted
        assertFalse(Files.exists(project.resolve("sub"))); // empty dir should be pruned

        // Verify pre-restore snapshot was generated
        List<TurnSnapshot> all = manager.listSnapshots(10);
        assertTrue(all.stream().anyMatch(s -> s.phase() == SnapshotPhase.PRE_RESTORE));

        // Restore to turn 1 start (which now is at offset 2 among pre-turns, because pre-restore is pre-restore phase)
        List<TurnSnapshot> preTurns = manager.listPreTurnSnapshots(10);
        assertEquals(2, preTurns.size()); // turn-2 and turn-1
        assertEquals("turn-2", preTurns.get(0).turnId());
        assertEquals("turn-1", preTurns.get(1).turnId());

        RestoreResult res2 = manager.restorePreTurn(2);
        assertTrue(res2.success());
        assertEquals("version 0", Files.readString(mainFile));
    }

    @Test
    void restoreDeletedFilesBack() throws Exception {
        Path project = tempDir.resolve("proj-del");
        Path snapshots = tempDir.resolve("snaps");
        Files.createDirectories(project);

        Path fileA = project.resolve("FileA.txt");
        Files.writeString(fileA, "content A");

        SideGitManager manager = new SideGitManager(project,
                new SnapshotConfig(true, snapshots, 50, List.of(".git")));

        manager.preTurnSnapshot("turn-1", "pre delete");
        // User/agent deletes fileA
        Files.delete(fileA);
        assertFalse(Files.exists(fileA));
        manager.postTurnSnapshot("turn-1", "post delete");

        // Restore
        RestoreResult res = manager.restorePreTurn(1);
        assertTrue(res.success());
        assertTrue(Files.exists(fileA));
        assertEquals("content A", Files.readString(fileA));
    }

    @Test
    void restoreReturnsFailureWhenOffsetExceedsHistory() throws Exception {
        Path project = tempDir.resolve("proj-empty");
        Path snapshots = tempDir.resolve("snaps");
        Files.createDirectories(project);

        SideGitManager manager = new SideGitManager(project,
                new SnapshotConfig(true, snapshots, 50, List.of(".git")));

        RestoreResult res = manager.restorePreTurn(5);
        assertFalse(res.success());
        assertTrue(res.message().contains("找不到"));
    }

    @Test
    void restoreDisabledReturnsFailure() throws Exception {
        Path project = tempDir.resolve("proj-dis");
        Path snapshots = tempDir.resolve("snaps");
        Files.createDirectories(project);

        SideGitManager manager = new SideGitManager(project,
                new SnapshotConfig(false, snapshots, 50, List.of(".git")));

        RestoreResult res = manager.restorePreTurn(1);
        assertFalse(res.success());
        assertTrue(res.message().contains("已关闭"));
    }
}
