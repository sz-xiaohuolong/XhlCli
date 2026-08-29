package com.xhlcli.tool.local;

import java.nio.file.Path;
import java.util.Objects;

public final class WorkspacePathResolver {
    private final Path projectRoot;

    public WorkspacePathResolver(Path projectRoot) {
        this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
    }

    public Path projectRoot() { return projectRoot; }

    public Path resolveSafe(String path) {
        if (path == null || path.isBlank()) return projectRoot;
        Path resolved = projectRoot.resolve(path).normalize();
        if (!resolved.startsWith(projectRoot)) {
            throw new IllegalArgumentException("Path is outside project root: " + path);
        }
        return resolved;
    }
}
