package com.xhlcli.tool.local;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class WorkspacePathResolverTest {

    @TempDir
    Path tempDir;

    @Test
    public void testRelativePathResolution() {
        WorkspacePathResolver resolver = new WorkspacePathResolver(tempDir);
        Path resolved = resolver.resolveSafe("src/main");
        assertEquals(tempDir.resolve("src/main").normalize(), resolved);
    }

    @Test
    public void testDotDotTraversalBlocked() {
        WorkspacePathResolver resolver = new WorkspacePathResolver(tempDir);
        assertThrows(IllegalArgumentException.class, () -> resolver.resolveSafe("../outside"));
    }

    @Test
    public void testAbsolutePathOutsideRootBlocked() {
        WorkspacePathResolver resolver = new WorkspacePathResolver(tempDir);
        assertThrows(IllegalArgumentException.class, () -> resolver.resolveSafe("/tmp/outside"));
    }

    @Test
    public void testNullOrBlankPathReturnsRoot() {
        WorkspacePathResolver resolver = new WorkspacePathResolver(tempDir);
        assertEquals(tempDir.toAbsolutePath().normalize(), resolver.resolveSafe(null));
        assertEquals(tempDir.toAbsolutePath().normalize(), resolver.resolveSafe(""));
        assertEquals(tempDir.toAbsolutePath().normalize(), resolver.resolveSafe("   "));
    }

    @Test
    public void testNormalNestedPathWorks() {
        WorkspacePathResolver resolver = new WorkspacePathResolver(tempDir);
        Path resolved = resolver.resolveSafe("a/b/c");
        assertEquals(tempDir.resolve("a/b/c").normalize(), resolved);
    }
}
