package com.xhlcli.tool.local;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhlcli.model.ToolMetadata.RiskLevel;
import com.xhlcli.model.ToolOutput;
import com.xhlcli.snapshot.SnapshotConfig;
import com.xhlcli.snapshot.SnapshotService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RevertTurnToolTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void metadataIsHighRisk() {
        Path project = tempDir.resolve("proj");
        try (SnapshotService service = SnapshotService.forProject(project)) {
            RevertTurnTool tool = new RevertTurnTool(service);
            assertEquals("revert_turn", tool.definition().name());
            assertEquals(RiskLevel.HIGH, tool.definition().metadata().riskLevel());
        }
    }

    @Test
    void executesRestoreWithOffset() throws Exception {
        Path project = tempDir.resolve("proj-restore");
        Path snapshots = tempDir.resolve("snaps");
        Files.createDirectories(project);

        Path file = project.resolve("code.txt");
        Files.writeString(file, "original");

        SnapshotConfig config = new SnapshotConfig(true, snapshots, 50, List.of(".git"));
        try (SnapshotService service = SnapshotService.forProject(project, config)) {
            // Turn 1
            service.snapshotBeforeTurn("t1", "turn 1 start");
            Files.writeString(file, "modified 1");
            service.manager().postTurnSnapshot("t1", "turn 1 end");

            // Turn 2
            service.snapshotBeforeTurn("t2", "turn 2 start");
            Files.writeString(file, "modified 2");
            service.manager().postTurnSnapshot("t2", "turn 2 end");

            RevertTurnTool tool = new RevertTurnTool(service);
            ObjectNode args = mapper.createObjectNode();
            args.put("offset", 1); // restore to turn 2 start

            ToolOutput output = tool.execute(args, null);
            assertTrue(output.summary().contains("已恢复到快照"));
            assertEquals("modified 1", Files.readString(file));
        }
    }

    @Test
    void throwsExceptionWhenRestoreFails() throws Exception {
        Path project = tempDir.resolve("proj-fail");
        Files.createDirectories(project);

        try (SnapshotService service = SnapshotService.forProject(project)) {
            RevertTurnTool tool = new RevertTurnTool(service);
            ObjectNode args = mapper.createObjectNode();
            args.put("offset", 99); // nonexistent

            RuntimeException ex = assertThrows(RuntimeException.class, () -> tool.execute(args, null));
            assertTrue(ex.getMessage().contains("找不到"));
        }
    }
}
