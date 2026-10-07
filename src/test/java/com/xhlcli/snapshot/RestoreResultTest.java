package com.xhlcli.snapshot;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestoreResultTest {

    @Test
    void successFormatContainsSummaryAndCounts() {
        RestoreResult result = RestoreResult.success(
                "1234567890abcdef",
                List.of("src/App.java", "README.md"),
                List.of("temp.txt")
        );
        assertTrue(result.success());
        String cli = result.formatForCli();
        assertTrue(cli.contains("已恢复到快照 1234567890"));
        assertTrue(cli.contains("写回文件: 2"));
        assertTrue(cli.contains("删除文件: 1"));
    }

    @Test
    void failureFormatContainsErrorMessage() {
        RestoreResult result = RestoreResult.failure("未找到指定的 pre-turn 快照");
        assertFalse(result.success());
        String cli = result.formatForCli();
        assertTrue(cli.contains("❌ 未找到指定的 pre-turn 快照"));
    }
}
