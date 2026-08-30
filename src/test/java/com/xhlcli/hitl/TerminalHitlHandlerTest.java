package com.xhlcli.hitl;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalHitlHandlerTest {

    private TerminalHitlHandler createHandler(String input, boolean enabled) {
        BufferedReader reader = new BufferedReader(new StringReader(input));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream printStream = new PrintStream(out, true, StandardCharsets.UTF_8);
        return new TerminalHitlHandler(enabled, reader, printStream);
    }

    @Test
    void autoApprovesWhenDisabled() {
        TerminalHitlHandler handler = createHandler("", false);
        ApprovalRequest request = ApprovalRequest.of("execute_command", "{\"command\":\"ls\"}");
        ApprovalResult result = handler.requestApproval(request);

        assertTrue(result.isApproved());
        assertEquals(ApprovalResult.Decision.APPROVED, result.decision());
    }

    @Test
    void approvesSingleOperationOnEnterOrY() {
        TerminalHitlHandler handler1 = createHandler("\n", true);
        ApprovalResult result1 = handler1.requestApproval(ApprovalRequest.of("write_file", "{}"));
        assertTrue(result1.isApproved());
        assertEquals(ApprovalResult.Decision.APPROVED, result1.decision());

        TerminalHitlHandler handler2 = createHandler("y\n", true);
        ApprovalResult result2 = handler2.requestApproval(ApprovalRequest.of("write_file", "{}"));
        assertTrue(result2.isApproved());
        assertEquals(ApprovalResult.Decision.APPROVED, result2.decision());
    }

    @Test
    void approveAllPersistsForSessionUntilCleared() {
        TerminalHitlHandler handler = createHandler("a\n", true);
        ApprovalRequest req = ApprovalRequest.of("write_file", "{}");

        ApprovalResult result1 = handler.requestApproval(req);
        assertTrue(result1.isApproved());
        assertTrue(result1.isApprovedAll());
        assertTrue(handler.isApprovedAllByTool("write_file"));

        // 第二次直接通过，不再读取 stdin
        ApprovalResult result2 = handler.requestApproval(req);
        assertTrue(result2.isApproved());

        handler.clearApprovedAll();
        assertFalse(handler.isApprovedAllByTool("write_file"));
    }

    @Test
    void rejectsOperationOnN() {
        TerminalHitlHandler handler = createHandler("n\n我不想执行此命令\n", true);
        ApprovalResult result = handler.requestApproval(ApprovalRequest.of("execute_command", "{}"));

        assertTrue(result.isRejected());
        assertEquals("我不想执行此命令", result.reason());
    }

    @Test
    void skipsOperationOnS() {
        TerminalHitlHandler handler = createHandler("s\n", true);
        ApprovalResult result = handler.requestApproval(ApprovalRequest.of("execute_command", "{}"));

        assertTrue(result.isSkipped());
    }

    @Test
    void modifiesArgumentsOnM() {
        String input = "m\n{\"path\":\"safe.txt\",\"content\":\"hello\"}\n";
        TerminalHitlHandler handler = createHandler(input, true);
        ApprovalResult result = handler.requestApproval(ApprovalRequest.of("write_file", "{\"path\":\"unsafe.txt\"}"));

        assertTrue(result.isApproved());
        assertTrue(result.isModified());
        assertEquals("{\"path\":\"safe.txt\",\"content\":\"hello\"}", result.effectiveArguments("{\"path\":\"unsafe.txt\"}"));
    }

    @Test
    void rejectsOnStreamClosedOrInvalidAttempts() {
        TerminalHitlHandler handler = createHandler("invalid1\ninvalid2\ninvalid3\ninvalid4\ninvalid5\n", true);
        ApprovalResult result = handler.requestApproval(ApprovalRequest.of("execute_command", "{}"));

        assertTrue(result.isRejected());
    }
}
