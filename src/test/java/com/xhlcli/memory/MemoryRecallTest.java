package com.xhlcli.memory;

import com.xhlcli.context.TokenBudget;
import com.xhlcli.model.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MemoryRecallTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-06T00:00:00Z"), ZoneOffset.UTC);
    private final MemoryRetriever retriever = new MemoryRetriever(clock);

    private MemoryEntry entry(String id, String content) {
        return new MemoryEntry(id, content, "project", "2026-01-01T00:00:00Z", "user");
    }

    private MemoryQuery query(String input) {
        return new MemoryQuery(input, List.of(), 5, 1500, 0.18);
    }

    @Test
    void ranksRelevantMemoriesAndAllowsZeroResults() {
        var data = List.of(entry("a", "由于部署复杂度，最终没有采用 Redis。"),
                entry("b", "项目使用 Java 21。"), entry("c", "Redis 缓存方案的初步讨论。"));
        var result = retriever.recall(data, query("为什么没有使用 REDIS？"));
        assertEquals(List.of("a", "c"), result.selected().stream().map(m -> m.entry().id()).toList());
        assertTrue(result.selected().getFirst().matchedTerms().contains("redis"));
        assertTrue(retriever.recall(data, query("为什么 Kubernetes 集群无法启动？")).selected().isEmpty());
    }

    @Test
    void conversationalChineseDoesNotDrownTechnicalTermsOrMatchAnotherTechnology() {
        var data = List.of(entry("redis", "Redis 部署复杂"), entry("java", "使用 Java 21"), entry("config", "API Key 配置在用户目录"));
        assertEquals("redis", retriever.recall(data, query("Redis 再解释一次")).selected().getFirst().entry().id());
        assertEquals("java", retriever.recall(data, query("Java 版本要求是什么？")).selected().getFirst().entry().id());
        assertTrue(retriever.recall(data, query("如何部署 Kubernetes？")).selected().isEmpty());
        assertTrue(retriever.recall(data, query("如何配置 OAuth 回调？")).selected().isEmpty());
    }

    @Test
    void preservesTechnicalIdentifiersAndChineseFragments() {
        var data = List.of(entry("file", "修改 ChatLoop.java，执行 ./mvnw verify，错误码 ERR-404。"),
                entry("zh", "部署复杂度导致放弃缓存。"));
        assertEquals("file", retriever.recall(data, query("ChatLoop.java ERR-404")).selected().getFirst().entry().id());
        assertEquals("zh", retriever.recall(data, query("部署复杂度")).selected().getFirst().entry().id());
        assertTrue(retriever.recall(data, query("帮我看看这个")).selected().isEmpty());
    }

    @Test
    void filtersLifecycleEvenForPinnedAndKeepsExplicitContradictions() {
        var data = List.of(
                new MemoryEntry("old", "Redis 已采用", "project", "2026-01-01T00:00:00Z", "user", true, null, "new"),
                new MemoryEntry("expired", "Redis 凭证已更新", "global", "2026-01-01T00:00:00Z", "user", true, "2026-11-06T00:00:00Z", null),
                entry("a", "Redis 已采用"), entry("b", "Redis 尚未采用"));
        assertEquals(List.of("a", "b"), retriever.recall(data, query("Redis")).selected().stream().map(m -> m.entry().id()).toList());
    }

    @Test
    void deduplicatesContentAndLimitsPinnedTogetherWithOrdinaryMemories() {
        var pinned = new MemoryEntry("pin", "必须保留审计日志", "global", "2026-01-01T00:00:00Z", "user", true, null, null);
        var data = List.of(entry("a", "Redis 部署"), entry("b", "redis  部署"), pinned, entry("c", "Redis 缓存"));
        var result = retriever.recall(data, new MemoryQuery("Redis", List.of(), 2, 1500, 0.18));
        assertEquals(2, result.selected().size());
        assertEquals("pin", result.selected().getFirst().entry().id());
        assertEquals("a", result.selected().getLast().entry().id());
        assertEquals(3, retriever.recall(data, query("Redis")).selected().size());
    }

    @Test
    void extractsMatchingWholeParagraphAndCountsAllWrapping() {
        var data = List.of(entry("long", "无关前文".repeat(500) + "\n\nRedis 因部署复杂度被放弃。\n\n" + "无关后文".repeat(500)));
        var result = retriever.recall(data, new MemoryQuery("Redis", List.of(), 5, 200, 0.18));
        assertEquals(1, result.selected().size());
        assertTrue(result.selected().getFirst().excerpted());
        assertEquals("Redis 因部署复杂度被放弃。", result.selected().getFirst().excerpt());
        assertEquals(TokenBudget.estimateTokens(ChatMessage.user(result.render())), result.estimatedTokens());
        assertTrue(result.estimatedTokens() <= 200);
        assertTrue(result.budgetLimited());
        var tooSmall = retriever.recall(data, new MemoryQuery("Redis", List.of(), 5, 3, 0.18));
        assertTrue(tooSmall.selected().isEmpty());
        assertTrue(tooSmall.budgetLimited());
    }

    @Test
    void usesOnlyBoundedRecentUserCluesForReferences() {
        var data = List.of(entry("a", "Redis 部署复杂"), entry("b", "SQLite 是本地存储"));
        var followup = retriever.recall(data, new MemoryQuery("刚才那个为什么没用？", List.of("为什么不用 Redis？"), 5, 1500, 0.18));
        assertEquals("a", followup.selected().getFirst().entry().id());
        var newTopic = retriever.recall(data, new MemoryQuery("SQLite", List.of("Redis"), 5, 1500, 0.18));
        assertEquals(List.of("b"), newTopic.selected().stream().map(m -> m.entry().id()).toList());
    }

    @Test
    void scopesAreBoundToStorageAndReadFailureReturnsNoDynamicMemory(@TempDir Path dir) throws Exception {
        var global = dir.resolve("global"); var a = dir.resolve("a"); var b = dir.resolve("b");
        var managerA = new MemoryManager(global, a);
        var managerB = new MemoryManager(global, b);
        managerA.saveGlobal("Redis 全局偏好", "user");
        managerA.saveProject("Redis A 项目决定", "user");
        managerB.saveProject("Redis B 项目秘密", "user");
        var result = managerA.recall(query("Redis " + b));
        assertEquals(2, result.selected().size());
        assertFalse(result.render().contains("B 项目秘密"));
        Files.delete(a.resolve("long_term.jsonl"));
        Files.createDirectory(a.resolve("long_term.jsonl"));
        var failed = managerA.recall(query("Redis"));
        assertTrue(failed.readFailed());
        assertTrue(failed.selected().isEmpty());
        assertFalse(failed.diagnostics().isEmpty());
        assertFalse(failed.diagnostics().toString().contains(dir.toString()));
    }

    @Test
    void reportsBadRecordsWithoutLeakingTheirContent(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("long_term.jsonl"), "{bad secret-value}\nnull\n"
                + "{\"id\":\"x\",\"content\":\"\",\"scope\":\"project\",\"timestamp\":\"bad-date\"}\n");
        var memory = new LongTermMemory(dir.resolve("long_term.jsonl"));
        memory.save(entry("good", "Redis 决策"));
        var read = memory.read();
        assertEquals(1, read.entries().size());
        assertEquals(3, read.diagnostics().size());
        assertFalse(read.readFailed());
        assertFalse(read.diagnostics().toString().contains("secret-value"));
    }

    @Test
    void supportsDefaultsAndRejectsInvalidConfiguration() {
        var defaults = MemoryRecallSettings.fromEnvironment(Map.of());
        assertEquals(5, defaults.maxEntries()); assertEquals(1500, defaults.tokenBudget());
        var settings = MemoryRecallSettings.fromEnvironment(Map.of("XHLCLI_MEMORY_MAX_ENTRIES", "2", "XHLCLI_MEMORY_TOKEN_BUDGET", "100", "XHLCLI_MEMORY_MIN_SCORE", "0.7"));
        assertEquals(2, settings.maxEntries()); assertEquals(100, settings.tokenBudget()); assertEquals(0.7, settings.minScore());
        assertThrows(IllegalArgumentException.class, () -> MemoryRecallSettings.fromEnvironment(Map.of("XHLCLI_MEMORY_MIN_SCORE", "NaN")));
        assertThrows(IllegalArgumentException.class, () -> MemoryRecallSettings.fromEnvironment(Map.of("XHLCLI_MEMORY_MAX_ENTRIES", "-1")));
        assertTrue(retriever.recall(List.of(entry("a", "Redis")), new MemoryQuery("Redis", List.of(), 0, 1500, 0.18)).selected().isEmpty());
    }
}
