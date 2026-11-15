package com.xhlcli.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.model.ChatMessage;
import okhttp3.*;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.xhlcli.context.ContextOptimizationReport.Status.*;

/** 调用本机 Headroom，只使用无损工具正文压缩。 */
public final class HeadroomContextOptimizer implements ContextOptimizer {
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int MIN_CHARS = 256;
    private static final Set<String> PROTECTED_TOOLS = Set.of("read_file", "write_file", "apply_patch", "git_diff", "load_skill", "revert_turn", "headroom_retrieve");
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .dns(host -> host.equalsIgnoreCase("localhost")
                    ? List.of(InetAddress.getByAddress(new byte[]{127, 0, 0, 1})) : Dns.SYSTEM.lookup(host))
            .build();
    private final HeadroomSettings settings;
    private final ObjectMapper mapper;
    private final ObjectReader strictJson;

    public HeadroomContextOptimizer(HeadroomSettings settings, ObjectMapper mapper) {
        this.settings = Objects.requireNonNull(settings);
        this.mapper = Objects.requireNonNull(mapper);
        // 观察对象回填时保留 data 中的小数精度，不修改共用 mapper 的配置。
        this.strictJson = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    @Override public boolean enabled() { return settings.enabled(); }

    @Override public Result optimize(List<ChatMessage> messages, String model, CancellationToken cancellation) {
        if (!settings.enabled()) return ContextOptimizer.disabled().optimize(messages, model, cancellation);
        long started = System.nanoTime();
        if (cancellation.isCancelled()) return result(messages, messages, CANCELED, 0, started, "CANCELED");
        List<Payload> payloads = payloads(messages);
        if (payloads.isEmpty()) return result(messages, messages, SKIPPED, 0, started, "NO_ELIGIBLE_CONTENT");
        ObjectNode requestBody = mapper.createObjectNode().put("model", model);
        requestBody.putObject("config").put("mode", "lossless").put("protect_recent", 0).put("compress_user_messages", false);
        ArrayNode sent = requestBody.putArray("messages");
        for (int i = 0; i < payloads.size(); i++) {
            Payload payload = payloads.get(i);
            String id = "headroom-" + i;
            ObjectNode assistant = sent.addObject().put("role", "assistant");
            assistant.putNull("content");
            assistant.putArray("tool_calls").addObject().put("id", id).put("type", "function")
                    .putObject("function").put("name", payload.tool()).put("arguments", "{}");
            sent.addObject().put("role", "tool").put("tool_call_id", id).put("content", payload.text());
        }
        try {
            byte[] body = mapper.writeValueAsBytes(requestBody);
            if (body.length > MAX_BYTES) return result(messages, messages, SKIPPED, 0, started, "REQUEST_LIMIT");
            // 包括 503 Retry-After 在内，任何失败都不能让客户端再次发送正文。
            RequestBody singleSendBody = new RequestBody() {
                @Override public MediaType contentType() { return JSON; }
                @Override public long contentLength() { return body.length; }
                @Override public boolean isOneShot() { return true; }
                @Override public void writeTo(okio.BufferedSink sink) throws IOException { sink.write(body); }
            };
            Call call = HTTP.newCall(new Request.Builder().url(settings.endpoint().toString())
                    .post(singleSendBody).build());
            call.timeout().timeout(settings.timeout().toMillis(), TimeUnit.MILLISECONDS);
            try (CancellationToken.Registration registration = cancellation.onCancel(call::cancel);
                 Response response = call.execute()) {
                if (cancellation.isCancelled()) return result(messages, messages, CANCELED, 0, started, "CANCELED");
                if (!response.isSuccessful()) return result(messages, messages, FALLBACK, 0, started, "HTTP_ERROR");
                ResponseBody responseBody = response.body();
                if (responseBody == null) return result(messages, messages, FALLBACK, 0, started, "INVALID_RESPONSE");
                if (responseBody.contentLength() > MAX_BYTES) return result(messages, messages, FALLBACK, 0, started, "RESPONSE_LIMIT");
                byte[] received = responseBody.byteStream().readNBytes(MAX_BYTES + 1);
                if (received.length > MAX_BYTES) return result(messages, messages, FALLBACK, 0, started, "RESPONSE_LIMIT");
                JsonNode parsed;
                try { parsed = strictJson.readTree(new java.io.ByteArrayInputStream(received)); }
                catch (IOException malformed) { return result(messages, messages, FALLBACK, 0, started, "INVALID_RESPONSE"); }
                if (cancellation.isCancelled()) return result(messages, messages, CANCELED, 0, started, "CANCELED");
                return apply(messages, payloads, sent, parsed, started);
            }
        } catch (IOException failure) {
            if (cancellation.isCancelled()) return result(messages, messages, CANCELED, 0, started, "CANCELED");
            return result(messages, messages, FALLBACK, 0, started, failure instanceof InterruptedIOException ? "TIMEOUT" : "NETWORK_ERROR");
        } catch (IllegalArgumentException | IllegalStateException failure) {
            return result(messages, messages, FALLBACK, 0, started, "INVALID_RESPONSE");
        }
    }

    private List<Payload> payloads(List<ChatMessage> messages) {
        Map<String, String> toolNames = new HashMap<>();
        List<Payload> result = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            for (var call : message.toolCalls()) toolNames.put(call.id(), call.name());
            if (message.role() != ChatMessage.Role.TOOL || message.hasContentParts()) continue;
            String tool = toolNames.get(message.toolCallId());
            if (tool == null || protectedTool(tool)) continue;
            ObjectNode observation = null;
            String text = message.content();
            try {
                JsonNode parsed = strictJson.readTree(text);
                if (parsed == null || !parsed.isObject() || !parsed.path("summary").isTextual()
                        || !parsed.has("status") || !parsed.has("data")) continue;
                observation = (ObjectNode) parsed;
                text = observation.path("summary").asText();
            } catch (IOException plainText) {
                // 看似 JSON 但整体非法的观察不能作为纯文本发送，避免泄露控制字段或丢失尾部。
                String leading = text.stripLeading();
                if (leading.startsWith("{") || leading.startsWith("[") || leading.startsWith("\"")) continue;
            }
            if (text.length() >= MIN_CHARS) result.add(new Payload(i, tool, text, observation));
        }
        return result;
    }

