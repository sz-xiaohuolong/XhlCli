package com.xhlcli.team;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReviewResultTest {

    @Test
    void testParseValidJsonApproved() {
        String json = """
                ```json
                {
                  "approved": true,
                  "summary": "代码实现完备，测试通过",
                  "issues": [],
                  "suggestions": ["可以增加边界测试"]
                }
                ```
                """;
        ReviewResult result = ReviewResult.parse(json);
        assertTrue(result.isApproved());
        assertEquals(ReviewResult.Status.APPROVED, result.status());
        assertEquals("代码实现完备，测试通过", result.summary());
        assertTrue(result.issues().isEmpty());
        assertEquals(List.of("可以增加边界测试"), result.suggestions());
    }

    @Test
    void testParseValidJsonChangesRequested() {
        String json = """
                {
                  "approved": false,
                  "summary": "存在并发问题",
                  "issues": ["未处理竞态条件", "缺少超时设置"],
                  "suggestions": ["使用锁保护共享数据"]
                }
                """;
        ReviewResult result = ReviewResult.parse(json);
        assertFalse(result.isApproved());
        assertEquals(ReviewResult.Status.CHANGES_REQUESTED, result.status());
        assertEquals("存在并发问题", result.summary());
        assertEquals(2, result.issues().size());
        assertTrue(result.formatIssues().contains("未处理竞态条件"));
        assertTrue(result.formatIssues().contains("缺少超时设置"));
    }

    @Test
    void testParseBlockedStatus() {
        String json = """
                {
                  "approved": false,
                  "status": "BLOCKED",
                  "summary": "依赖的远程服务不可用",
                  "issues": ["连接拒绝"]
                }
                """;
        ReviewResult result = ReviewResult.parse(json);
        assertFalse(result.isApproved());
        assertEquals(ReviewResult.Status.BLOCKED, result.status());
        assertEquals("依赖的远程服务不可用", result.summary());
    }

    @Test
    void testParseHeuristicPositive() {
        String text = "经过仔细审查，该实现完全符合要求，通过测试，质量合格。";
        ReviewResult result = ReviewResult.parse(text);
        assertTrue(result.isApproved());
        assertEquals(ReviewResult.Status.APPROVED, result.status());
    }

    @Test
    void testParseHeuristicNegative() {
        String text = """
                审查未通过。发现以下缺陷：
                - 边界值未校验导致空指针
                - 缺失单测覆盖
                """;
        ReviewResult result = ReviewResult.parse(text);
        assertFalse(result.isApproved());
        assertEquals(ReviewResult.Status.CHANGES_REQUESTED, result.status());
        assertTrue(result.issues().contains("边界值未校验导致空指针"));
        assertTrue(result.issues().contains("缺失单测覆盖"));
    }

    @Test
    void testParseEmptyOrNullConservativelyRejects() {
        ReviewResult nullResult = ReviewResult.parse(null);
        assertFalse(nullResult.isApproved());

        ReviewResult emptyResult = ReviewResult.parse("   ");
        assertFalse(emptyResult.isApproved());
    }

    @Test
    void testParseAmbiguousTextConservativelyRejects() {
        String text = "这是一段模棱两可的评论，没有说通过，也没有说不通过。";
        ReviewResult result = ReviewResult.parse(text);
        assertFalse(result.isApproved());
        assertEquals(ReviewResult.Status.CHANGES_REQUESTED, result.status());
    }
}
