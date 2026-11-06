package com.xhlcli.team;

import com.xhlcli.llm.*;
import com.xhlcli.model.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TeamReplanningTest {
    private static final String OK = "{\"approved\":true,\"summary\":\"检查通过\",\"issues\":[]}";
    private static final String NO = "{\"approved\":false,\"summary\":\"原计划依赖不存在的接口\",\"issues\":[\"需要先增加适配器\"]}";
    static String step(String id, String text, String deps) {
        return "{\"id\":\"" + id + "\",\"description\":\"" + text + "\",\"type\":\"ANALYSIS\",\"dependencies\":" + deps + "}";
    }
    static String plan(String... steps) { return "{\"steps\":[" + String.join(",", steps) + "]}"; }
    static String revised(String... steps) {
        return "{\"decision\":\"REPLAN\",\"reason\":\"接口不存在，先做适配\",\"steps\":[" + String.join(",", steps) + "]}";
    }
    static TeamOrchestrator team(LlmClient llm) {
        return new TeamOrchestrator(llm, null, List.of(), null, new PrintStream(new ByteArrayOutputStream()));
    }
    @Test void replansRemainingWorkAndDoesNotRerunCompletedStep() {
        var llm = new Scripted(
            plan(step("a", "确认接口", "[]"), step("b", "调用旧接口", "[\"a\"]"), step("c", "验证结果", "[\"b\"]")),
            "接口已确认", OK, "尝试一", NO, "尝试二", NO, "尝试三", NO,
            revised(step("d", "建立适配器", "[\"a\"]"), step("e", "验证适配器", "[\"d\"]")),
            "适配完成", OK, "验证完成", OK);
        var result = team(llm).run("完成接口适配", e -> {}, new CancellationToken());
        assertEquals(RunStatus.COMPLETED, result.status());
        assertTrue(result.finalAnswer().contains("验证完成"));
        assertTrue(result.finalAnswer().contains("接口已确认"));
        assertEquals(1, llm.prompts.stream().filter(s -> s.contains("当前任务目标**：确认接口")).count());
        assertTrue(llm.prompts.stream().anyMatch(s -> s.contains("接口已确认") && s.contains("尝试三")));
    }
    @Test void invalidInitialGraphNeverStartsWorkers() {
        for (String graph : List.of(
            plan(step("a", "任务一", "[\"missing\"]")),
            plan(step("a", "任务一", "[\"b\"]"), step("b", "任务二", "[\"a\"]")),
            plan(step("a", "任务一", "[]"), step("a", "任务二", "[]")))) {
            var llm = new Scripted(graph, "不应执行", OK);
            var output = new ByteArrayOutputStream();
            var orchestrator = new TeamOrchestrator(llm, null, List.of(), null, new PrintStream(output));
            assertEquals(RunStatus.FAILED, orchestrator.run("检查任务", e -> {}, new CancellationToken()).status());
            assertEquals(1, llm.prompts.size());
            assertTrue(output.toString().contains("规划失败"), "终端必须显示失败原因");
        }
    }
    @Test void atMostTwoReplansAcrossTheWholeRun() {
        var responses = new ArrayList<String>();
        responses.add(plan(step("a", "原方案", "[]")));
        for (int version = 0; version < 3; version++) {
            for (int attempt = 0; attempt < 3; attempt++) { responses.add("尝试失败"); responses.add(NO); }
            if (version < 2) responses.add(revised(step("r" + version, "替代方案" + version, "[]")));
        }
        var llm = new Scripted(responses.toArray(String[]::new));
        var result = team(llm).run("完成任务", e -> {}, new CancellationToken());
        assertEquals(RunStatus.FAILED, result.status());
        assertEquals(21, llm.prompts.size());
        assertTrue(result.finalAnswer().contains("重规划"));
    }
    @Test void failedReplacementGraphDoesNotRunNewWorkers() {
        var llm = new Scripted(plan(step("a", "原方案", "[]")), "一", NO, "二", NO, "三", NO,
                revised(step("b", "替代方案", "[\"unknown\"]")), "禁止执行", OK);
        var result = team(llm).run("完成任务", e -> {}, new CancellationToken());
        assertEquals(RunStatus.FAILED, result.status());
        assertEquals(8, llm.prompts.size());
        assertFalse(result.finalAnswer().contains("禁止执行"));
    }
    @Test void replanningReceivesRealFileChangesAndPreservesExistingContent(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        java.nio.file.Files.writeString(root.resolve("existing.txt"), "用户已有内容");
        var write = new com.xhlcli.tool.local.WriteFileTool(new com.xhlcli.tool.local.WorkspacePathResolver(root));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var prompts = new ArrayList<String>();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        LlmClient llm = (messages, tools, listener, token) -> {
            int call = calls.incrementAndGet();
            String input = messages.getLast().content(); prompts.add(input);
            if (call == 1) return new ChatResponse(plan(step("a", "原方案", "[]")), List.of(), TokenUsage.unknown());
            if (call == 2) return new ChatResponse("", List.of(new ToolCall("w", "write_file",
                    "{\"path\":\"partial.txt\",\"content\":\"失败前留下的内容\"}")), TokenUsage.unknown());
            String response = switch (call) {
                case 3, 5, 7 -> "实现未完成";
                case 4, 6, 8 -> NO;
                case 9 -> revised(step("b", "保留文件并完成剩余分析", "[]"));
                case 10 -> "分析完成，文件保留";
                default -> OK;
            };
            return new ChatResponse(response, List.of(), TokenUsage.unknown());
        };
        com.xhlcli.tool.ToolExecutor executor = (call, token) -> {
            try {
                var output = write.execute(mapper.readTree(call.argumentsJson()), token);
                return new ToolResult(call.id(), call.name(), ToolResultStatus.SUCCESS, output.summary(), output.data(), 0, false, 0, "");
            } catch (Exception e) { throw new RuntimeException(e); }
        };
        var orchestrator = new TeamOrchestrator(llm, executor, List.of(write.definition()), null,
                new PrintStream(new ByteArrayOutputStream()));
        orchestrator.setProjectDirectory(root);
        var result = orchestrator.run("完成原目标", e -> {}, new CancellationToken());
        assertEquals(RunStatus.COMPLETED, result.status());
        assertTrue(prompts.get(8).contains("partial.txt"), "规划者必须获得真实文件变化，不能只有口头总结");
        assertTrue(prompts.get(8).contains("SHA-256"));
        assertEquals("用户已有内容", java.nio.file.Files.readString(root.resolve("existing.txt")));
        assertEquals("失败前留下的内容", java.nio.file.Files.readString(root.resolve("partial.txt")));
        assertEquals(11, calls.get());
    }

    @Test void lateReplanningKeepsUnrelatedPendingTasks() {
        String original = plan(step("a", "已做一", "[]"), step("b", "已做二", "[\"a\"]"),
                step("c", "失败步骤", "[\"b\"]"), step("d", "后续验证", "[\"c\"]"));
        var llm = new Scripted(original, "一完成", OK, "二完成", OK, "三失败", NO, "三失败", NO, "三失败", NO,
                revised(step("e", "修正失败步骤", "[\"b\"]"), step("d", "后续验证", "[\"e\"]")),
                "修正完成", OK, "验证完成", OK);
        var result = team(llm).run("完整目标", e -> {}, new CancellationToken());
        assertEquals(RunStatus.COMPLETED, result.status());
        assertTrue(llm.prompts.get(11).contains("仅失败步骤及其下游依赖"));
    }
    @Test void stopDecisionAndNetworkFailureDoNotCauseReplanningLoops() {
        var llm = new Scripted(plan(step("a", "原步骤", "[]")), "一", NO, "二", NO, "三", NO,
                "{\"decision\":\"STOP\",\"reason\":\"等待外部依赖\"}");
        var result = team(llm).run("任务", e -> {}, new CancellationToken());
        assertEquals(RunStatus.FAILED, result.status());
        assertEquals(8, llm.prompts.size());
        assertTrue(result.finalAnswer().contains("等待外部依赖"));
    }
    @Test void cancellationDuringReplanningNeverRunsReplacement() {
        var token = new CancellationToken();
        var delegate = new Scripted(plan(step("a", "原步骤", "[]")), "一", NO, "二", NO, "三", NO,
                revised(step("b", "替代步骤", "[]")));
        LlmClient client = (messages, tools, listener, cancellation) -> {
            if (delegate.prompts.size() == 7) token.cancel();
            return delegate.stream(messages, tools, listener, cancellation);
        };
        var result = team(client).run("任务", e -> {}, token);
        assertEquals(RunStatus.CANCELED, result.status());
        assertEquals(8, delegate.prompts.size());
    }
    @Test void permissionDenialCannotBeRetriedAsAnotherPlan(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        LlmClient client = (messages, tools, listener, token) -> {
            int n = calls.incrementAndGet();
            if (n == 1) return new ChatResponse(plan(step("a", "操作文件", "[]")), TokenUsage.unknown());
            if (n == 2) return new ChatResponse("", List.of(new ToolCall("deny", "write_file", "{}")), TokenUsage.unknown());
            return new ChatResponse("越过拒绝继续成功", TokenUsage.unknown());
        };
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.xhlcli.tool.ToolExecutor executor = (call, token) -> new ToolResult(call.id(), call.name(),
                ToolResultStatus.EXECUTION_ERROR, "[HITL] 用户拒绝", mapper.createObjectNode(), 0, false, 0, "");
        var orchestrator = new TeamOrchestrator(client, executor, List.of(), null, new PrintStream(new ByteArrayOutputStream()));
        orchestrator.setProjectDirectory(root);
        var result = orchestrator.run("操作文件", e -> {}, new CancellationToken());
        assertEquals(RunStatus.FAILED, result.status());
        assertEquals(2, calls.get(), "拒绝后不能继续请求模型改换操作或计划");
    }

    @Test void waitsForOldParallelBatchBeforeReplanning() throws Exception {
        var slowStarted = new java.util.concurrent.CountDownLatch(1);
        var releaseSlow = new java.util.concurrent.CountDownLatch(1);
        var failedReviews = new java.util.concurrent.CountDownLatch(3);
        var plannerCalls = new java.util.concurrent.atomic.AtomicInteger();
        LlmClient llm = (messages, tools, listener, token) -> {
            String role = messages.getFirst().content(); String input = messages.getLast().content();
            String response;
            if (role.contains("Task Planning Specialist")) {
                int call = plannerCalls.incrementAndGet();
                if (call == 1) response = plan(step("a", "失败任务", "[]"), step("b", "慢任务", "[]"), step("c", "最终验证", "[\"a\"]"));
                else {
                    assertEquals(0, releaseSlow.getCount(), "旧任务结束前不能更换计划");
                    response = revised(step("d", "替代任务", "[\"b\"]"), step("e", "最终验证", "[\"d\"]"));
                }
            } else if (role.contains("Quality Review Specialist")) {
                if (input.contains("失败任务")) { failedReviews.countDown(); response = NO; } else response = OK;
            } else {
                if (input.contains("当前任务目标**：慢任务")) {
                    slowStarted.countDown();
                    try { assertTrue(releaseSlow.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                }
                response = "该步骤已执行";
            }
            return new ChatResponse(response, TokenUsage.unknown());
        };
        try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var result = pool.submit(() -> team(llm).run("完成任务", e -> {}, new CancellationToken()));
            try {
                assertTrue(slowStarted.await(3, java.util.concurrent.TimeUnit.SECONDS));
                assertTrue(failedReviews.await(3, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(1, plannerCalls.get());
            } finally { releaseSlow.countDown(); }
            assertEquals(RunStatus.COMPLETED, result.get(5, java.util.concurrent.TimeUnit.SECONDS).status());
        }
    }
    @Test void lateRevisionCannotChangeUnrelatedPendingDefinition() {
        var a = TeamOrchestrator.ExecutionStep.pending("a", "完成一", "ANALYSIS", List.of()).withSuccess("成果", ReviewResult.approved("通过"));
        var b = TeamOrchestrator.ExecutionStep.pending("b", "完成二", "ANALYSIS", List.of("a")).withSuccess("成果", ReviewResult.approved("通过"));
        var c = TeamOrchestrator.ExecutionStep.pending("c", "失败步骤", "ANALYSIS", List.of("b")).withFailure("失败", ReviewResult.changesRequested("失败", List.of(), List.of()));
        var d = TeamOrchestrator.ExecutionStep.pending("d", "独立验证", "ANALYSIS", List.of("b"));
        var changed = TeamOrchestrator.ExecutionStep.pending("d", "删除验证", "ANALYSIS", List.of("b"));
        var fix = TeamOrchestrator.ExecutionStep.pending("e", "修正失败步骤", "ANALYSIS", List.of("b"));
        assertFalse(TeamPlanValidator.validRevision(List.of(a,b,c,d), List.of(fix,changed)));
        assertTrue(TeamPlanValidator.validRevision(List.of(a,b,c,d), List.of(fix,d)));
        assertFalse(TeamPlanValidator.validRevision(List.of(a,b,c,d), List.of(a,fix,d)));
    }
    @Test void invalidReviewResponseIsNotAReasonToRetryOrReplan() {
        for (String response : List.of("服务暂时出错", "{\"status\":\"BLOCKED\"}", "{\"approved\":\"false\"}",
                "{\"approved\":true,\"status\":\"BLOCKED\"}")) {
            var llm = new Scripted(plan(step("a", "原步骤", "[]")), "执行结果", response);
            assertEquals(RunStatus.FAILED, team(llm).run("任务", e -> {}, new CancellationToken()).status());
            assertEquals(3, llm.prompts.size());
        }
    }

    @Test void mixedPermissionDenialAndLogicalFailureCannotReplan(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) {
        var plannerCalls = new java.util.concurrent.atomic.AtomicInteger();
        LlmClient client = (messages, tools, listener, token) -> {
            String role = messages.getFirst().content(), input = messages.getLast().content();
            if (role.contains("Task Planning Specialist")) {
                return new ChatResponse(plannerCalls.incrementAndGet() == 1
                        ? plan(step("a", "拒绝任务", "[]"), step("b", "逻辑失败任务", "[]"))
                        : revised(step("c", "换名执行被拒绝的任务", "[]")), TokenUsage.unknown());
            }
            if (role.contains("Quality Review Specialist")) return new ChatResponse(NO, TokenUsage.unknown());
            if (input.contains("当前任务目标**：拒绝任务"))
                return new ChatResponse("", List.of(new ToolCall("deny", "write_file", "{}")), TokenUsage.unknown());
            return new ChatResponse("执行结果", TokenUsage.unknown());
        };
        com.xhlcli.tool.ToolExecutor executor = (call, token) -> new ToolResult(call.id(), call.name(),
                ToolResultStatus.EXECUTION_ERROR, "[POLICY] 拒绝", new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(), 0, false, 0, "");
        var orchestrator = new TeamOrchestrator(client, executor, List.of(), null, new PrintStream(new ByteArrayOutputStream()));
        orchestrator.setProjectDirectory(root);
        assertEquals(RunStatus.FAILED, orchestrator.run("完成任务", e -> {}, new CancellationToken()).status());
        assertEquals(1, plannerCalls.get(), "任意不可恢复失败都必须阻止整图替换");
    }

    @Test void revisionCannotOnlyChangeUnrelatedWork() {
        var a = TeamOrchestrator.ExecutionStep.pending("a", "失败步骤", "ANALYSIS", List.of())
                .withFailure("失败", ReviewResult.changesRequested("失败", List.of(), List.of()));
        var c = TeamOrchestrator.ExecutionStep.pending("c", "完成步骤", "ANALYSIS", List.of())
                .withSuccess("成果", ReviewResult.approved("通过"));
        var b = TeamOrchestrator.ExecutionStep.pending("b", "独立验证", "ANALYSIS", List.of("c"));
        var changed = TeamOrchestrator.ExecutionStep.pending("b", "改了无关文案", "ANALYSIS", List.of("c"));
        var extra = TeamOrchestrator.ExecutionStep.pending("d", "无关工作", "ANALYSIS", List.of());
        var renamed = TeamOrchestrator.ExecutionStep.pending("renamed", "失败步骤", "ANALYSIS", List.of());
        assertFalse(TeamPlanValidator.validRevision(List.of(a,c,b), List.of(a,changed)));
        assertFalse(TeamPlanValidator.validRevision(List.of(a,c,b), List.of(a,b,extra)));
        assertFalse(TeamPlanValidator.validRevision(List.of(a,c,b), List.of(renamed,b)));
    }

    @Test void malformedDependenciesAndTrailingContentNeverRunWorkers() {
        for (String graph : List.of(plan(step("a", "任务", "[null]")), plan(step("a", "任务", "[{\"id\":\"b\"}]")),
                plan(step("a", "任务", "[\"\"]")), plan(step("a", "任务", "[]")) + " {}")) {
            var llm = new Scripted(graph, "不应执行", OK);
            assertEquals(RunStatus.FAILED, team(llm).run("任务", e -> {}, new CancellationToken()).status());
            assertEquals(1, llm.prompts.size());
        }
    }

    @Test void largeWorkerOutputCannotHideReviewOrWorkspaceEvidence(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) {
        var plannerCalls = new java.util.concurrent.atomic.AtomicInteger();
        var workerPrompts = new java.util.concurrent.CopyOnWriteArrayList<String>();
        LlmClient client = (messages, tools, listener, token) -> {
            String role = messages.getFirst().content(), input = messages.getLast().content();
            String response;
            if (role.contains("Task Planning Specialist")) {
                if (plannerCalls.incrementAndGet() == 1) response = plan(step("a", "失败甲", "[]"), step("b", "失败乙", "[]"));
                else {
                    assertTrue(input.contains("独特验收缺陷"), "规划者必须收到审查证据");
                    assertTrue(input.contains("partial.txt"));
                    response = revised(step("c", "核对旧文件并修正", "[]"));
                }
            } else if (role.contains("Quality Review Specialist")) {
                response = input.contains("核对旧文件") ? OK : "{\"approved\":false,\"summary\":\"独特验收缺陷\",\"issues\":[\"修正接口字段\"]}";
            } else {
                workerPrompts.add(input);
                try { java.nio.file.Files.writeString(root.resolve("partial.txt"), "中间成果"); }
                catch (IOException e) { throw new UncheckedIOException(e); }
                response = "执行者自述".repeat(5000);
            }
            return new ChatResponse(response, TokenUsage.unknown());
        };
        var orchestrator = new TeamOrchestrator(client, (call, token) -> { throw new AssertionError(); }, List.of(), null,
                new PrintStream(new ByteArrayOutputStream()));
        orchestrator.setProjectDirectory(root);
        assertEquals(RunStatus.COMPLETED, orchestrator.run("完成任务", e -> {}, new CancellationToken()).status());
        String last = workerPrompts.getLast();
        assertTrue(last.contains("partial.txt"));
        assertTrue(last.contains("独特验收缺陷"));
        assertTrue(last.contains("尝试 3"));
    }

    @Test void incompleteWorkspaceEvidenceStopsReplanning(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) {
        var delegate = new Scripted(plan(step("a", "原步骤", "[]")), "一", NO, "二", NO, "三", NO);
        LlmClient client = (messages, tools, listener, token) -> {
            if (delegate.prompts.size() == 1) {
                try {
                    for (int i = 0; i < 101; i++) java.nio.file.Files.writeString(root.resolve("partial-" + i), "中间成果");
                } catch (IOException e) { throw new UncheckedIOException(e); }
            }
            return delegate.stream(messages, tools, listener, token);
        };
        var orchestrator = new TeamOrchestrator(client, (call, token) -> { throw new AssertionError(); }, List.of(), null,
                new PrintStream(new ByteArrayOutputStream()));
        orchestrator.setProjectDirectory(root);
        var result = orchestrator.run("完成任务", e -> {}, new CancellationToken());
        assertEquals(RunStatus.FAILED, result.status());
        assertEquals(7, delegate.prompts.size());
        assertTrue(result.finalAnswer().contains("无法完整核对"));
        assertTrue(java.nio.file.Files.exists(root.resolve("partial-100")));
    }

    static class Scripted implements LlmClient {
        final Queue<String> responses;
        final List<String> prompts = new ArrayList<>();
        Scripted(String... values) { responses = new ArrayDeque<>(List.of(values)); }
        @Override public synchronized ChatResponse stream(List<ChatMessage> messages, List<ToolDefinition> tools,
                StreamListener listener, CancellationToken token) {
            prompts.add(messages.getLast().content());
            String response = responses.isEmpty() ? "{\"decision\":\"STOP\",\"reason\":\"停止\"}" : responses.remove();
            return new ChatResponse(response, List.of(), new TokenUsage(1, 1, true));
        }
    }
}
