# Phase 01 Terminal Chat Implementation Plan

> 2026-08-26 范围修订：按用户最终决定，Phase 01 仅以 macOS Java 21 CI 作为发布门禁，不承诺 Windows/Linux 兼容性。本修订优先于下文早期计划中的三平台矩阵要求。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a Java 21 XhlCLI that securely configures DeepSeek and supports cancellable, streaming, multi-turn terminal chat without Agent or local-tool capabilities.

**Architecture:** Selectively migrate Paicli's OpenAI-compatible SSE and DeepSeek adapter into small `config`, `llm`, `app`, `render`, and `cli` units. Keep conversation state and user-visible events provider-neutral, keep HTTP details inside `llm`, and use JLine only for portable line input and Ctrl+C handling.

**Tech Stack:** Java 21, Maven Wrapper, OkHttp 5.5.0, Jackson Databind 2.22.2, JLine 4.3.1, JUnit 5.10.2, MockWebServer 5.5.0, Maven Shade Plugin 3.6.2.

**Spec:** `docs/superpowers/specs/2026-08-26-phase-01-terminal-chat-design.md`

## Global Constraints

- Work directly in the existing `main` checkout; do not create a Git worktree.
- Keep `maven.compiler.release=21`; do not use preview APIs.
- Use `com.xhlcli` packages, XhlCLI branding, `~/.xhlcli`, and `.xhlcli` paths.
- Read Paicli with `git -C ../paicli show <commit>:<path>` because its working tree contains user changes and is strictly read-only.
- Adopt only Phase 01 chat behavior from Paicli commits `e2b8df4`, `530bb9c`, `f49d33c`, and the current committed `DeepSeekClient`; do not migrate Agent, Tool Call, multi-model, reasoning display, TUI, or persistence code.
- Never print, log, commit, or upload `DEEPSEEK_API_KEY`; real `.env` remains ignored.
- Automated tests must use injected environment maps, temporary directories, fake clients, and MockWebServer; they must not read a real Key, network, or user home.
- Do not push any Phase 01 commit until the user has filled `.env` and the real-provider gate has passed.
- Create `v0.2.0` only after the pushed `main` commit passes the Ubuntu/macOS/Windows Java 21 CI matrix.

---

### Task 1: Build and Configuration Contract

**Files:**

- Modify: `pom.xml`
- Modify: `.env.example`
- Create: `src/main/java/com/xhlcli/config/ConfigKey.java`
- Create: `src/main/java/com/xhlcli/config/ConfigSource.java`
- Create: `src/main/java/com/xhlcli/config/LogLevel.java`
- Create: `src/main/java/com/xhlcli/config/ConfigurationException.java`
- Create: `src/main/java/com/xhlcli/config/ChatConfig.java`
- Create: `src/main/java/com/xhlcli/config/ChatConfigLoader.java`
- Create: `src/main/java/com/xhlcli/config/SecretRedactor.java`
- Test: `src/test/java/com/xhlcli/config/ChatConfigLoaderTest.java`
- Test: `src/test/java/com/xhlcli/config/SecretRedactorTest.java`

**Interfaces:**

- Consumes: CLI arguments, `Map<String, String>` process environment, project directory, and user-home directory.
- Produces: `ChatConfigLoader.load(String[], Map<String,String>, Path, Path)`, immutable `ChatConfig`, `ConfigKey`, `ConfigSource`, and `SecretRedactor.redact(String, String)`.

- [ ] **Step 1: Add only the Phase 01 runtime and test dependencies**

Change the project version to `0.2.0-SNAPSHOT`. Add Maven properties and dependencies:

```xml
<okhttp.version>5.5.0</okhttp.version>
<jackson.version>2.22.2</jackson.version>
<jline.version>4.3.1</jline.version>

<dependency>
    <groupId>com.squareup.okhttp3</groupId>
    <artifactId>okhttp</artifactId>
    <version>${okhttp.version}</version>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>${jackson.version}</version>
</dependency>
<dependency>
    <groupId>org.jline</groupId>
    <artifactId>jline</artifactId>
    <version>${jline.version}</version>
</dependency>
<dependency>
    <groupId>org.jline</groupId>
    <artifactId>jline-terminal-jni</artifactId>
    <version>${jline.version}</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>com.squareup.okhttp3</groupId>
    <artifactId>mockwebserver</artifactId>
    <version>${okhttp.version}</version>
    <scope>test</scope>
</dependency>
```

Add Maven Shade Plugin 3.6.2 at `package`, with `ManifestResourceTransformer` main class `com.xhlcli.cli.Main`, so dependencies remain runnable from `target/xhlcli-0.2.0-SNAPSHOT.jar`.

- [ ] **Step 2: Write failing configuration and redaction tests**

The configuration test must create `.env` and `~/.xhlcli/config.json` under `@TempDir`, then assert exact precedence and sources:

