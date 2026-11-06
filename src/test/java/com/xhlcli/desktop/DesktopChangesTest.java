package com.xhlcli.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class DesktopChangesTest {
    @TempDir Path temp;
    @Test void undoPreservesEarlierChangesAndUnrelatedFiles() throws Exception {
        Path root = Files.createDirectory(temp.resolve("project"));
        Files.writeString(root.resolve("a.txt"), "用户已有修改\n");
        var changes = new DesktopChanges(root, temp.resolve("home"));
        String before = changes.capture("t", false);
        Files.writeString(root.resolve("a.txt"), "Agent 修改\n");
        Files.writeString(root.resolve("new.txt"), "新增\n");
        String after = changes.capture("t", true);
        Files.writeString(root.resolve("other.txt"), "用户后来创建\n");
        assertEquals(2, changes.diff(before, after).size());
        changes.undo(before, after);
        assertEquals("用户已有修改\n", Files.readString(root.resolve("a.txt")));
        assertFalse(Files.exists(root.resolve("new.txt")));
        assertEquals("用户后来创建\n", Files.readString(root.resolve("other.txt")));
    }
    @Test void overlappingUserEditRejectsWholeUndo() throws Exception {
        Path root = Files.createDirectory(temp.resolve("project"));
        Files.writeString(root.resolve("a.txt"), "原文");
        var changes = new DesktopChanges(root, temp.resolve("home"));
        String before = changes.capture("t", false);
        Files.writeString(root.resolve("a.txt"), "Agent");
        String after = changes.capture("t", true);
        Files.writeString(root.resolve("a.txt"), "用户后来编辑");
        assertThrows(IllegalStateException.class, () -> changes.undo(before, after));
        assertEquals("用户后来编辑", Files.readString(root.resolve("a.txt")));
    }
    @Test void restoresDeletionAndExecutableWithoutChangingGitIndex() throws Exception {
        Path root=Files.createDirectory(temp.resolve("git-project"));
        try(var git=org.eclipse.jgit.api.Git.init().setDirectory(root.toFile()).call()) {
            Path file=root.resolve("script.sh");Files.writeString(file,"用户已暂存\n");file.toFile().setExecutable(true);
            git.add().addFilepattern("script.sh").call();
            byte[] index=Files.readAllBytes(root.resolve(".git/index"));
            var changes=new DesktopChanges(root,temp.resolve("home"));String before=changes.capture("delete",false);
            Files.delete(file);String after=changes.capture("delete",true);
            changes.undo(before,after);
            assertEquals("用户已暂存\n",Files.readString(file));assertTrue(Files.isExecutable(file));
            assertArrayEquals(index,Files.readAllBytes(root.resolve(".git/index")));
        }
    }
    @Test void refusesLinkReplacementWithoutTouchingExternalFile() throws Exception {
        Path root=Files.createDirectory(temp.resolve("project"));Path outside=temp.resolve("outside");Files.writeString(outside,"外部内容");
        Path file=root.resolve("a.txt");Files.writeString(file,"原文");
        var changes=new DesktopChanges(root,temp.resolve("home"));String before=changes.capture("link",false);
        Files.writeString(file,"Agent");String after=changes.capture("link",true);
        Files.delete(file);Files.createSymbolicLink(file,outside);
        assertThrows(Exception.class,()->changes.undo(before,after));
        assertEquals("外部内容",Files.readString(outside));assertTrue(Files.isSymbolicLink(file));
    }
    @Test void refusesSymbolicLinkInParentAfterSnapshot() throws Exception {
        Path root=Files.createDirectory(temp.resolve("project"));Path sub=Files.createDirectory(root.resolve("sub"));
        Files.writeString(sub.resolve("a.txt"),"原文");
        var changes=new DesktopChanges(root,temp.resolve("home"));String before=changes.capture("parent",false);
        Files.writeString(sub.resolve("a.txt"),"Agent");String after=changes.capture("parent",true);
        Path outside=temp.resolve("outside");Files.move(sub,outside);Files.createSymbolicLink(sub,outside);
        assertThrows(Exception.class,()->changes.undo(before,after));
        assertEquals("Agent",Files.readString(outside.resolve("a.txt")));
    }
}
