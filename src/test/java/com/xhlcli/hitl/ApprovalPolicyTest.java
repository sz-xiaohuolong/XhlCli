package com.xhlcli.hitl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalPolicyTest {

    @Test
    void readOnlyToolsDoNotRequireApproval() {
        assertFalse(ApprovalPolicy.requiresApproval("read_file"));
        assertFalse(ApprovalPolicy.requiresApproval("list_dir"));
        assertFalse(ApprovalPolicy.requiresApproval("glob_files"));
        assertFalse(ApprovalPolicy.requiresApproval("grep_code"));
        assertFalse(ApprovalPolicy.requiresApproval("git_diff"));
        assertFalse(ApprovalPolicy.requiresApproval("echo_text"));
        assertFalse(ApprovalPolicy.requiresApproval("current_time"));

        assertEquals(RiskLevel.READ_ONLY, ApprovalPolicy.getRiskLevel("read_file"));
    }

    @Test
    void writingAndExecutionToolsRequireApproval() {
        assertTrue(ApprovalPolicy.requiresApproval("write_file"));
        assertTrue(ApprovalPolicy.requiresApproval("apply_patch"));
        assertTrue(ApprovalPolicy.requiresApproval("execute_command"));

        assertEquals(RiskLevel.MEDIUM_RISK, ApprovalPolicy.getRiskLevel("write_file"));
        assertEquals(RiskLevel.MEDIUM_RISK, ApprovalPolicy.getRiskLevel("apply_patch"));
        assertEquals(RiskLevel.HIGH_RISK, ApprovalPolicy.getRiskLevel("execute_command"));
    }

    @Test
    void unknownToolsRequireApprovalByDefault() {
        assertTrue(ApprovalPolicy.requiresApproval("unknown_tool_123"));
        assertEquals(RiskLevel.HIGH_RISK, ApprovalPolicy.getRiskLevel("unknown_tool_123"));
        assertTrue(ApprovalPolicy.requiresApproval(null));
        assertTrue(ApprovalPolicy.requiresApproval(""));
    }

    @Test
    void providesHelpfulRiskDescriptions() {
        assertNotNull(ApprovalPolicy.getRiskDescription("execute_command"));
        assertNotNull(ApprovalPolicy.getRiskDescription("write_file"));
        assertNotNull(ApprovalPolicy.getRiskDescription("read_file"));
        assertNotNull(ApprovalPolicy.getRiskDescription("unknown_new_tool"));
    }
}
