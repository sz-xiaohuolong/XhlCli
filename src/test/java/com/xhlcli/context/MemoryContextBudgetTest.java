package com.xhlcli.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.memory.*;
import com.xhlcli.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryContextBudgetTest {
    @Test
    void countsImageAndRealToolSchemaWithoutChangingRulesOrInput() {
        var budget = new TokenBudget(1400, 100, 999, 999);
        var rules = ChatMessage.system("关键项目规则");
        var input = ChatMessage.user("Redis 图片问题", List.of(ContentPart.text("Redis 图片问题"), ContentPart.imageBase64("AAAA", "image/png")));
        var history = List.of(rules, input);
        var schema = new ObjectMapper().createObjectNode().put("description", "字段说明".repeat(60));
        var tools = List.of(new ToolDefinition("read", "读取", schema, new ToolMetadata(ToolMetadata.RiskLevel.LOW, true, true, false, "")));
        var entries = java.util.stream.IntStream.range(0, 5).mapToObj(i ->
                new MemoryEntry("m" + i, "Redis 第 " + i + " 条决定：" + "不可忽略".repeat(15), "project", "2026-01-01T00:00:00Z", "user")).toList();
        var recall = new MemoryRetriever().recall(entries, MemoryRecallSettings.defaults().query("Redis", List.of()));
        assertEquals(5, recall.selected().size());
        var assembled = new ContextAssembler(budget).withRecall(history, tools, recall);
        assertTrue(assembled.recall().selected().size() < 5);
        assertTrue(assembled.recall().budgetLimited());
        assertEquals(rules, assembled.messages().getFirst());
        assertEquals(input, assembled.messages().getLast());
        assertTrue(assembled.messages().getLast().hasImages());
        assertTrue(budget.remainingInputTokens(assembled.messages(), tools) >= 0);
        assertEquals(2, history.size());
        assertEquals(5, recall.selected().size());
    }

    @Test
    void exactBoundaryKeepsCurrentQuestionAndLeavesNoRoomForMemory() {
        var history = List.of(ChatMessage.system("规则"), ChatMessage.user("Redis"));
        int base = TokenBudget.estimateTokens(history);
        var budget = new TokenBudget(base + 10, 10, 0, 0);
        var entry = MemoryEntry.create("Redis 因部署复杂被放弃", "project", "user");
        var recall = new MemoryRetriever().recall(List.of(entry), MemoryRecallSettings.defaults().query("Redis", List.of()));
        var assembled = new ContextAssembler(budget).withRecall(history, List.of(), recall);
        assertEquals(history, assembled.messages());
        assertTrue(assembled.recall().selected().isEmpty());
        assertTrue(assembled.recall().budgetLimited());
    }
}
