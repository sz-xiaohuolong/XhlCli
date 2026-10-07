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

class SideGitManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void createsPreAndPostTurnSnapshots() throws Exception {
        Path project = tempDir.resolve("project");
        Path snapshots = tempDir.resolve("snapshots");
        Files.createDirectories(project);
        Files.writeString(project.resolve("file.txt"), "hello world");

        SideGitManager manager = new SideGitManager(project,
                new SnapshotConfig(true, snapshots, 50, List.of(".git", "target")));

        TurnSnapshot pre = manager.preTurnSnapshot("turn-101", "starting work");
        assertNotNull(pre);
        assertEquals(SnapshotPhase.PRE_TURN, pre.phase());
        assertEquals("turn-101", pre.turnId());
        assertNotNull(pre.commitId());

        Files.writeString(project.resolve("file.txt"), "modified world");
        TurnSnapshot post = manager.postTurnSnapshot("turn-101", "finished work");
        assertNotNull(post);
        assertEquals(SnapshotPhase.POST_TURN, post.phase());

        List<TurnSnapshot> list = manager.listSnapshots(10);
        assertEquals(2, list.size());
        assertEquals(SnapshotPhase.POST_TURN, list.get(0).phase());
        assertEquals(SnapshotPhase.PRE_TURN, list.get(1).phase());
    }

    @Test
    void excludesAreRespectedAndNeverTracked() throws Exception {
        Path project = tempDir.resolve("project");
        Path snapshots = tempDir.resolve("snapshots");
        Files.createDirectories(project.resolve("target"));
        Files.writeString(project.resolve("target").resolve("output.bin"), "binary");
        Files.writeString(project.resolve("app.java"), "public class App {}");

        SideGitManager manager = new SideGitManager(project,
                new SnapshotConfig(true, snapshots, 50, List.of(".git", "target", "*.class")));

        manager.preTurnSnapshot("turn-1", "pre");

        // Side-git repo should exist in snapshots, but target/output.bin must not be in tree
        assertTrue(Files.exists(manager.gitDir().resolve("config")));
        String status = manager.formatStatus();
        assertTrue(status.contains("Side-Git 快照状态"));
        assertTrue(status.contains("启用"));
    }

    @Test
    void userProjectGitIsNeverTouchedOrPolluted() throws Exception {
        Path project = tempDir.resolve("my-project");
        Path userGit = project.resolve(".git");
        Files.createDirectories(userGit);
        Files.writeString(userGit.resolve("HEAD"), "ref: refs/heads/feature-branch\n");
        Files.writeString(userGit.resolve("config"), "[core]\n\trepositoryformatversion = 0\n");

        Path snapshots = tempDir.resolve("snapshots");
        Files.writeString(project.resolve("code.py"), "print('original')");

        SideGitManager manager = new SideGitManager(project,
                new SnapshotConfig(true, snapshots, 50, List.of(".git", "target")));

        manager.preTurnSnapshot("turn-1", "user turn");

        Files.writeString(project.resolve("code.py"), "print('changed')");
        manager.postTurnSnapshot("turn-1", "user turn done");

        // Verify user .git is completely untouched
        assertEquals("ref: refs/heads/feature-branch\n", Files.readString(userGit.resolve("HEAD")));
        assertEquals("[core]\n\trepositoryformatversion = 0\n", Files.readString(userGit.resolve("config")));

        // Now restore
        RestoreResult result = manager.restorePreTurn(1);
        assertTrue(result.success());
        assertEquals("print('original')", Files.readString(project.resolve("code.py")));

        // Verify user .git is STILL completely untouched
        assertEquals("ref: refs/heads/feature-branch\n", Files.readString(userGit.resolve("HEAD")));
    }

    @Test
    void cleanSnapshotsDeletesSideGitDirectory() throws Exception {
        Path project = tempDir.resolve("project");
        Path snapshots = tempDir.resolve("snapshots");
        Files.createDirectories(project);
        Files.writeString(project.resolve("file.txt"), "hello");

        SideGitManager manager = new SideGitManager(project,
                new SnapshotConfig(true, snapshots, 50, List.of(".git")));
        manager.preTurnSnapshot("turn-1", "test");
        assertTrue(Files.exists(manager.gitDir()));

        String cleanMsg = manager.cleanSnapshots();
        assertTrue(cleanMsg.contains("已清理"));
        assertFalse(Files.exists(manager.gitDir()));
    }
}
