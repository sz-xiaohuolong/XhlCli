package com.xhlcli.snapshot;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotConfigTest {

    @AfterEach
    void tearDown() {
        System.clearProperty("xhlcli.snapshot.enabled");
        System.clearProperty("xhlcli.snapshot.dir");
        System.clearProperty("xhlcli.snapshot.max");
        System.clearProperty("xhlcli.snapshot.excludes");
    }

    @Test
    void defaultValuesAreLoaded() {
        SnapshotConfig config = SnapshotConfig.fromEnvironment();
        assertTrue(config.enabled());
        assertEquals(50, config.maxSnapshots());
        assertTrue(config.excludes().contains(".git"));
        assertTrue(config.excludes().contains("target"));
        assertTrue(config.excludes().contains("node_modules"));
    }

    @Test
    void systemPropertiesOverrideDefaults() {
        System.setProperty("xhlcli.snapshot.enabled", "false");
        System.setProperty("xhlcli.snapshot.max", "10");
        System.setProperty("xhlcli.snapshot.dir", "/tmp/snapshots");
        System.setProperty("xhlcli.snapshot.excludes", "custom_dir,*.tmp");

        SnapshotConfig config = SnapshotConfig.fromEnvironment();
        assertFalse(config.enabled());
        assertEquals(10, config.maxSnapshots());
        assertEquals(Path.of("/tmp/snapshots"), config.snapshotsRoot());
        assertTrue(config.excludes().contains("custom_dir"));
        assertTrue(config.excludes().contains("*.tmp"));
        assertTrue(config.excludes().contains(".git")); // defaults preserved
    }

    @Test
    void withMethodsProduceModifiedCopies() {
        SnapshotConfig base = new SnapshotConfig(true, Path.of("/base"), 20, List.of(".git"));
        SnapshotConfig modified = base.withEnabled(false).withMaxSnapshots(30);

        assertFalse(modified.enabled());
        assertEquals(30, modified.maxSnapshots());
        assertEquals(Path.of("/base"), modified.snapshotsRoot());
    }
}
