package com.xhlcli.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.context.ContextAssembler;
import com.xhlcli.context.TokenBudget;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MemoryRecallEvaluationTest {
    @Test
    void comparesThirtyAnnotatedQuestionsWithFullInjection() throws Exception {
        var mapper = new ObjectMapper();
        var resource = getClass().getResourceAsStream("/memory/recall-golden.json");
        assertNotNull(resource);
        com.fasterxml.jackson.databind.JsonNode fixture;
        try (resource) { fixture = mapper.readTree(resource); }
        List<MemoryEntry> memories = new ArrayList<>();
        for (var item : fixture.path("memories")) memories.add(mapper.treeToValue(item, MemoryEntry.class));
        var retriever = new MemoryRetriever(Clock.fixed(Instant.parse("2026-11-06T00:00:00Z"), ZoneOffset.UTC));
        int fullTokens = TokenBudget.estimateTokens(new ContextAssembler(new TokenBudget(10000))
                .assemble("", "", "", "", "", memories, null, null));
        int totalExpected = 0, totalHit = 0, totalSelected = 0, totalTokens = 0, count = 0;
        StringBuilder rows = new StringBuilder("| 问题 | 预期 ID | 实际 ID | 漏召回 | 无关条数 | 召回/全量 Token |\n|---|---|---|---|---|---|\n");
        for (var c : fixture.path("cases")) {
            List<String> recent = new ArrayList<>();
            c.path("recentUserMessages").forEach(n -> recent.add(n.asText()));
            Set<String> expected = new TreeSet<>();
            c.path("expectedIds").forEach(n -> expected.add(n.asText()));
            var result = retriever.recall(memories, MemoryRecallSettings.defaults().query(c.path("question").asText(), recent));
            Set<String> selected = new TreeSet<>();
            result.selected().forEach(m -> selected.add(m.entry().id()));
            Set<String> missed = new TreeSet<>(expected); missed.removeAll(selected);
            int hits = expected.size() - missed.size();
            totalExpected += expected.size(); totalHit += hits; totalSelected += selected.size(); totalTokens += result.estimatedTokens(); count++;
            assertTrue(result.estimatedTokens() <= 1500);
            assertTrue(selected.size() <= 5);
            rows.append("| ").append(c.path("id").asText()).append(" ").append(c.path("question").asText())
                    .append(" | ").append(expected).append(" | ").append(selected).append(" | ").append(missed)
                    .append(" | ").append(selected.size() - hits).append(" | ").append(result.estimatedTokens()).append("/").append(fullTokens).append(" |\n");
        }
        assertEquals(30, count);
        assertTrue(totalTokens < fullTokens * count, "按需召回应减少实际注入 Token");
        assertTrue(totalSelected - totalHit < memories.size() * count - totalExpected, "按需召回应减少无关条目");
        String summary = String.format(Locale.ROOT,
                "# 记忆召回离线评测\n\n数据来源：%s\n\n固定配置：5 条 / 1500 估算 Token / 最低相关性 0.18。计量包含资料包装，不包含其他系统提示词、工具和对话。\n\n"
                + "| 方法 | 命中/应召回 | 召回率 | 无关条目总数 | 注入条目总数 | 平均记忆 Token |\n|---|---|---|---|---|---|\n"
                + "| 原全量注入 | %d/%d | 100%% | %d | %d | %d |\n"
                + "| 文本按需召回 | %d/%d | %.2f%% | %d | %d | %.1f |\n\n"
                + "词法基线不会自动理解同义表达。下表保留漏召回与误召回；真实模型答案是否遗漏关键约束仍需人工验收。\n\n",
                fixture.path("provenance").asText(), totalExpected, totalExpected,
                memories.size() * count - totalExpected, memories.size() * count, fullTokens,
                totalHit, totalExpected, totalHit * 100.0 / totalExpected, totalSelected - totalHit, totalSelected, totalTokens / (double) count);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/memory-recall-evaluation.md"), summary + rows);
        System.out.println(String.format(Locale.ROOT, "记忆评测：命中 %d/%d，无关 %d，平均 Token %.1f（全量 %d）", totalHit, totalExpected, totalSelected - totalHit, totalTokens / (double) count, fullTokens));
    }
}
