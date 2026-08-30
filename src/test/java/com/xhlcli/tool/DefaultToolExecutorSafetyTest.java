package com.xhlcli.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.hitl.ApprovalRequest;
import com.xhlcli.hitl.ApprovalResult;
import com.xhlcli.hitl.HitlHandler;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.model.ToolCall;
import com.xhlcli.model.ToolResult;
import com.xhlcli.model.ToolResultStatus;
import com.xhlcli.policy.AuditLog;
import com.xhlcli.policy.PathGuard;
import com.xhlcli.tool.local.ExecuteCommandTool;
import com.xhlcli.tool.local.ReadFileTool;
import com.xhlcli.tool.local.WorkspacePathResolver;
import com.xhlcli.tool.local.WriteFileTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultToolExecutorSafetyTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rejectsHardPolicyViolationsWithoutCallingHitl(@TempDir Path tempDir) {
        PathGuard pathGuard = new PathGuard(tempDir);
        WorkspacePathResolver resolver = new WorkspacePathResolver(tempDir);
        AuditLog auditLog = new AuditLog(tempDir.resolve("audit"));
        AtomicBoolean hitlCalled = new AtomicBoolean(false);

        HitlHandler hitlHandler = new HitlHandler() {
            @Override
            public ApprovalResult requestApproval(ApprovalRequest request) {
                hitlCalled.set(true);
                return ApprovalResult.approve();
            }

            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public void setEnabled(boolean enabled) {}
        };

        ToolRegistry registry = new ToolRegistry(List.of(
                new ExecuteCommandTool(resolver),
                new WriteFileTool(resolver)));

        DefaultToolExecutor executor = new DefaultToolExecutor(
                registry,
                new ToolSchemaValidator(mapper),
                new ToolResultBudget(1000, mapper),
                mapper,
                System::nanoTime,
                pathGuard,
                hitlHandler,
                auditLog);

        // 1. 命令黑名单
        ToolCall sudoCall = new ToolCall("call_1", "execute_command", "{\"command\":\"sudo apt-get install evil\"}");
        ToolResult sudoResult = executor.execute(sudoCall, new CancellationToken());
        assertEquals(ToolResultStatus.EXECUTION_ERROR, sudoResult.status());
        assertTrue(sudoResult.summary().contains("[POLICY] 策略拒绝"));
        assertFalse(hitlCalled.get());

        // 2. 路径越界
        ToolCall escapeCall = new ToolCall("call_2", "write_file", "{\"path\":\"/etc/passwd\",\"content\":\"hacked\"}");
        ToolResult escapeResult = executor.execute(escapeCall, new CancellationToken());
        assertEquals(ToolResultStatus.EXECUTION_ERROR, escapeResult.status());
        assertTrue(escapeResult.summary().contains("[POLICY] 策略拒绝"));
        assertFalse(hitlCalled.get());

        // 验证审计日志记录了两次 deny
        List<AuditLog.AuditEntry> audits = auditLog.readRecent(10);
        assertEquals(2, audits.size());
        assertEquals("deny", audits.get(0).outcome());
        assertEquals("deny", audits.get(1).outcome());
    }

    @Test
    void readOnlyToolsBypassHitlApproval(@TempDir Path tempDir) throws IOException {
        Path testFile = tempDir.resolve("hello.txt");
        Files.writeString(testFile, "hello world");

        PathGuard pathGuard = new PathGuard(tempDir);
        WorkspacePathResolver resolver = new WorkspacePathResolver(tempDir);
        AuditLog auditLog = new AuditLog(tempDir.resolve("audit"));
        AtomicBoolean hitlCalled = new AtomicBoolean(false);

        HitlHandler hitlHandler = new HitlHandler() {
            @Override
            public ApprovalResult requestApproval(ApprovalRequest request) {
                hitlCalled.set(true);
                return ApprovalResult.approve();
            }

            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public void setEnabled(boolean enabled) {}
        };

        ToolRegistry registry = new ToolRegistry(List.of(new ReadFileTool(resolver)));

        DefaultToolExecutor executor = new DefaultToolExecutor(
                registry,
                new ToolSchemaValidator(mapper),
                new ToolResultBudget(1000, mapper),
                mapper,
                System::nanoTime,
                pathGuard,
                hitlHandler,
                auditLog);

        ToolCall readCall = new ToolCall("call_1", "read_file", "{\"path\":\"hello.txt\"}");
        ToolResult readResult = executor.execute(readCall, new CancellationToken());

        assertEquals(ToolResultStatus.SUCCESS, readResult.status());
        assertTrue(readResult.summary().contains("hello world"));
        assertFalse(hitlCalled.get()); // 只读工具不触发 HITL
    }

    @Test
    void dangerousToolsRequestApprovalAndHandleUserDecisions(@TempDir Path tempDir) {
        PathGuard pathGuard = new PathGuard(tempDir);
        WorkspacePathResolver resolver = new WorkspacePathResolver(tempDir);
        AuditLog auditLog = new AuditLog(tempDir.resolve("audit"));

        ToolRegistry registry = new ToolRegistry(List.of(new WriteFileTool(resolver)));

        // 1. 用户拒绝场景
        HitlHandler rejectHitl = new HitlHandler() {
            @Override
            public ApprovalResult requestApproval(ApprovalRequest request) {
                return ApprovalResult.reject("禁止写入此文件");
            }

            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public void setEnabled(boolean enabled) {}
        };

        DefaultToolExecutor rejectExecutor = new DefaultToolExecutor(
                registry,
                new ToolSchemaValidator(mapper),
                new ToolResultBudget(1000, mapper),
                mapper,
                System::nanoTime,
                pathGuard,
                rejectHitl,
                auditLog);

        ToolCall writeCall = new ToolCall("call_1", "write_file", "{\"path\":\"a.txt\",\"content\":\"data\"}");
        ToolResult rejectResult = rejectExecutor.execute(writeCall, new CancellationToken());

        assertEquals(ToolResultStatus.EXECUTION_ERROR, rejectResult.status());
        assertTrue(rejectResult.summary().contains("[HITL] 操作已被用户拒绝: 禁止写入此文件"));
        assertFalse(Files.exists(tempDir.resolve("a.txt")));

        // 2. 用户批准场景
        HitlHandler approveHitl = new HitlHandler() {
            @Override
            public ApprovalResult requestApproval(ApprovalRequest request) {
                return ApprovalResult.approve();
            }

            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public void setEnabled(boolean enabled) {}
        };

        DefaultToolExecutor approveExecutor = new DefaultToolExecutor(
                registry,
                new ToolSchemaValidator(mapper),
                new ToolResultBudget(1000, mapper),
                mapper,
                System::nanoTime,
                pathGuard,
                approveHitl,
                auditLog);

        ToolResult approveResult = approveExecutor.execute(writeCall, new CancellationToken());
        assertEquals(ToolResultStatus.SUCCESS, approveResult.status());
        assertTrue(Files.exists(tempDir.resolve("a.txt")));
    }
}
