package com.xhlcli.policy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathGuardTest {

    @Test
    void allowsValidRelativeAndAbsolutePathsWithinRoot(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("src/main/Test.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "content");

        PathGuard guard = new PathGuard(tempDir);

        Path resolvedRelative = guard.resolveSafe("src/main/Test.java");
        assertEquals(file.toRealPath(), resolvedRelative);

        Path resolvedAbsolute = guard.resolveSafe(file.toString());
        assertEquals(file.toRealPath(), resolvedAbsolute);

        assertNull(guard.checkSafe("src/main/Test.java"));
    }

    @Test
    void rejectsParentTraversalEscapes(@TempDir Path tempDir) {
        PathGuard guard = new PathGuard(tempDir);

        assertThrows(PolicyException.class, () -> guard.resolveSafe("../secret.txt"));
        assertThrows(PolicyException.class, () -> guard.resolveSafe("foo/../../secret.txt"));
        assertNotNull(guard.checkSafe("../secret.txt"));
    }

    @Test
    void rejectsAbsolutePathEscapes(@TempDir Path tempDir) {
        PathGuard guard = new PathGuard(tempDir);

        assertThrows(PolicyException.class, () -> guard.resolveSafe("/etc/passwd"));
        assertNotNull(guard.checkSafe("/etc/passwd"));
    }

    @Test
    void rejectsSymlinkEscapePointingOutsideRoot(@TempDir Path tempDir) throws IOException {
        Path outsideDir = Files.createTempDirectory("outside-dir");
        Path outsideFile = outsideDir.resolve("secret.txt");
        Files.writeString(outsideFile, "secret");

        Path linkInside = tempDir.resolve("link-out");
        try {
            Files.createSymbolicLink(linkInside, outsideDir);
        } catch (UnsupportedOperationException | IOException e) {
            // 某些系统可能不支持软链接，跳过软链接特异测试
            return;
        }

        PathGuard guard = new PathGuard(tempDir);

        assertThrows(PolicyException.class, () -> guard.resolveSafe("link-out/secret.txt"));
        assertThrows(PolicyException.class, () -> guard.resolveSafe("link-out/non-existing.txt"));
    }

    @Test
    void rejectsNullOrEmptyPath(@TempDir Path tempDir) {
        PathGuard guard = new PathGuard(tempDir);

        assertThrows(PolicyException.class, () -> guard.resolveSafe(null));
        assertThrows(PolicyException.class, () -> guard.resolveSafe("   "));
    }
}
