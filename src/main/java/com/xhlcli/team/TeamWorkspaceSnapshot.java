package com.xhlcli.team;

import com.xhlcli.llm.CancellationToken;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

/** 只读记录文件指纹，不复制源码内容，不跟随符号链接，不回滚工作区。 */
final class TeamWorkspaceSnapshot {
    private static final Set<String> EXCLUDED = Set.of(".git", ".xhlcli", "target", "node_modules", "dist", "build");
    private final Map<String, String> files;
    private final boolean complete;
    private TeamWorkspaceSnapshot(Map<String, String> files, boolean complete) {
        this.files = Map.copyOf(files); this.complete = complete;
    }
    boolean complete() { return complete; }

    static TeamWorkspaceSnapshot capture(Path directory, CancellationToken token) {
        if (directory == null) return new TeamWorkspaceSnapshot(Map.of(), false);
        Map<String, String> files = new TreeMap<>();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        long[] bytes = {0};
        try {
            Path root = directory.toRealPath();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                private void check() throws IOException {
                    if (token.isCancelled() || System.nanoTime() > deadline || files.size() >= 20000
                            || bytes[0] > 256L * 1024 * 1024) throw new IOException("文件证据达到采集边界");
                }
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    check();
                    return !dir.equals(root) && EXCLUDED.contains(dir.getFileName().toString())
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    check();
                    String fingerprint;
                    if (attrs.isSymbolicLink()) fingerprint = "符号链接:" + Files.readSymbolicLink(file);
                    else if (attrs.isRegularFile()) {
                        try {
                            var digest = MessageDigest.getInstance("SHA-256");
                            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                                byte[] buffer = new byte[8192]; int n;
                                while ((n = input.read(buffer)) != -1) {
                                    bytes[0] += n; check(); digest.update(buffer, 0, n);
                                }
                            }
                            fingerprint = "SHA-256:" + HexFormat.of().formatHex(digest.digest());
                        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
                    } else fingerprint = "特殊文件";
                    files.put(root.relativize(file).toString(), fingerprint);
                    return FileVisitResult.CONTINUE;
                }
            });
            return new TeamWorkspaceSnapshot(files, true);
        } catch (IOException | RuntimeException e) {
            return new TeamWorkspaceSnapshot(files, false);
        }
    }

    String changesSince(TeamWorkspaceSnapshot before) {
        if (!complete || before == null || !before.complete) return null;
        Set<String> paths = new TreeSet<>(before.files.keySet()); paths.addAll(files.keySet());
        StringBuilder changes = new StringBuilder("工作区文件变化（相对本轮开始，文件指纹 SHA-256；不含生成物与内部目录）：\n");
        int count = 0;
        for (String path : paths) {
            if (Objects.equals(before.files.get(path), files.get(path))) continue;
            if (++count > 100 || changes.length() > 20000) return null;
            String action = !before.files.containsKey(path) ? "新增" : !files.containsKey(path) ? "删除" : "修改";
            changes.append(action).append(' ').append(path).append("；当前 ").append(files.getOrDefault(path, "不存在")).append('\n');
        }
        if (count == 0) changes.append("未观察到文件内容变化。\n");
        return changes.toString();
    }
}