```java
ChatConfig config = ChatConfigLoader.load(
        new String[]{"--model", "cli-model", "--read-timeout", "45"},
        Map.of("DEEPSEEK_MODEL", "env-model", "DEEPSEEK_BASE_URL", "https://env.example/v1"),
        projectDir,
        userHome);

assertEquals("dot-env-secret", config.apiKey());
assertEquals("cli-model", config.model());
assertEquals(URI.create("https://env.example/v1"), config.baseUrl());
assertEquals(Duration.ofSeconds(45), config.readTimeout());
assertEquals(ConfigSource.DOT_ENV, config.source(ConfigKey.API_KEY));
assertEquals(ConfigSource.CLI, config.source(ConfigKey.MODEL));
assertEquals(ConfigSource.ENVIRONMENT, config.source(ConfigKey.BASE_URL));
```

Also assert defaults (`deepseek-v4-flash`, `https://api.deepseek.com`, 30/300/600 seconds, WARN), process-environment priority over `.env`, JSON priority over defaults, rejection of API Key in JSON, invalid URL/timeout rejection, unknown argument rejection, missing option value rejection, and `--base-url http://localhost:<port>` acceptance.

The redaction test must assert that the literal Key, `Bearer <key>`, and JSON keys `api_key`, `apiKey`, `authorization`, `token`, and `password` never remain in output.

- [ ] **Step 3: Run the focused tests and observe the expected RED state**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=ChatConfigLoaderTest,SecretRedactorTest test
```

Expected: test compilation fails because the `com.xhlcli.config` types do not exist.

- [ ] **Step 4: Implement the immutable configuration types**

```java
public enum ConfigKey {
    API_KEY, MODEL, BASE_URL, CONNECT_TIMEOUT, READ_TIMEOUT, REQUEST_TIMEOUT, LOG_LEVEL
}

public enum ConfigSource {
    CLI, ENVIRONMENT, DOT_ENV, USER_CONFIG, DEFAULT, MISSING
}

