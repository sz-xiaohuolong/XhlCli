package com.xhlcli.llm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.xhlcli.agent.*;
import com.xhlcli.config.*;
import com.xhlcli.context.*;
import com.xhlcli.memory.*;
import com.xhlcli.model.*;
import com.xhlcli.tool.local.*;
import okhttp3.*;
import okio.Buffer;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;

/** 用真实 Agent、工具、压缩器和模型客户端做三组受控对照，不保存凭据。 */
public final class HeadroomRealApiProbe {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String QUERY = "根据刚才工具观察的 summary，提取全部 HEADROOM_KEEP_EVIDENCE_n 中不同的整数 n。只返回 JSON：ids 为升序整数数组，count 为数量，sum 为总和。没有条目时返回空数组、0、0。不要调用工具。";
    private static final class Capture {
        final List<ObjectNode> requests = new ArrayList<>();
        final List<JsonNode> usages = new ArrayList<>();
        final List<String> ids = new ArrayList<>();
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
    public static void main(String[] args) throws Exception {
        Path project = Path.of(args[0]).toAbsolutePath();
        Path output = Path.of(args[1]).toAbsolutePath();
        Files.createDirectories(output);
        var config = ChatConfigLoader.load(new String[0], System.getenv(), project, Path.of(System.getProperty("user.home")));
        require(config.baseUrl().getHost().equals("api.deepseek.com") && config.baseUrl().getScheme().equals("https"), "本探针只允许现有 DeepSeek 官方 HTTPS 地址");
        require(config.hasApiKey(), "缺少现有模型凭据");
        var optimizerEnv = new HashMap<>(ChatConfigLoader.readDotEnv(project.resolve(".env")));
        optimizerEnv.putAll(System.getenv());
        require(HeadroomSettings.fromEnvironment(optimizerEnv).enabled(), "项目 Headroom 尚未开启");
        Path workspace = output.resolve("workspace");
        Files.createDirectories(workspace);
        String fixture = IntStream.range(0, 40).mapToObj(i -> "src/very/long/package/path/Example.java:" + i + ": return HEADROOM_KEEP_EVIDENCE_" + i + ";").collect(Collectors.joining("\n")) + "\n";
        Files.writeString(workspace.resolve("fixture.txt"), fixture);
        var command = new ExecuteCommandTool(new WorkspacePathResolver(workspace));
        var commandOutput = command.execute(JSON.createObjectNode().put("command", "cat fixture.txt"), new CancellationToken());
        String originalObservation = new ToolResult("real-api-call", "execute_command", ToolResultStatus.SUCCESS,
                commandOutput.summary(), commandOutput.data(), 0, false, 0, commandOutput.continueHint()).observationJson(JSON);
        var control = (ObjectNode) JSON.readTree(originalObservation);
        control.put("summary", "");
        var captures = new LinkedHashMap<String, Capture>();
        var cases = JSON.createObjectNode();
        for (String scenario : List.of("disabled", "enabled", "empty_control")) {
            System.out.println("正在请求真实模型：" + scenario);
            var capture = new Capture(); captures.put(scenario, capture);
            var http = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(30)).readTimeout(Duration.ofSeconds(90))
                    .callTimeout(Duration.ofSeconds(120)).retryOnConnectionFailure(false)
                    .addInterceptor(chain -> {
                        var buffer = new Buffer(); chain.request().body().writeTo(buffer);
                        var body = (ObjectNode) JSON.readTree(buffer.readUtf8());
                        // 所有组都使用相同的输出上限和非思考模式，控制本次验证费用。
                        body.put("max_tokens", 256).put("temperature", 0).put("tool_choice", "none");
                        body.putObject("thinking").put("type", "disabled");
                        body.putObject("stream_options").put("include_usage", true);
                        capture.requests.add(body.deepCopy());
                        var request = chain.request().newBuilder().post(RequestBody.create(JSON.writeValueAsBytes(body), MediaType.get("application/json"))).build();
                        var response = chain.proceed(request);
                        if (!response.isSuccessful()) {
                            int code = response.code(); response.close();
                            throw new java.io.IOException("真实模型请求未成功，HTTP " + code);
                        }
                        var type = response.body().contentType();
                        String sse = response.body().string();
                        JsonNode usage = null; String responseId = "";
                        for (String line : sse.split("\n")) {
                            if (!line.startsWith("data:")) continue;
                            String data = line.substring(5).strip();
                            if (data.equals("[DONE]") || data.isBlank()) continue;
                            var chunk = JSON.readTree(data);
                            if (chunk.has("usage") && chunk.path("usage").isObject()) usage = chunk.path("usage").deepCopy();
                            if (chunk.has("id")) responseId = chunk.path("id").asText();
                        }
                        if (usage == null) throw new java.io.IOException("真实模型未返回用量，不能宣称 Token 节省");
                        capture.usages.add(usage); capture.ids.add(responseId);
                        return response.newBuilder().body(ResponseBody.create(sse, type)).build();
                    }).build();
            var client = new DeepSeekClient(config, http, JSON, Thread::sleep, DiagnosticSink.NO_OP);
            client.configureReasoning("none");
            var history = List.of(ChatMessage.system("你是工具证据验收助手，只根据工具观察回答，只输出 JSON，不输出 Markdown，不猜测未出现的条目。"),
                    ChatMessage.user("我已经执行了读取样本的命令，请核对结果。"),
                    ChatMessage.assistant("", List.of(new ToolCall("real-api-call", "execute_command", "{\"command\":\"cat fixture.txt\"}"))),
                    ChatMessage.tool("real-api-call", scenario.equals("empty_control") ? control.toString() : originalObservation));
            try (var scheduler = new ScheduledTimeoutScheduler()) {
                var agent = new ReactAgent(history.getFirst(), client,
                        (call, token) -> { throw new IllegalStateException("本探针禁止追加工具执行"); }, List.of(command.definition()),
                        new RunLimits(1, Duration.ofSeconds(120)), scheduler, JSON, Clock.systemUTC(), () -> "real-api-" + scenario);
                agent.setContextAssembler(new ContextAssembler(new TokenBudget(20000)));
                agent.setMemoryRecall(new MemoryManager(workspace.resolve("memory-global"), workspace.resolve("memory-project")), MemoryRecallSettings.defaults());
                agent.setContextOptimizer(scenario.equals("enabled") ? ContextOptimizer.fromEnvironment(optimizerEnv, JSON) : ContextOptimizer.disabled());
                agent.restoreHistory(history);
                var result = agent.run(QUERY, event -> {}, new CancellationToken());
                require(result.status() == RunStatus.COMPLETED, "真实 Agent 未完成：" + scenario + " / " + result.reason());
                require(capture.requests.size() == 1 && capture.usages.size() == 1, "每组必须恰好一次真实 API 请求");
                require(result.usage().known(), "Java 客户端未识别真实用量");
                require(agent.history().get(3).equals(history.get(3)), "原始工具历史发生改写");
                var answer = JSON.readTree(result.finalAnswer());
                int expected = scenario.equals("empty_control") ? 0 : 40;
                require(answer.path("count").asInt(-1) == expected && answer.path("sum").asInt(-1) == (expected == 0 ? 0 : 780), "模型计数或总和不符：" + scenario);
                require(answer.path("ids").isArray() && answer.path("ids").size() == expected, "模型未返回全部编号");
                for (int i = 0; i < expected; i++) require(answer.path("ids").get(i).asInt(-1) == i, "模型漏编号 " + i);
                var sent = capture.requests.getFirst().path("messages").get(3);
                var sentObservation = JSON.readTree(sent.path("content").asText());
                if (expected > 0) for (int i = 0; i < 40; i++) require(sentObservation.path("summary").asText().contains(i + ": return HEADROOM_KEEP_EVIDENCE_" + i + ";"), "请求漏证据 " + i);
                require(sentObservation.path("data").equals(JSON.readTree(originalObservation).path("data")), "data 变化");
                require(capture.usages.getFirst().path("prompt_tokens").asInt() == result.usage().inputTokens(), "SSE 和 Java 的输入用量不一致");
                var row = cases.putObject(scenario);
                row.put("agent_status", result.status().name()).put("response_id", capture.ids.getFirst());
                row.set("provider_usage", capture.usages.getFirst());
                row.set("model_answer", answer); row.set("optimization", JSON.valueToTree(agent.lastContextOptimization()));
                row.put("tool_summary_chars", sentObservation.path("summary").asText().length()).put("original_history_preserved", true);
                Files.writeString(output.resolve(scenario + "-request.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(capture.requests.getFirst()));
                System.out.printf("%s：API 输入 %d Token，正文 %d 字符，模型核对 %d 条证据通过%n", scenario, result.usage().inputTokens(), sentObservation.path("summary").asText().length(), expected);
            } finally {
                http.dispatcher().executorService().shutdown(); http.connectionPool().evictAll();
            }
        }
        ObjectNode normalized = null;
        for (var entry : captures.entrySet()) {
            var body = entry.getValue().requests.getFirst().deepCopy();
            var tool = (ObjectNode) body.path("messages").get(3);
            var observation = (ObjectNode) JSON.readTree(tool.path("content").asText());
            observation.put("summary", ""); tool.put("content", observation.toString());
            if (normalized == null) normalized = body;
            else require(normalized.equals(body), "对照请求存在工具正文以外的差异");
        }
        int before = cases.path("disabled").path("provider_usage").path("prompt_tokens").asInt();
        int after = cases.path("enabled").path("provider_usage").path("prompt_tokens").asInt();
        int empty = cases.path("empty_control").path("provider_usage").path("prompt_tokens").asInt();
        require(cases.path("enabled").path("optimization").path("status").asText().equals("COMPRESSED"), "没有实际采用压缩");
        require(before > after && after > empty, "API 实测输入 Token 没有减少");
        var report = JSON.createObjectNode().put("timestamp_utc", Instant.now().toString()).put("model", config.model())
                .put("provider", config.baseUrl().getScheme() + "://" + config.baseUrl().getHost())
                .put("real_api_requests", 3).put("only_tool_summary_differs", true);
        report.set("cases", cases);
        var savings = report.putObject("savings");
        savings.put("provider_input_tokens_saved", before - after).put("total_input_reduction_percent", 100.0 * (before - after) / before)
                .put("tool_summary_marginal_tokens_before", before - empty).put("tool_summary_marginal_tokens_after", after - empty)
                .put("tool_summary_reduction_percent", 100.0 * (before - after) / (before - empty));
        Files.writeString(output.resolve("report.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }
}
