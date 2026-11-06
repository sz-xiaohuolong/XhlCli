package com.xhlcli.desktop;

import com.xhlcli.snapshot.*;
import com.xhlcli.tool.local.WorkspacePathResolver;
import org.eclipse.jgit.diff.*;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 复用 Side-History，只撤销指定轮次发生且之后未被修改的文件。 */
public final class DesktopChanges {
    private final SideGitManager snapshots;
    private final WorkspacePathResolver paths;
    private final Path root;
    public DesktopChanges(Path workspace, Path home) {
        var defaults = SnapshotConfig.fromEnvironment();
        var excludes = new ArrayList<>(defaults.excludes());
        excludes.addAll(List.of(".env", ".env.*", ".xhlcli", "desktop/release"));
        snapshots = new SideGitManager(workspace, new SnapshotConfig(defaults.enabled(), home.resolve(".xhlcli/snapshots"), 50, excludes));
        root=workspace.toAbsolutePath().normalize();
        paths = new WorkspacePathResolver(root);
    }
    public String capture(String turn, boolean after) throws Exception {
        var snapshot = after ? snapshots.postTurnSnapshot(turn, "桌面轮次结束") : snapshots.preTurnSnapshot(turn, "桌面轮次开始");
        if (snapshot == null) throw new IllegalStateException("快照已关闭，本轮无法查看差异或撤销");
        return snapshot.commitId();
    }
    private Repository repository() throws Exception {
        return new FileRepositoryBuilder().setGitDir(snapshots.gitDir().toFile()).setWorkTree(snapshots.projectRoot().toFile()).build();
    }
    private List<DiffEntry> entries(Repository repository, String before, String after) throws Exception {
        if (before == null || after == null || before.isBlank() || after.isBlank()) throw new IllegalStateException("本轮没有完整快照");
        try (var walk = new RevWalk(repository); var formatter = new DiffFormatter(new ByteArrayOutputStream())) {
            formatter.setRepository(repository);
            return formatter.scan(walk.parseCommit(ObjectId.fromString(before)).getTree(), walk.parseCommit(ObjectId.fromString(after)).getTree());
        }
    }
    public List<Map<String, Object>> diff(String before, String after) throws Exception {
        try (var repository = repository()) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (var entry : entries(repository, before, after)) {
                var output = new ByteArrayOutputStream();
                try (var formatter = new DiffFormatter(output)) {
                    formatter.setRepository(repository);
                    formatter.setBinaryFileThreshold(512 * 1024);
                    formatter.format(entry);
                }
                String text = output.toString(StandardCharsets.UTF_8);
                result.add(Map.of("path", path(entry), "type", entry.getChangeType().name(), "diff",
                        text.length() > 200000 ? text.substring(0, 200000) + "\n差异过长，已截断显示。" : text));
            }
            return result;
        }
    }
    private String path(DiffEntry entry) { return entry.getChangeType() == DiffEntry.ChangeType.DELETE ? entry.getOldPath() : entry.getNewPath(); }
    private byte[] bytes(Repository repository, AbbreviatedObjectId id) throws Exception {
        return id.toObjectId().equals(ObjectId.zeroId()) ? null : repository.open(id.toObjectId()).getBytes(16 * 1024 * 1024);
    }
    public void undo(String before, String after) throws Exception {
        try (var repository = repository()) {
            var entries = entries(repository, before, after);
            for (var entry : entries) {verifyUnchanged(repository, entry);bytes(repository,entry.getOldId());}
            var backup=snapshots.preRestoreSnapshot("desktop-undo", "撤销前备份");
            int restored=0;
            try {
            for (var entry : entries) {
                verifyUnchanged(repository, entry);
                Path target = paths.resolveSafe(path(entry));
                byte[] previous = bytes(repository, entry.getOldId());
                if (previous == null) Files.deleteIfExists(target);
                else {
                    Files.createDirectories(target.getParent());
                    Path temp = Files.createTempFile(target.getParent(), ".xhlcli-undo-", ".tmp");
                    try {
                        Files.write(temp, previous);
                        if (Files.exists(target)) {
                            try { Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(target)); }
                            catch (UnsupportedOperationException ignored) {}
                        }
                        temp.toFile().setExecutable(entry.getOldMode().equals(FileMode.EXECUTABLE_FILE), false);
                        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } finally { Files.deleteIfExists(temp); }
                }
                restored++;
            }
            } catch(Exception failure) {
                throw new IllegalStateException("撤销未完成，已恢复 "+restored+" 个文件；其余文件保留当前状态。撤销前备份："
                        +(backup==null?"不可用":backup.commitId())+"。原因："+failure.getMessage(),failure);
            }
        }
    }
    private void verifyUnchanged(Repository repository, DiffEntry entry) throws Exception {
        if (entry.getNewMode().equals(FileMode.SYMLINK) || entry.getOldMode().equals(FileMode.SYMLINK)
                || entry.getNewMode().equals(FileMode.GITLINK) || entry.getOldMode().equals(FileMode.GITLINK))
            throw new IllegalStateException("链接或子模块需要人工处理：" + path(entry));
        Path target = paths.resolveSafe(path(entry));
        Path ancestor=root;
        if(Files.isSymbolicLink(ancestor))throw new IllegalStateException("撤销冲突：项目目录已替换为链接");
        Path relative=root.relativize(target);
        for(int i=0;i<relative.getNameCount()-1;i++) {
            ancestor=ancestor.resolve(relative.getName(i));
            if(Files.isSymbolicLink(ancestor))throw new IllegalStateException("撤销冲突：父目录包含符号链接："+path(entry));
        }
        byte[] expected = bytes(repository, entry.getNewId());
        byte[] current = Files.exists(target, LinkOption.NOFOLLOW_LINKS) && Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) ? Files.readAllBytes(target) : null;
        if (Files.isSymbolicLink(target) || Files.isDirectory(target) || !Arrays.equals(expected, current)
                || (expected != null && Files.isExecutable(target) != entry.getNewMode().equals(FileMode.EXECUTABLE_FILE)))
            throw new IllegalStateException("撤销冲突：文件在本轮后已修改，已保留现有内容：" + path(entry));
    }
}