public record ChatConfig(
        String apiKey,
        String model,
        URI baseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        Duration requestTimeout,
        LogLevel logLevel,
        Map<ConfigKey, ConfigSource> sources) {
    public ChatConfig {
        sources = Map.copyOf(sources);
    }

    public ConfigSource source(ConfigKey key) {
        return sources.getOrDefault(key, ConfigSource.MISSING);
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
```

`ConfigurationException` extends `Exception` and contains only a safe user-facing message.

`LogLevel` contains `ERROR`, `WARN`, `INFO`, and `DEBUG`, parses case-insensitively, and exposes `boolean allows(LogLevel eventLevel)`.

- [ ] **Step 5: Implement deterministic configuration merging**

`ChatConfigLoader.load` must:

1. Parse `~/.xhlcli/config.json` into a private Jackson record containing only `model`, `baseUrl`, `connectTimeoutSeconds`, `readTimeoutSeconds`, `requestTimeoutSeconds`, and `logLevel`.
2. Reject a JSON tree containing `apiKey`, `api_key`, `token`, `authorization`, or `password` before mapping it.
3. Parse project `.env` as UTF-8 `KEY=VALUE`, ignoring blank lines/comments and removing one matching pair of surrounding single or double quotes.
4. Resolve process environment over `.env`, then user JSON, then defaults.
5. Apply CLI non-secret overrides last for `--model`, `--base-url`, `--connect-timeout`, `--read-timeout`, `--request-timeout`, and `--log-level`.
6. Validate positive timeout seconds and an absolute HTTP/HTTPS URI with a host.
7. Never read the actual `System.getenv`, `user.home`, or current directory inside this overload.

The API Key resolution is exactly environment > `.env` > missing; user JSON and CLI never accept it.

Implement resolution through one helper that records both value and source:

```java
private static <T> Resolved<T> firstPresent(
        Resolved<T> cli, Resolved<T> environment, Resolved<T> dotEnv,
        Resolved<T> userConfig, Resolved<T> defaults) {
    return Stream.of(cli, environment, dotEnv, userConfig, defaults)
            .filter(Objects::nonNull)
            .filter(Resolved::hasValue)
            .findFirst()
            .orElseThrow();
}
```

- [ ] **Step 6: Update `.env.example` without adding a real secret**

```dotenv
# Copy this file to .env. The .env file is ignored by Git.
DEEPSEEK_API_KEY=replace_with_your_deepseek_api_key

# Optional overrides
# DEEPSEEK_MODEL=deepseek-v4-flash
# DEEPSEEK_BASE_URL=https://api.deepseek.com
# XHLCLI_CONNECT_TIMEOUT_SECONDS=30
# XHLCLI_READ_TIMEOUT_SECONDS=300
# XHLCLI_REQUEST_TIMEOUT_SECONDS=600
# XHLCLI_LOG_LEVEL=WARN
```

- [ ] **Step 7: Run focused and full tests**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=ChatConfigLoaderTest,SecretRedactorTest test
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw test
```

Expected: configuration tests pass; Phase 00 CLI tests remain green.

- [ ] **Step 8: Commit the configuration contract locally**

```bash
git add pom.xml .env.example src/main/java/com/xhlcli/config src/test/java/com/xhlcli/config
git commit -m "feat: add secure DeepSeek configuration"
```

Do not push.

---

### Task 2: Provider-Neutral Chat and Cancellation Model

**Files:**

- Create: `src/main/java/com/xhlcli/model/ChatMessage.java`
- Create: `src/main/java/com/xhlcli/model/TokenUsage.java`
- Create: `src/main/java/com/xhlcli/model/ChatResponse.java`
- Create: `src/main/java/com/xhlcli/llm/LlmErrorType.java`
- Create: `src/main/java/com/xhlcli/llm/LlmException.java`
- Create: `src/main/java/com/xhlcli/llm/StreamListener.java`
- Create: `src/main/java/com/xhlcli/llm/DiagnosticSink.java`
- Create: `src/main/java/com/xhlcli/llm/CancellationToken.java`
- Create: `src/main/java/com/xhlcli/llm/LlmClient.java`
- Test: `src/test/java/com/xhlcli/model/ChatMessageTest.java`
- Test: `src/test/java/com/xhlcli/llm/CancellationTokenTest.java`

**Interfaces:**

- Consumes: validated text messages and cancellation callbacks.
- Produces: `LlmClient.stream(List<ChatMessage>, StreamListener, CancellationToken)`, `DiagnosticSink`, and immutable response/error types used by all later tasks.

- [ ] **Step 1: Write failing model and cancellation tests**

Test role serialization, blank-content rejection, immutable response values, unknown TokenUsage, cancel-before-registration, cancel-after-registration, idempotent cancel, and callback deregistration:

```java
CancellationToken token = new CancellationToken();
AtomicInteger calls = new AtomicInteger();
CancellationToken.Registration registration = token.onCancel(calls::incrementAndGet);

token.cancel();
token.cancel();
registration.close();

assertTrue(token.isCancelled());
assertEquals(1, calls.get());
```

- [ ] **Step 2: Run tests to confirm RED**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=ChatMessageTest,CancellationTokenTest test
```

Expected: compilation failure for the missing model and LLM types.

- [ ] **Step 3: Implement the exact minimal contracts**

```java
public record ChatMessage(Role role, String content) {
    public enum Role {
        SYSTEM("system"), USER("user"), ASSISTANT("assistant");
        private final String wireName;
        Role(String wireName) { this.wireName = wireName; }
        public String wireName() { return wireName; }
    }
}

public record TokenUsage(int inputTokens, int outputTokens, boolean known) {
    public static TokenUsage unknown() { return new TokenUsage(0, 0, false); }
}

public record ChatResponse(String content, TokenUsage usage) {}

public enum LlmErrorType {
    MISSING_CONFIGURATION, AUTHENTICATION, RATE_LIMIT, NETWORK, SERVER,
    INVALID_RESPONSE, TIMEOUT, CANCELLED, INVALID_CONFIGURATION
}

@FunctionalInterface
public interface StreamListener {
    void onTextDelta(String delta);
}

public interface LlmClient extends AutoCloseable {
    ChatResponse stream(List<ChatMessage> messages, StreamListener listener,
                        CancellationToken cancellationToken) throws LlmException;
    @Override default void close() {}
}

@FunctionalInterface
public interface DiagnosticSink {
    DiagnosticSink NO_OP = (event, metadata) -> {};
    void debug(String event, Map<String, String> metadata);
}
```

`LlmException` stores `LlmErrorType type`, safe message, `boolean retryable`, and `boolean partialResponse`; it must not expose raw response bodies containing credentials.

`CancellationToken` uses an `AtomicBoolean` plus a thread-safe callback collection. Its nested `Registration extends AutoCloseable` redeclares `void close()` without a checked exception. Registering after cancellation invokes immediately; each callback runs at most once; callback exceptions do not prevent remaining callbacks.

- [ ] **Step 4: Run focused and full tests**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=ChatMessageTest,CancellationTokenTest test
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw test
```

Expected: all tests pass.

- [ ] **Step 5: Commit the model boundary locally**

```bash
git add src/main/java/com/xhlcli/model src/main/java/com/xhlcli/llm src/test/java/com/xhlcli/model src/test/java/com/xhlcli/llm
git commit -m "feat: define streaming chat contracts"
```

Do not push.

---
### Task 3: DeepSeek OpenAI-Compatible Streaming Client

**Files:**

- Create: `src/main/java/com/xhlcli/llm/OpenAiSseParser.java`
- Create: `src/main/java/com/xhlcli/llm/AbstractOpenAiCompatibleClient.java`
- Create: `src/main/java/com/xhlcli/llm/DeepSeekClient.java`
- Test: `src/test/java/com/xhlcli/llm/DeepSeekClientTest.java`

**Interfaces:**

- Consumes: `ChatConfig`, `List<ChatMessage>`, `StreamListener`, and `CancellationToken`.
- Produces: `DeepSeekClient(ChatConfig, DiagnosticSink)` and package-private injectable constructor `(ChatConfig, OkHttpClient, ObjectMapper, Sleeper, DiagnosticSink)` implementing `LlmClient`.

- [ ] **Step 1: Inspect the authorized source without touching its dirty worktree**

```bash
git -C ../paicli show f49d33c:src/main/java/com/paicli/llm/AbstractOpenAiCompatibleClient.java
git -C ../paicli show f49d33c:src/main/java/com/paicli/llm/DeepSeekClient.java
git -C ../paicli show HEAD:src/main/java/com/paicli/llm/DeepSeekClient.java
```

Use the request shape, SSE loop, Bearer header, HTTP/1.1 DeepSeek override, and response accumulation. Exclude tools, reasoning callbacks, model capabilities, prompt caching, images, and multi-provider factory code.

- [ ] **Step 2: Write the failing happy-path streaming test**

Enqueue SSE with two content chunks, a final usage chunk, and `[DONE]`. Assert deltas, complete response, request path/header/body, message order, and `stream: true`:

```java
server.enqueue(sse("""
        data: {"choices":[{"delta":{"role":"assistant","content":"你"}}]}

        data: {"choices":[{"delta":{"content":"好"}}]}

        data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":7,"completion_tokens":2}}

        data: [DONE]

        """));

List<String> deltas = new ArrayList<>();
ChatResponse response = client.stream(messages, deltas::add, new CancellationToken());

assertEquals(List.of("你", "好"), deltas);
assertEquals("你好", response.content());
assertEquals(new TokenUsage(7, 2, true), response.usage());
```

- [ ] **Step 3: Run the happy-path test to confirm RED**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=DeepSeekClientTest test
```

Expected: compilation failure because `DeepSeekClient` does not exist.

- [ ] **Step 4: Implement URL normalization, request serialization, and SSE parsing**

The production constructor builds one OkHttpClient per `ChatConfig` with connect/read/call timeouts and protocols `List.of(Protocol.HTTP_1_1)`.

`AbstractOpenAiCompatibleClient` must:

- normalize Base URL and append `/chat/completions` exactly once;
- serialize only `model`, `stream`, and ordered role/content messages;
- register `Call.cancel()` with the token before `execute()` and deregister it in `finally`;
- close every `Response` with try-with-resources;
- parse SSE through `OpenAiSseParser`;
- retry at most once after 250 ms only for pre-delta network errors, 429, and 5xx;
- map 401 to `AUTHENTICATION`, 429 to `RATE_LIMIT`, 5xx to `SERVER`, timeouts to `TIMEOUT`, explicit cancellation to `CANCELLED`, and malformed/empty/incomplete streams to `INVALID_RESPONSE`;
- sanitize provider error text through `SecretRedactor` before constructing `LlmException`.
- emit only sanitized request-start, retry, HTTP-status, completion, timeout, and cancellation metadata through `DiagnosticSink`; never include message content or Authorization.

Use a package-private functional interface so tests skip real waiting:

```java
@FunctionalInterface
interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
}

private ChatResponse executeAttempt(List<ChatMessage> messages,
                                    StreamListener listener,
                                    CancellationToken token) throws LlmException {
    Request request = buildRequest(messages);
    Call call = httpClient.newCall(request);
    try (CancellationToken.Registration ignored = token.onCancel(call::cancel);
         Response response = call.execute()) {
        requireSuccessful(response);
        return parser.parse(response.body(), listener, token);
    } catch (IOException failure) {
        throw mapIoFailure(failure, token);
    }
}
```

`OpenAiSseParser` returns known usage only when both token fields exist, ignores unknown events and reasoning fields, requires at least one nonblank content delta and `[DONE]`, and marks parsing errors after a delta as partial.

- [ ] **Step 5: Add error, retry, timeout, cancellation, and断流 tests**

Add deterministic cases for:

- 401 does not retry and message excludes Key;
- 429 retries once and succeeds on the second queued response;
- 503 retries once then returns SERVER;
- invalid JSON returns INVALID_RESPONSE;
- EOF without `[DONE]` returns INVALID_RESPONSE with `partialResponse=true` after one delta;
- unknown SSE event is ignored;
- delayed body exceeds a 100 ms test read timeout and returns TIMEOUT;
- cancellation calls `Call.cancel()` and returns CANCELLED;
- response without usage returns `TokenUsage.unknown()`;
- Base URL already ending in `/chat/completions` is not duplicated.

- [ ] **Step 6: Run focused and full verification**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=DeepSeekClientTest test
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw test
```

Expected: all SSE and error cases pass without external network access.

- [ ] **Step 7: Commit the DeepSeek client locally**

```bash
git add src/main/java/com/xhlcli/llm src/test/java/com/xhlcli/llm
git commit -m "feat: stream DeepSeek chat completions"
```

Do not push.

---

### Task 4: Conversation State, Events, and Plain Renderer

**Files:**

- Create: `src/main/java/com/xhlcli/app/ChatEvent.java`
- Create: `src/main/java/com/xhlcli/app/ChatEventSink.java`
- Create: `src/main/java/com/xhlcli/app/ChatSession.java`
- Create: `src/main/java/com/xhlcli/render/PlainChatRenderer.java`
- Create: `src/main/java/com/xhlcli/render/PlainDiagnosticSink.java`
- Test: `src/test/java/com/xhlcli/app/ChatSessionTest.java`
- Test: `src/test/java/com/xhlcli/render/PlainChatRendererTest.java`
- Test: `src/test/java/com/xhlcli/render/PlainDiagnosticSinkTest.java`

**Interfaces:**

- Consumes: `LlmClient` and provider-neutral chat values.
- Produces: `ChatSession.send(String, ChatEventSink, CancellationToken)`, `clear()`, `history()`, sealed `ChatEvent`, `PlainChatRenderer.accept(ChatEvent)`, and sanitized debug logging.

- [ ] **Step 1: Write failing multi-turn and rollback tests**

Use a recording fake client to assert the exact system/user/assistant order across five sends. The initial and post-clear history must contain only:

```java
new ChatMessage(ChatMessage.Role.SYSTEM,
        "You are XhlCLI, a helpful coding assistant. In Phase 01 you have no tools "
                + "and must not claim to inspect or modify local files.")
```

Assert blank input does not call the client, `/clear` semantics reset history, successful sends append user+assistant, and authentication/cancel/partial-response failures leave history unchanged.

- [ ] **Step 2: Write failing event and renderer tests**

Use byte-array streams and assert:

- Waiting emits a short non-secret status;
- first TextDelta prints the assistant prefix exactly once;
- subsequent deltas append without added spaces;
- Completed restores a newline and prints `tokens: input=7, output=2` or `tokens: unknown`;
- Failed prints the error category and actionable suggestion;
- partial failure includes `response incomplete`;
- Cancelled prints a cancellation message and restores prompt-safe newline;
- a configured Key passed to the renderer redactor never appears.

- [ ] **Step 3: Run focused tests to confirm RED**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=ChatSessionTest,PlainChatRendererTest,PlainDiagnosticSinkTest test
```

Expected: compilation failure for missing app/render types.

- [ ] **Step 4: Implement the event and session contract**

```java
public sealed interface ChatEvent {
    record Waiting() implements ChatEvent {}
    record TextDelta(String text) implements ChatEvent {}
    record Completed(TokenUsage usage) implements ChatEvent {}
    record Failed(LlmErrorType type, String message, boolean partial) implements ChatEvent {}
    record Cancelled() implements ChatEvent {}
}

@FunctionalInterface
public interface ChatEventSink {
    void accept(ChatEvent event);
}
```

`ChatSession.send` builds a temporary request list, emits Waiting, forwards each nonempty delta as TextDelta, calls the client, commits user+assistant only after success, and emits Completed. It converts `LlmException(CANCELLED)` to Cancelled and all other failures to Failed without swallowing the error classification.

`history()` returns `List.copyOf(history)`; `clear()` restores the single fixed system message.

- [ ] **Step 5: Implement PlainChatRenderer**

The renderer owns `PrintStream out`, `PrintStream err`, and the configured Key string. It calls `SecretRedactor.redact(text, apiKey)`, maps error types to fixed suggestions, and never prints a stack trace in normal mode. Synchronize `accept` so signal and request threads cannot interleave fragments.

Use a sealed-event switch so every state has one output path:

```java
public synchronized void accept(ChatEvent event) {
    switch (event) {
        case ChatEvent.Waiting ignored -> renderWaiting();
        case ChatEvent.TextDelta delta -> renderDelta(delta.text());
        case ChatEvent.Completed completed -> renderUsage(completed.usage());
        case ChatEvent.Failed failed -> renderFailure(failed);
        case ChatEvent.Cancelled ignored -> renderCancelled();
    }
}
```

`PlainDiagnosticSink` implements `DiagnosticSink`, checks `ChatConfig.logLevel()`, formats only the provided metadata map, redacts it, and writes to stderr only when DEBUG is enabled. Add tests that DEBUG emits provider/model/attempt but never prompts or Key, while WARN emits nothing.

- [ ] **Step 6: Run focused and full tests**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=ChatSessionTest,PlainChatRendererTest,PlainDiagnosticSinkTest test
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw test
```

Expected: all tests pass.

- [ ] **Step 7: Commit the conversation layer locally**

```bash
git add src/main/java/com/xhlcli/app src/main/java/com/xhlcli/render src/test/java/com/xhlcli/app src/test/java/com/xhlcli/render
git commit -m "feat: manage streaming chat sessions"
```

Do not push.

---

### Task 5: Interactive CLI and Ctrl+C Lifecycle

**Files:**

- Modify: `src/main/java/com/xhlcli/cli/CliApplication.java`
- Modify: `src/main/java/com/xhlcli/cli/Main.java`
- Create: `src/main/java/com/xhlcli/cli/ChatCommand.java`
- Create: `src/main/java/com/xhlcli/cli/ChatCommandParser.java`
- Create: `src/main/java/com/xhlcli/cli/InputReader.java`
- Create: `src/main/java/com/xhlcli/cli/JLineTerminalSession.java`
- Create: `src/main/java/com/xhlcli/cli/ChatLoop.java`
- Create: `src/main/java/com/xhlcli/cli/ChatRunner.java`
- Create: `src/main/java/com/xhlcli/cli/ChatBootstrap.java`
- Test: `src/test/java/com/xhlcli/cli/ChatCommandParserTest.java`
- Test: `src/test/java/com/xhlcli/cli/ChatLoopTest.java`
- Modify test: `src/test/java/com/xhlcli/cli/CliApplicationTest.java`

**Interfaces:**

- Consumes: `ChatConfigLoader`, `DeepSeekClient`, `ChatSession`, `PlainChatRenderer`, and an injected `InputReader`.
- Produces: executable chat mode, `/help`, `/config`, `/clear`, `/exit`, stable process exit codes, and `ChatLoop.cancelActiveResponse()` for the INT signal handler.

- [ ] **Step 1: Write failing slash-command parser tests**

```java
assertEquals(ChatCommand.HELP, parser.parse("/help"));
assertEquals(ChatCommand.CONFIG, parser.parse(" /config "));
assertEquals(ChatCommand.CLEAR, parser.parse("/CLEAR"));
assertEquals(ChatCommand.EXIT, parser.parse("/exit"));
assertEquals(ChatCommand.USER_MESSAGE, parser.parse("explain /help"));
assertEquals(ChatCommand.UNKNOWN, parser.parse("/tools"));
```

- [ ] **Step 2: Write failing ChatLoop tests with fakes**

Cover `/help`, `/config` redaction, `/clear`, `/exit`, EOF, blank input, unknown slash command, five user turns, provider failure followed by a successful next turn, idle interrupt, and active cancellation:

```java
CompletableFuture<Void> run = CompletableFuture.runAsync(loop::run);
assertTrue(fakeClient.awaitRequest(Duration.ofSeconds(2)));
assertTrue(loop.cancelActiveResponse());
fakeInput.add("reply only OK");
fakeInput.add("/exit");
run.get(5, TimeUnit.SECONDS);

assertTrue(fakeClient.firstRequestWasCancelled());
assertTrue(output.contains("OK"));
```

- [ ] **Step 3: Update CLI application tests before implementation**

Keep the Phase 00 help/version/unknown-option assertions and change no-argument behavior to invoke an injected `ChatRunner`. Add missing-Key exit code 3 and safe guidance assertions. Define exit codes: 0 normal/help/version, 2 CLI usage, 3 configuration/bootstrap failure.

- [ ] **Step 4: Run focused CLI tests to confirm RED**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=ChatCommandParserTest,ChatLoopTest,CliApplicationTest test
```

Expected: compilation failures for new CLI types and changed constructor behavior.

- [ ] **Step 5: Implement parser and testable loop**

`InputReader` exposes `String readLine(String prompt)` and throws explicit EOF/interrupt exceptions defined in the CLI package. `ChatLoop.run()` reads sequentially, routes commands without calling the model, creates one new `CancellationToken` per user turn, stores it in an `AtomicReference`, and clears it in `finally`.

`cancelActiveResponse()` returns false when idle and otherwise cancels the active token exactly once. `/config` uses `ConfigSource` values and prints `apiKey=configured (DOT_ENV)` or `apiKey=missing`; it never prints the value.

Use these boundaries so CLI metadata tests never open a real terminal:

```java
@FunctionalInterface
public interface ChatRunner {
    int run(String[] args);
}

public int run() {
    while (true) {
        String input = inputReader.readLine("You > ");
        switch (commandParser.parse(input)) {
            case HELP -> renderer.printHelp();
            case CONFIG -> renderer.printConfig(config);
            case CLEAR -> { session.clear(); renderer.printCleared(); }
            case EXIT -> { renderer.printGoodbye(); return 0; }
            case UNKNOWN -> renderer.printUnknownCommand(input);
            case USER_MESSAGE -> sendTurn(input);
        }
    }
}
```

- [ ] **Step 6: Implement the JLine adapter and production bootstrap**

`JLineTerminalSession` builds a plain Terminal and LineReader, converts `UserInterruptException` and `EndOfFileException` to CLI exceptions, and installs `Terminal.Signal.INT` so an active request calls `ChatLoop.cancelActiveResponse()`. When idle, JLine retains normal line-interrupt behavior.

`CliApplication` handles sole `--help`/`--version` first and delegates all other argument arrays to its injected `ChatRunner`. `ChatBootstrap` passes those arguments to `ChatConfigLoader`, validates Key presence, creates `DeepSeekClient`, `ChatSession`, renderers, and `JLineTerminalSession`, and shows:

```text
DeepSeek API Key is missing.
Copy .env.example to .env and set DEEPSEEK_API_KEY, then run XhlCLI again.
```

`Main` supplies `System.getenv()`, `Path.of("").toAbsolutePath()`, `Path.of(System.getProperty("user.home"))`, stdout/stderr, and the manifest version.

Help must list Phase 01 commands and non-secret options while retaining `--help` and `--version`.

- [ ] **Step 7: Run CLI and full tests**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=ChatCommandParserTest,ChatLoopTest,CliApplicationTest test
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw test
```

Expected: all tests pass without a real Key.

- [ ] **Step 8: Package and smoke-test metadata and missing-Key behavior**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw package
java -jar target/xhlcli-0.2.0-SNAPSHOT.jar --help
java -jar target/xhlcli-0.2.0-SNAPSHOT.jar --version
env -u DEEPSEEK_API_KEY java -jar target/xhlcli-0.2.0-SNAPSHOT.jar
```

Expected: help and version exit 0; no-Key chat startup exits 3 with setup guidance; no stack trace or secret appears.

- [ ] **Step 9: Commit the interactive CLI locally**

```bash
git add src/main/java/com/xhlcli/cli src/test/java/com/xhlcli/cli
git commit -m "feat: add cancellable terminal chat loop"
```

Do not push.

---

### Task 6: Offline Release Gate, Cross-Platform CI, and Documentation

**Files:**

- Modify: `.github/workflows/ci.yml`
- Modify: `README.md`
- Modify: `CHANGELOG.md`
- Modify: `ROADMAP.md`
- Modify: `AGENTS.md`
- Modify: `SECURITY.md`
- Modify: `docs/prd/phase-01-terminal-chat.md`

**Interfaces:**

- Consumes: all Phase 01 implementation and tests.
- Produces: truthful setup documentation, three-OS build gate, local offline verification evidence, and a clear pause point for the user to add a real Key.

- [ ] **Step 1: Expand CI to a Java 21 operating-system matrix**

Set `strategy.fail-fast: false` and `matrix.os: [ubuntu-latest, macos-latest, windows-latest]`. Keep current `actions/checkout@v7` and `actions/setup-java@v6`. Use conditional wrapper steps:

```yaml
- name: Verify on Unix
  if: runner.os != 'Windows'
  run: ./mvnw --batch-mode verify
- name: Verify on Windows
  if: runner.os == 'Windows'
  run: .\mvnw.cmd --batch-mode verify
- name: Smoke test executable JAR
  run: |
    java -jar target/xhlcli-0.2.0-SNAPSHOT.jar --help
    java -jar target/xhlcli-0.2.0-SNAPSHOT.jar --version
```

- [ ] **Step 2: Update docs to implemented-but-awaiting-real-verification state**

README must document `.env`, DeepSeek console Key creation link, default model/Base URL, build/run commands, commands, config precedence, troubleshooting, and the explicit boundary “Phase 01 cannot read or modify local files and has no Agent tools.”

CHANGELOG adds an Unreleased Phase 01 section. ROADMAP and AGENTS say “implemented locally; real-provider and release verification pending.” Phase 01 PRD status becomes “实施完成，验收中.” SECURITY adds Key rotation guidance and confirms `/config` redaction.

- [ ] **Step 3: Run the full offline gate from Java 21**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw clean verify
java -jar target/xhlcli-0.2.0-SNAPSHOT.jar --help
java -jar target/xhlcli-0.2.0-SNAPSHOT.jar --version
javap -verbose -classpath target/classes com.xhlcli.cli.Main | rg 'major version: 65'
```

Expected: all tests and package steps pass; metadata commands match README; bytecode is Java 21.

- [ ] **Step 4: Verify the Phase 01 boundary and secrets**

```bash
test -z "$(find src/main/java -type f | rg '/(agent|tool|mcp|rag|memory|plan)/' || true)"
test -z "$(git ls-files | rg '(^|/)(target|\.env|\.xhlcli|logs|output)/' || true)"
test -z "$(rg -n '(sk-[A-Za-z0-9]{20,}|ghp_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}|BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY)' . --hidden --glob '!.git/**' --glob '!target/**' || true)"
git diff --check
```

Expected: no later-phase package, ignored runtime data, real credential, or whitespace error is found.

- [ ] **Step 5: Verify from only tracked files in a temporary directory**

Create a temporary directory with `mktemp -d`, extract `git archive HEAD` plus the current committed implementation commits, run `./mvnw test`, `./mvnw package`, and JAR help, then move the temporary directory to Trash. Do not copy `.env`, `target`, parent workspace files, or `../paicli`.

- [ ] **Step 6: Commit the offline-complete state locally**

```bash
git add .github README.md CHANGELOG.md ROADMAP.md AGENTS.md SECURITY.md docs/prd/phase-01-terminal-chat.md
git commit -m "docs: prepare phase one provider verification"
git status --short --ignored
```

Expected: only ignored `.DS_Store`, `target/`, and the user's future `.env` may appear. Do not push.

- [ ] **Step 7: Pause and give the user exact local Key instructions**

Tell the user to run from the repository root:

```bash
cp .env.example .env
chmod 600 .env
```

Then edit `.env` so only their real value replaces `replace_with_your_deepseek_api_key`. Tell them not to paste the Key into chat. Wait for their confirmation before Task 7.

---

### Task 7: Real DeepSeek Gate, Demo Artifact, and Remote Release

**Files:**

- Create: `docs/assets/xhlcli-phase-01-demo.gif`
- Modify: `pom.xml`
- Modify: `.github/workflows/ci.yml`
- Modify: `README.md`
- Modify: `CHANGELOG.md`
- Modify: `ROADMAP.md`
- Modify: `AGENTS.md`
- Modify: `docs/prd/phase-01-terminal-chat.md`

**Interfaces:**

- Consumes: user-owned ignored `.env`, final executable JAR, GitHub Actions, and Git tag permissions.
- Produces: evidence of five-turn real-provider history, Ctrl+C recovery, sanitized demo GIF, final `0.2.0` release metadata, pushed main, green remote CI, and `v0.2.0`.

- [ ] **Step 1: Confirm the Key is usable without exposing it**

Check only presence and file tracking state:

```bash
test -f .env
test -z "$(git ls-files .env)"
test -n "$(sed -n 's/^DEEPSEEK_API_KEY=//p' .env | head -n 1)"
```

Do not print the substituted value or run commands that echo the environment.

- [ ] **Step 2: Run and record a five-turn real conversation**

Start the JAR under a PTY and `script` capture:

```bash
script -q target/phase01-real-session.typescript java -jar target/xhlcli-0.2.0-SNAPSHOT.jar
```

Send these turns in order, checking that each answer streams and the later turns use prior history:

```text
请记住验证码 XHL-260826，只回复“已记住”。
刚才的验证码是什么？只回复验证码。
把验证码中的连字符替换成下划线，只回复结果。
到目前为止我提出了几轮问题？只回复数字。
用不超过十个汉字结束本次测试。
/config
/exit
```

Expected: five assistant responses complete; turn 2/3 demonstrate history; `/config` says the Key is configured without revealing it; exit is clean.

- [ ] **Step 3: Verify real Ctrl+C cancellation and recovery**

Start a second PTY session, ask `请持续输出一篇至少两千字的 Java 21 介绍。`, interrupt with Ctrl+C after text begins, then send `只回复 OK` and `/exit`.

Expected: the first response is marked cancelled/incomplete, the process remains alive, the second response succeeds, and no stack trace or Key appears.

- [ ] **Step 4: Sanitize the captured transcript and create the GIF from real output**

Convert the `script` capture to printable text, remove terminal controls, machine paths, and the `/config` Key-source detail, then confirm no known Key literal remains. Use the actual transcript as the FFmpeg text source:

```bash
col -b < target/phase01-real-session.typescript \
  | perl -pe 's/\e\[[0-9;?]*[ -\/]*[@-~]//g; s#(/Users/|/home/)[^ ]+#<local-path>#g' \
  > target/phase01-demo.txt
test -z "$(rg -n 'DEEPSEEK_API_KEY=|Bearer |configured \((ENVIRONMENT|DOT_ENV)\)' target/phase01-demo.txt || true)"
mkdir -p docs/assets
ffmpeg -y -f lavfi -i color=c=0x111827:s=1280x720:d=20 \
  -vf "drawtext=fontfile=/System/Library/Fonts/Menlo.ttc:textfile=target/phase01-demo.txt:fontcolor=0xE5E7EB:fontsize=24:x=40:y=h-mod(t*60\,h+text_h),split[s0][s1];[s0]palettegen[p];[s1][p]paletteuse" \
  -r 12 docs/assets/xhlcli-phase-01-demo.gif
```

Open the GIF and verify that it is legible, animated, XhlCLI-branded, and contains no credential or private path.

- [ ] **Step 5: Finalize release metadata**

Change Maven version from `0.2.0-SNAPSHOT` to `0.2.0`; update CI JAR paths. Move CHANGELOG Phase 01 items into `## [0.2.0] - 2026-08-26`, mark Phase 01 delivered in ROADMAP/AGENTS/PRD, link the demo GIF in README, and retain the warning that Agent/tools begin later.

- [ ] **Step 6: Run the final local release gate**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw clean verify
java -jar target/xhlcli-0.2.0.jar --help
java -jar target/xhlcli-0.2.0.jar --version
javap -verbose -classpath target/classes com.xhlcli.cli.Main | rg 'major version: 65'
git diff --check
```

Repeat the boundary and secret scans from Task 6, explicitly excluding ignored `.env` content from command output while checking it remains untracked.

- [ ] **Step 7: Commit the verified Phase 01 release**

```bash
git add pom.xml .github README.md CHANGELOG.md ROADMAP.md AGENTS.md \
  docs/prd/phase-01-terminal-chat.md docs/assets/xhlcli-phase-01-demo.gif
git commit -m "docs: mark phase one as verified"
git status --short --ignored
```

Expected: only ignored local files remain.

- [ ] **Step 8: Push main and wait for the exact CI commit**

```bash
git push origin main
gh run list --workflow CI --branch main --limit 3 \
  --json databaseId,status,conclusion,headSha,url
xhlcli_run_id=$(gh run list --workflow CI --branch main \
  --commit "$(git rev-parse HEAD)" --limit 1 --json databaseId --jq '.[0].databaseId')
gh run watch "$xhlcli_run_id" --exit-status
```

Expected: Ubuntu, macOS, and Windows jobs all pass for the pushed HEAD. If any job fails, do not tag; diagnose, fix, rerun locally, push, and wait again.

- [ ] **Step 9: Tag only the green commit and verify the remote refs**

```bash
git tag -a v0.2.0 -m "XhlCLI Phase 01 terminal chat"
git push origin v0.2.0
git ls-remote origin refs/heads/main refs/tags/v0.2.0 refs/tags/v0.2.0^{}
```

Expected: annotated tag `v0.2.0` resolves to the same commit as remote `main`.

---

## Plan Self-Review Mapping

| Spec requirement | Plan coverage |
|---|---|
| Selective Paicli adoption | Tasks 1–3 and Global Constraints |
| DeepSeek OpenAI-compatible streaming | Task 3 |
| Secure precedence and `.env` | Task 1 |
| Multi-turn history and `/clear` | Task 4 |
| `/help`, `/config`, `/exit` | Task 5 |
| Ctrl+C and timeout cancellation | Tasks 3 and 5 |
| Error classification, retry,断流 | Tasks 3 and 4 |
| Debug diagnostics and redaction | Tasks 1, 3, and 4 |
| Token usage/unknown | Tasks 3 and 4 |
| No Agent/tools/reasoning | Global Constraints and Task 6 scans |
| Mock HTTP integration tests | Task 3 |
| Three-platform verification | Task 6 |
| Real five-turn and cancel gate | Task 7 |
| Sanitized demo GIF | Task 7 |
| Documentation, CI, release tag | Tasks 6 and 7 |
