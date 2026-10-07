package com.xhlcli.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhlcli.model.ToolOutput;
import com.xhlcli.tool.local.RevertTurnTool;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotGoldenTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Golden Test: 全链路多轮修改、引入破坏、自愈恢复与宿主 Git 零污染验证")
    void fullTurnLifecycleAndHostGitIsolationGoldenTest() throws Exception {
        Path projectRoot = tempDir.resolve("my-real-project");
        Path snapshotsRoot = tempDir.resolve("isolated-snapshots");
        Files.createDirectories(projectRoot);

        // 1. 初始化宿主真实 Git 仓库与业务基线代码
        Path appFile = projectRoot.resolve("src").resolve("App.java");
        Path readmeFile = projectRoot.resolve("README.md");
        Files.createDirectories(appFile.getParent());
        Files.writeString(appFile, "public class App { public static void main(String[] args) {} }");
        Files.writeString(readmeFile, "# Project Initial Readme");

        String hostInitialCommitId;
        try (Git hostGit = Git.init().setDirectory(projectRoot.toFile()).call()) {
            hostGit.add().addFilepattern(".").call();
            var commit = hostGit.commit().setMessage("Initial commit by user").call();
            hostInitialCommitId = commit.getId().getName();
        }

        // 2. 初始化快照配置与服务
        SnapshotConfig config = new SnapshotConfig(true, snapshotsRoot, 50, List.of(".git", "target"));
        try (SnapshotService snapshotService = SnapshotService.forProject(projectRoot, config)) {
            RevertTurnTool revertTool = new RevertTurnTool(snapshotService);

            // ==========================================
            // Turn 1: 正常功能开发（添加 Utils.java，修改 App.java）
            // ==========================================
            String turn1Id = "react-turn-1";
            snapshotService.snapshotBeforeTurn(turn1Id, "Add Utils class");

            Path utilsFile = projectRoot.resolve("src").resolve("Utils.java");
            Files.writeString(utilsFile, "public class Utils { public static String id() { return \"42\"; } }");
            Files.writeString(appFile, "public class App { public static void main(String[] args) { Utils.id(); } }");

            snapshotService.snapshotAfterTurnAsync(turn1Id, "Add Utils class");
            snapshotService.awaitIdle();

            assertEquals("public class Utils { public static String id() { return \"42\"; } }", Files.readString(utilsFile));

            // ==========================================
            // Turn 2: 异常事故（误删 README.md，破坏 App.java，产生垃圾文件）
            // ==========================================
            String turn2Id = "react-turn-2";
            snapshotService.snapshotBeforeTurn(turn2Id, "Refactor App with mistakes");

            // 引入破坏
            Files.delete(readmeFile);
            Files.writeString(appFile, "BROKEN SYNTAX ERROR $$$$");
            Path junkFile = projectRoot.resolve("temp_junk.log");
            Files.writeString(junkFile, "debug trace log that should not exist");

            snapshotService.snapshotAfterTurnAsync(turn2Id, "Refactor App with mistakes");
            snapshotService.awaitIdle();

            // 确认当前项目已遭到破坏
            assertFalse(Files.exists(readmeFile), "README.md 此时应已被删除");
            assertTrue(Files.readString(appFile).contains("BROKEN SYNTAX ERROR"));
            assertTrue(Files.exists(junkFile));

            // ==========================================
            // 3. 执行自愈回滚：调用 RevertTurnTool (回滚 offset=1，即回到 Turn 2 之前)
            // ==========================================
            ObjectNode args = mapper.createObjectNode();
            args.put("offset", 1);
            ToolOutput output = revertTool.execute(args, null);

            assertNotNull(output);
            assertTrue(output.summary().contains("已恢复到快照"));

            // 验证磁盘文件状态 100% 还原至 Turn 1 结束时的完好状态
            assertTrue(Files.exists(readmeFile), "被误删的 README.md 必须被恢复");
            assertEquals("# Project Initial Readme", Files.readString(readmeFile));
            assertEquals("public class App { public static void main(String[] args) { Utils.id(); } }", Files.readString(appFile));
            assertTrue(Files.exists(utilsFile));
            assertFalse(Files.exists(junkFile), "Turn 2 产生的新增垃圾文件必须被彻底清理");

            // 验证已生成 pre-restore 保护快照
            List<TurnSnapshot> allSnapshots = snapshotService.listSnapshots(20);
            boolean hasPreRestore = allSnapshots.stream().anyMatch(s -> s.phase() == SnapshotPhase.PRE_RESTORE);
            assertTrue(hasPreRestore, "在执行恢复前必须产生 PRE_RESTORE 保护快照");

            // ==========================================
            // 4. 宿主 Git 零污染零修改断言
            // ==========================================
            try (Git hostGit = Git.open(projectRoot.toFile())) {
                var hostHead = hostGit.getRepository().resolve("HEAD");
                assertNotNull(hostHead);
                assertEquals(hostInitialCommitId, hostHead.getName(), "宿主 Git 的 HEAD Commit 必须原封不动");
                var status = hostGit.status().call();
                // 宿主 Git 工作区此时应感知到 Turn 1 的更改（App.java, Utils.java），但绝无任何快照相关的 commit 或配置污染
                assertTrue(status.getUntracked().contains("src/Utils.java"));
                assertFalse(status.getUntracked().contains(".git"));
                assertFalse(status.getUntracked().contains(".xhlcli"));
            }

            // ==========================================
            // 5. 进一步执行二次回滚至最原始状态（回到 Turn 1 之前）
            // ==========================================
            RestoreResult resTurn1 = snapshotService.restorePreTurn(2);
            assertTrue(resTurn1.success());

            // 验证 App.java 完全回到原始无 Utils 引用的状态
            assertEquals("public class App { public static void main(String[] args) {} }", Files.readString(appFile));
            assertFalse(Files.exists(utilsFile), "Turn 1 产生的 Utils.java 此时必须被删除");
        }
    }
}
