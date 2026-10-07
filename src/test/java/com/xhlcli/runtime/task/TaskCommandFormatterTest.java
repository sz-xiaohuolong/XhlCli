package com.xhlcli.runtime.task;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskCommandFormatterTest {

    @TempDir
    Path tempDir;

    private DurableTaskManager manager;

    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = tempDir.resolve("test_tasks.db");
        // 快速同步 runner 用于测试
        manager = new DurableTaskManager(dbPath, prompt -> "Execution result for: " + prompt, 1);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (manager != null) {
            manager.close();
        }
    }

    @Test
    void shouldHandleUninitializedManager() {
        String output = TaskCommandFormatter.handle(null, "list");
        assertTrue(output.contains("未初始化"));
    }

    @Test
    void shouldShowUsageOnUnknownSubcommand() {
        String output = TaskCommandFormatter.handle(manager, "unknown_action");
        assertTrue(output.contains("未知 /task 子命令"));
        assertTrue(output.contains("可用命令："));
    }

    @Test
    void shouldListEmptyTasks() {
        String output = TaskCommandFormatter.handle(manager, "list");
        assertTrue(output.contains("暂无后台任务"));
    }

    @Test
    void shouldAddAndListTasks() {
        String addOutput = TaskCommandFormatter.handle(manager, "add 跑自动化回归测试");
        assertTrue(addOutput.contains("后台任务已提交"));
        assertTrue(addOutput.contains("/task log"));

        String listOutput = TaskCommandFormatter.handle(manager, "list");
        assertTrue(listOutput.contains("跑自动化回归测试"));
        assertTrue(listOutput.contains("enqueued") || listOutput.contains("running"));
    }

    @Test
    void shouldRequirePromptForAdd() {
        String output = TaskCommandFormatter.handle(manager, "add");
        assertTrue(output.contains("任务内容不能为空"));
    }

    @Test
    void shouldShowTaskLog() {
        DurableTask task = manager.enqueue("分析内存占用");
        String logOutput = TaskCommandFormatter.handle(manager, "log " + task.id());

        assertTrue(logOutput.contains(task.id()));
        assertTrue(logOutput.contains("分析内存占用"));

        String notFoundOutput = TaskCommandFormatter.handle(manager, "log task_non_existent");
        assertTrue(notFoundOutput.contains("未找到后台任务"));
    }

    @Test
    void shouldCancelTask() {
        DurableTask task = manager.enqueue("超长计算任务");
        String cancelOutput = TaskCommandFormatter.handle(manager, "cancel " + task.id());
        assertTrue(cancelOutput.contains("取消"));

        String notFoundCancel = TaskCommandFormatter.handle(manager, "cancel task_not_found");
        assertTrue(notFoundCancel.contains("未找到"));
    }
}
