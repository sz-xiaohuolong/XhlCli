package com.xhlcli.hitl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalPolicySnapshotTest {

    @Test
    void revertTurnIsHighRiskAndRequiresApproval() {
        assertEquals(RiskLevel.HIGH_RISK, ApprovalPolicy.getRiskLevel("revert_turn"));
        assertTrue(ApprovalPolicy.requiresApproval("revert_turn"));
        assertFalse(ApprovalPolicy.isReadOnly("revert_turn"));

        String desc = ApprovalPolicy.getRiskDescription("revert_turn");
        assertTrue(desc.contains("Side-Git"));
        assertTrue(desc.contains("恢复工作区"));
    }
}