    private boolean protectedTool(String tool) {
        String normalized = tool.toLowerCase(Locale.ROOT);
        return PROTECTED_TOOLS.stream().anyMatch(name -> normalized.equals(name) || normalized.endsWith("__" + name));
    }

    private Result apply(List<ChatMessage> original, List<Payload> payloads, ArrayNode sent, JsonNode response, long started) {
        JsonNode returned = response == null ? null : response.get("messages");
        if (returned == null || !returned.isArray() || returned.size() != sent.size())
            return result(original, original, FALLBACK, 0, started, "INVALID_RESPONSE");
        List<ChatMessage> optimized = new ArrayList<>(original);
        int changed = 0;
        for (int i = 0; i < sent.size(); i++) {
            JsonNode candidate = returned.get(i);
            if (i % 2 == 0) {
                if (!sent.get(i).equals(candidate)) return result(original, original, FALLBACK, 0, started, "INVALID_RESPONSE");
                continue;
            }
            if (!candidate.isObject() || !candidate.path("content").isTextual())
                return result(original, original, FALLBACK, 0, started, "INVALID_RESPONSE");
            ObjectNode expectedMetadata = ((ObjectNode) sent.get(i)).deepCopy(); expectedMetadata.remove("content");
            ObjectNode actualMetadata = ((ObjectNode) candidate).deepCopy(); actualMetadata.remove("content");
            String text = candidate.path("content").asText();
            if (!expectedMetadata.equals(actualMetadata) || text.isBlank() || text.contains("<<ccr:")
                    || text.contains("headroom_retrieve") || text.contains("Retrieve more: hash="))
                return result(original, original, FALLBACK, 0, started, "INVALID_RESPONSE");
            Payload payload = payloads.get(i / 2);
            if (text.length() >= payload.text().length()) continue;
            ChatMessage old = original.get(payload.index());
            String content = text;
            if (payload.observation() != null) {
                ObjectNode observation = payload.observation().deepCopy(); observation.put("summary", text);
                content = observation.toString();
            }
            ChatMessage next = new ChatMessage(old.role(), content, old.toolCalls(), old.toolCallId(), old.contentParts(), old.reasoningContent());
            if (TokenBudget.estimateTokens(next) >= TokenBudget.estimateTokens(old)) continue;
            optimized.set(payload.index(), next); changed++;
        }
        return result(original, changed > 0 ? optimized : original, changed > 0 ? COMPRESSED : UNCHANGED,
                changed, started, changed > 0 ? "APPLIED" : "NO_SAVINGS");
    }

    private Result result(List<ChatMessage> before, List<ChatMessage> after, ContextOptimizationReport.Status status,
                          int changed, long started, String reason) {
        return new Result(after, new ContextOptimizationReport(status, TokenBudget.estimateTokens(before),
                TokenBudget.estimateTokens(after), changed, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), reason));
    }

    private record Payload(int index, String tool, String text, ObjectNode observation) {}
}
