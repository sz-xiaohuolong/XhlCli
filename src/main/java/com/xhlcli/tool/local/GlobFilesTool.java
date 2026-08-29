package com.xhlcli.tool.local;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.tool.Tool;
import com.xhlcli.model.ToolDefinition;
import com.xhlcli.model.ToolMetadata;
import com.xhlcli.model.ToolMetadata.RiskLevel;
import com.xhlcli.model.ToolOutput;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class GlobFilesTool implements Tool {
    private static final Set<String> EXCLUDED_DIRS = Set.of(
            ".git", ".xhlcli", "target", "node_modules", "dist", "build", "coverage", ".idea", ".gradle"
    );

    public static ToolDefinition createDefinition() {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("type", "object");

        ObjectNode props = params.putObject("properties");
        props.putObject("pattern").put("type", "string");
        props.putObject("path").put("type", "string");
        props.putObject("max_results").put("type", "integer");

        params.putArray("required").add("pattern");

        ToolMetadata meta = new ToolMetadata(RiskLevel.LOW, true, false, true, "glob");
        return new ToolDefinition("glob_files", "按文件名 glob 查找项目内文件（只读、实时、尊重常见忽略目录）；适合先定位候选文件，例如 **/*Service.java", params, meta);
    }

    private final WorkspacePathResolver resolver;

    public GlobFilesTool(WorkspacePathResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public ToolDefinition definition() {
        return createDefinition();
    }

    @Override
    public ToolOutput execute(JsonNode arguments, CancellationToken cancellationToken) throws Exception {
        String pattern = arguments.get("pattern").asText().trim();
        String pathStr = arguments.has("path") ? arguments.get("path").asText() : ".";
        int parsedMax = arguments.has("max_results") ? arguments.get("max_results").asInt() : 50;
        final int maxResults = Math.max(1, Math.min(parsedMax, 200));

        Path root = resolver.resolveSafe(pathStr);
        Path projectRoot = resolver.projectRoot();

        String normalizedPattern = normalizeGlob(pattern);
        String normalizedFileName = normalizeFileNameGlob(pattern);
        PathMatcher matcher = projectRoot.getFileSystem().getPathMatcher("glob:" + normalizedPattern);
        PathMatcher fileNameMatcher = projectRoot.getFileSystem().getPathMatcher("glob:" + normalizedFileName);

        List<Path> matchedFiles = new ArrayList<>();
        boolean[] reachedLimit = {false};

        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (cancellationToken != null && cancellationToken.isCancelled()) return FileVisitResult.TERMINATE;
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (!dir.equals(projectRoot) && EXCLUDED_DIRS.contains(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (cancellationToken != null && cancellationToken.isCancelled()) return FileVisitResult.TERMINATE;
                    if (matchedFiles.size() >= maxResults) {
                        reachedLimit[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    Path relative = projectRoot.relativize(file);
                    if (matcher.matches(relative) || fileNameMatcher.matches(file.getFileName())) {
                        matchedFiles.add(relative);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("Error traversing files: " + e.getMessage(), e);
        }

        if (matchedFiles.isEmpty()) {
            return new ToolOutput("未找到匹配文件: " + pattern, JsonNodeFactory.instance.objectNode(), "");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("匹配文件 ").append(matchedFiles.size()).append(" 个");
        if (reachedLimit[0] || matchedFiles.size() >= maxResults) {
            sb.append("（已达到上限 ").append(maxResults).append("）");
        }
        sb.append(":\n");
        for (int i = 0; i < matchedFiles.size(); i++) {
            sb.append(i + 1).append(". ").append(matchedFiles.get(i).toString()).append("\n");
        }

        return new ToolOutput(sb.toString().trim(), JsonNodeFactory.instance.objectNode(), "");
    }

    private static String normalizeGlob(String pattern) {
        String normalized = pattern == null ? "**/*" : pattern.replace('\\', '/').trim();
        if (normalized.isEmpty()) {
            return "**/*";
        }
        if (!normalized.contains("/") && !normalized.startsWith("**")) {
            return "**/" + normalized;
        }
        return normalized;
    }

    private static String normalizeFileNameGlob(String pattern) {
        String normalized = pattern == null ? "*" : pattern.replace('\\', '/').trim();
        if (normalized.isEmpty()) {
            return "*";
        }
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }
}
