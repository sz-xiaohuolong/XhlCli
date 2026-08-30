package com.xhlcli.hitl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalRequestTest {

    @Test
    void formatsDisplayBoxWithAlignedBorders() {
        ApprovalRequest request = ApprovalRequest.of(
                "write_file",
                "{\"path\":\"src/Test.java\",\"content\":\"public class Test {}\"}",
                "修复编译错误"
        );

        String display = request.toDisplayText();
        assertNotNull(display);
        assertTrue(display.contains("⚠️  需要审批"));
        assertTrue(display.contains("工具: write_file"));
        assertTrue(display.contains("等级: 🟡 中危"));
        assertTrue(display.contains("path: \"src/Test.java\""));
        assertTrue(display.contains("修复编译错误"));
        assertTrue(display.startsWith("┌"));
        assertTrue(display.endsWith("┘"));
    }

    @Test
    void handlesNullOrMalformedArgumentsGracefully() {
        ApprovalRequest req1 = ApprovalRequest.of("execute_command", null);
        assertTrue(req1.toDisplayText().contains("(无参数)"));

        ApprovalRequest req2 = ApprovalRequest.of("execute_command", "not a valid json");
        assertTrue(req2.toDisplayText().contains("not a valid json"));
    }
}
