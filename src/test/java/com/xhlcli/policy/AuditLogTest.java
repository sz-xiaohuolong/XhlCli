package com.xhlcli.policy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditLogTest {

    @Test
    void recordsAndReadsAuditEntries(@TempDir Path tempDir) {
        AuditLog auditLog = new AuditLog(tempDir);

        auditLog.record(AuditLog.AuditEntry.allow("read_file", "{\"path\":\"README.md\"}", 12));
        auditLog.record(AuditLog.AuditEntry.denyByPolicy("execute_command", "{\"command\":\"sudo rm -rf /\"}", "禁止 sudo", 5));
        auditLog.record(AuditLog.AuditEntry.denyByHitl("write_file", "{\"path\":\"a.txt\"}", "用户拒绝", 8));

        List<AuditLog.AuditEntry> recent = auditLog.readRecent(10);
        assertEquals(3, recent.size());

        assertEquals("read_file", recent.get(0).tool());
        assertEquals("allow", recent.get(0).outcome());
        assertEquals("none", recent.get(0).approver());

        assertEquals("execute_command", recent.get(1).tool());
        assertEquals("deny", recent.get(1).outcome());
        assertEquals("policy", recent.get(1).approver());
        assertEquals("禁止 sudo", recent.get(1).reason());

        assertEquals("write_file", recent.get(2).tool());
        assertEquals("deny", recent.get(2).outcome());
        assertEquals("hitl", recent.get(2).approver());
        assertEquals("用户拒绝", recent.get(2).reason());
    }

    @Test
    void sanitizesSensitiveTokensAndPassword() {
        String input1 = "Bearer sk-proj-1234567890abcdef";
        String sanitized1 = AuditLog.sanitize(input1);
        assertEquals("Bearer ***", sanitized1);

        String input2 = "{\"api_key\": \"secret-key-value\", \"password\": \"my-secret-pass\"}";
        String sanitized2 = AuditLog.sanitize(input2);
        assertFalse(sanitized2.contains("secret-key-value"));
        assertFalse(sanitized2.contains("my-secret-pass"));
        assertTrue(sanitized2.contains("***"));

        String input3 = "connect url=http://example.com token=supersecrettoken";
        String sanitized3 = AuditLog.sanitize(input3);
        assertFalse(sanitized3.contains("supersecrettoken"));
        assertTrue(sanitized3.contains("***"));
    }

    @Test
    void truncatesExcessiveFieldLength() {
        String longText = "a".repeat(2000);
        String truncated = AuditLog.truncate(longText);
        assertNotNull(truncated);
        assertTrue(truncated.length() <= 1020);
        assertTrue(truncated.endsWith("...(truncated)"));
    }
}
