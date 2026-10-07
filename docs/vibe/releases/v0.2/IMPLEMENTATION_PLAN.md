# Release v0.2 Implementation Plan

<!-- Source: plans/2026-08-26-phase-01-terminal-chat.md -->

# Phase 01 Terminal Chat Implementation Plan

> 2026-08-26 范围修订：按用户最终决定，Phase 01 仅以 macOS Java 21 CI 作为发布门禁，不承诺 Windows/Linux 兼容性。本修订优先于下文早期计划中的三平台矩阵要求。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a Java 21 XhlCLI that securely configures DeepSeek and supports cancellable, streaming, multi-turn terminal chat without Agent or local-tool capabilities.

**Architecture:** Selectively migrate Paicli's OpenAI-compatible SSE and DeepSeek adapter into small `config`, `llm`, `app`, `render`, and `cli` units. Keep conversation state and user-visible events provider-neutral, keep HTTP details inside `llm`, and use JLine only for portable line input and Ctrl+C handling.

**Tech Stack:** Java 21, Maven Wrapper, OkHttp 5.5.0, Jackson Databind 2.22.2, JLine 4.3.1, JUnit 5.10.2, MockWebServer 5.5.0, Maven Shade Plugin 3.6.2.

**Spec:** `docs/specs/2026-08-26-phase-01-terminal-chat-design.md`

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


<!-- Source: plans/2026-08-27-phase-02-react-agent.md -->

# Phase 02 ReAct Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a Java 21 ReAct Agent that consumes structured model Tool Calls, executes controlled demo tools, returns structured Observations, and stops safely under every Phase 02 terminal condition.

**Architecture:** Extend the Phase 01 provider-neutral message and streaming contracts, then place a stateful `ReactAgent` between the CLI and a shared `ToolExecutor`. Keep lifecycle, repetition detection, timeouts and RunEvent sequencing in small injectable units so Fake Model tests can prove terminal invariants without network, real time or sleep.

**Tech Stack:** Java 21, Maven Wrapper, Jackson 2.22.2, OkHttp/MockWebServer 5.5.0, JLine 4.3.1, JUnit 5.10.2.

**Spec:** `docs/specs/2026-08-27-phase-02-react-agent-design.md`

## Global Constraints

- Work directly in the existing `main` checkout as explicitly authorized; do not create a worktree.
- Keep `maven.compiler.release=21`; do not use preview APIs.
- Read Paicli only with `git -C ../paicli show <commit>:<path>`; never modify, switch, reset or clean its working tree.
- Use only fixed reference objects `e2b8df4`, `f49d33c`, `a6fa3a8`, and `b7ee842` and the exact files listed by the spec.
- Use `com.xhlcli`, XhlCLI, `.xhlcli`, and `~/.xhlcli`; do not migrate reference branding or Git history.
- Do not add real filesystem, search, write, Shell, Git, Policy, HITL, Plan, parallel, Multi-Agent, Memory, RAG, MCP, image or reasoning capabilities.
- Model action comes only from structured Tool Calls; never parse `Thought:` text.
- All behavior changes use RED → GREEN → refactor. Automated tests cannot use real API keys, network, user home, uncontrolled current time or fixed sleep.
- Core logic cannot write stdout/stderr; all user-visible run state uses `RunEvent`.
- A terminal Run cannot issue a later model request, start a tool, or publish another event.
- The user waived manual demos and recordings for this phase. Full automated verification is still mandatory.
- Do not create a version tag. Push committed, verified changes to `origin/main` only after the completion audit passes.

---

### Task 1: Structured Message, Tool, Result, and Event Contracts

**Files:**

- Create: `src/main/java/com/xhlcli/model/ToolCall.java`
- Create: `src/main/java/com/xhlcli/model/ToolMetadata.java`
- Create: `src/main/java/com/xhlcli/model/ToolDefinition.java`
- Create: `src/main/java/com/xhlcli/model/ToolOutput.java`
- Create: `src/main/java/com/xhlcli/model/ToolResultStatus.java`
- Create: `src/main/java/com/xhlcli/model/ToolResult.java`
- Create: `src/main/java/com/xhlcli/model/RunStatus.java`
- Create: `src/main/java/com/xhlcli/model/RunResult.java`
- Create: `src/main/java/com/xhlcli/model/RunEvent.java`
- Create: `src/main/java/com/xhlcli/model/RunEventSink.java`
- Modify: `src/main/java/com/xhlcli/model/ChatMessage.java`
- Modify: `src/main/java/com/xhlcli/model/ChatResponse.java`
- Test: `src/test/java/com/xhlcli/model/ChatMessageTest.java`
- Test: `src/test/java/com/xhlcli/model/ToolProtocolTest.java`
- Test: `src/test/java/com/xhlcli/model/RunEventTest.java`

**Interfaces:**

- Consumes: Jackson `JsonNode`, existing `TokenUsage`.
- Produces: immutable provider-neutral messages, Tool Call/definition/result contracts, Run terminal result and sequenced event types used by all later tasks.

- [ ] **Step 1: Extend `ChatMessageTest` with wished-for tool protocol behavior**

Add tests that construct and reject exact shapes:

```java
ToolCall call = new ToolCall("call_1", "echo_text", "{\"text\":\"hello\"}");
ChatMessage assistant = ChatMessage.assistant("checking", List.of(call));
ChatMessage observation = ChatMessage.tool("call_1", "{\"status\":\"success\"}");

assertEquals(List.of(call), assistant.toolCalls());
assertEquals(ChatMessage.Role.TOOL, observation.role());
assertEquals("call_1", observation.toolCallId());
assertThrows(IllegalArgumentException.class,
        () -> ChatMessage.assistant("", List.of()));
assertThrows(IllegalArgumentException.class,
        () -> ChatMessage.tool("", "result"));
assertThrows(UnsupportedOperationException.class,
        () -> assistant.toolCalls().add(call));
```

Extend response assertions:

```java
ChatResponse response = new ChatResponse("", List.of(call), TokenUsage.unknown());
assertTrue(response.hasToolCalls());
assertThrows(IllegalArgumentException.class,
        () -> new ChatResponse("", List.of(), TokenUsage.unknown()));
```

- [ ] **Step 2: Add failing `ToolProtocolTest` and `RunEventTest`**

Assert `ToolMetadata.conservative()` is high risk; definitions defensively copy schema/metadata; `ToolResult.observationJson(mapper)` has stable fields; event metadata rejects blank run IDs and non-positive sequence; terminal event records expose the expected `RunStatus`.

- [ ] **Step 3: Run model tests and confirm RED**

```bash
./mvnw -Dtest=ChatMessageTest,ToolProtocolTest,RunEventTest test
```

Expected: test compilation fails because the new model contracts and `ChatMessage` factories do not exist.

- [ ] **Step 4: Implement the minimal immutable contracts**

Use these exact public shapes:

```java
public record ToolCall(String id, String name, String argumentsJson) {}

public record ToolDefinition(
        String name, String description, JsonNode parameters, ToolMetadata metadata) {}

public record ToolOutput(String summary, JsonNode data, String continueHint) {}

public enum ToolResultStatus {
    SUCCESS, UNKNOWN_TOOL, VALIDATION_ERROR, EXECUTION_ERROR, TIMEOUT, CANCELLED
}

public record RunResult(
        String runId, RunStatus status, String finalAnswer,
        String reason, int iterations, TokenUsage usage) {}
```

`RunEvent` is sealed with nested records `RunStarted`, `ModelRequestStarted`, `TextDelta`, `ModelRequestCompleted`, `ToolStarted`, `ToolCompleted`, `IterationCompleted`, `RunCompleted`, `RunFailed`, `RunCancelled`, and `RunLimitReached`. Every record contains the same validated `Metadata` value.

`ToolResult.observationJson(ObjectMapper)` serializes lower-case status plus `summary`, `data`, `elapsed_ms`, `truncated`, `original_chars`, and `continue_hint` in that order.

- [ ] **Step 5: Run model tests and the existing Phase 01 model/session tests**

```bash
./mvnw -Dtest=ChatMessageTest,ToolProtocolTest,RunEventTest,ChatSessionTest test
```

Expected: all selected tests pass. Adjust `ChatSession` only if compilation requires the compatibility constructors; do not add Agent behavior yet.

- [ ] **Step 6: Commit the contract layer**

```bash
git add src/main/java/com/xhlcli/model src/test/java/com/xhlcli/model
git commit -m "feat: define react run and tool protocols"
```

---

### Task 2: Tool Registry, Validation, Execution, and Demo Tools

**Files:**

- Create: `src/main/java/com/xhlcli/tool/Tool.java`
- Create: `src/main/java/com/xhlcli/tool/ToolRegistry.java`
- Create: `src/main/java/com/xhlcli/tool/ToolSchemaValidator.java`
- Create: `src/main/java/com/xhlcli/tool/ToolResultBudget.java`
- Create: `src/main/java/com/xhlcli/tool/ToolExecutor.java`
- Create: `src/main/java/com/xhlcli/tool/DefaultToolExecutor.java`
- Create: `src/main/java/com/xhlcli/tool/demo/EchoTool.java`
- Create: `src/main/java/com/xhlcli/tool/demo/CurrentTimeTool.java`
- Test: `src/test/java/com/xhlcli/tool/ToolRegistryTest.java`
- Test: `src/test/java/com/xhlcli/tool/ToolSchemaValidatorTest.java`
- Test: `src/test/java/com/xhlcli/tool/DefaultToolExecutorTest.java`
- Test: `src/test/java/com/xhlcli/tool/DemoToolsTest.java`

**Interfaces:**

- Consumes: Task 1 model contracts and existing `CancellationToken`.
- Produces: `ToolRegistry.definitions()`, `ToolExecutor.execute(ToolCall, CancellationToken)`, and two controlled tools for `ReactAgent`.

- [ ] **Step 1: Write failing Registry and Schema tests**

Registry tests must prove insertion order, lookup, immutable definitions, snake_case rejection and duplicate failure:

```java
ToolRegistry registry = new ToolRegistry(List.of(firstTool, secondTool));
assertEquals(List.of("first_tool", "second_tool"),
        registry.definitions().stream().map(ToolDefinition::name).toList());
assertThrows(IllegalArgumentException.class,
        () -> new ToolRegistry(List.of(firstTool, firstTool)));
```

Schema tests must use real Jackson nodes to assert valid objects and messages for malformed JSON, non-object roots, missing required fields, wrong string/integer/number/boolean/array/object types, and unknown fields when `additionalProperties` is false.

- [ ] **Step 2: Run Registry/Schema tests and confirm RED**

```bash
./mvnw -Dtest=ToolRegistryTest,ToolSchemaValidatorTest test
```

Expected: compilation failure for missing `com.xhlcli.tool` types.

- [ ] **Step 3: Implement Registry and the supported JSON Schema subset**

`ToolSchemaValidator.parseAndValidate(String, JsonNode)` returns a small result containing either the validated object node or a safe error string. It recognizes JSON Schema property types `string`, `integer`, `number`, `boolean`, `array`, and `object`, applies `required`, and rejects unknown keys only when `additionalProperties` is explicitly false.

Do not add a schema-validator dependency and do not accept scalar/array argument roots.

- [ ] **Step 4: Run Registry/Schema tests and confirm GREEN**

```bash
./mvnw -Dtest=ToolRegistryTest,ToolSchemaValidatorTest test
```

- [ ] **Step 5: Write failing executor, budget and demo-tool tests**

Cover:

```java
ToolResult unknown = executor.execute(
        new ToolCall("call_1", "missing_tool", "{}"), token);
assertEquals(ToolResultStatus.UNKNOWN_TOOL, unknown.status());

ToolResult invalid = executor.execute(
        new ToolCall("call_2", "echo_text", "{not-json}"), token);
assertEquals(ToolResultStatus.VALIDATION_ERROR, invalid.status());

token.cancel();
ToolResult cancelled = executor.execute(
        new ToolCall("call_3", "echo_text", "{\"text\":\"x\"}"), token);
assertEquals(ToolResultStatus.CANCELLED, cancelled.status());
```

Add a throwing test tool and assert `EXECUTION_ERROR`. Configure a tiny budget and assert the resulting Observation stays valid JSON, has `truncated=true`, records the original size and contains a continue hint.

Use `Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC)` and assert `current_time` returns exactly that instant. Assert `echo_text` preserves Unicode content.

- [ ] **Step 6: Run executor/demo tests and confirm RED**

```bash
./mvnw -Dtest=DefaultToolExecutorTest,DemoToolsTest test
```

Expected: compilation failure for the missing executor and demo tools.

- [ ] **Step 7: Implement executor, budget and demo tools**

Use:

```java
@FunctionalInterface
public interface ToolExecutor {
    ToolResult execute(ToolCall call, CancellationToken cancellationToken);
}
```

`DefaultToolExecutor` owns `ToolRegistry`, `ToolSchemaValidator`, `ToolResultBudget`, `ObjectMapper`, and `LongSupplier nanoTime`. It catches every tool exception, measures elapsed milliseconds through the injected monotonic supplier, and never throws a business failure.

- [ ] **Step 8: Run all tool tests and a quick regression**

```bash
./mvnw -Dtest='com.xhlcli.tool.*Test' test
./mvnw test
```

- [ ] **Step 9: Commit the tool layer**

```bash
git add src/main/java/com/xhlcli/tool src/test/java/com/xhlcli/tool
git commit -m "feat: add validated demo tool execution"
```

---

### Task 3: OpenAI-Compatible Tool Call Streaming

**Files:**

- Modify: `src/main/java/com/xhlcli/llm/LlmClient.java`
- Modify: `src/main/java/com/xhlcli/llm/LlmErrorType.java`
- Modify: `src/main/java/com/xhlcli/llm/AbstractOpenAiCompatibleClient.java`
- Modify: `src/main/java/com/xhlcli/llm/OpenAiSseParser.java`
- Modify: `src/test/java/com/xhlcli/llm/DeepSeekClientTest.java`
- Modify: `src/test/java/com/xhlcli/app/ChatSessionTest.java`

**Interfaces:**

- Consumes: `ChatMessage`, `ChatResponse`, `ToolDefinition`, existing HTTP cancellation/error contracts.
- Produces: tool-aware `LlmClient.stream(messages, tools, listener, token)` and correct DeepSeek/OpenAI-compatible wire behavior.

- [ ] **Step 1: Add failing request serialization tests**

Extend `DeepSeekClientTest` with a MockWebServer response and request assertions for:

```json
"tools": [{
  "type": "function",
  "function": {
    "name": "echo_text",
    "description": "Echo text",
    "parameters": {"type":"object"}
  }
}]
```

Also send an assistant Tool Call followed by a tool Observation and assert `tool_calls`, JSON-null assistant content, `role: tool`, `tool_call_id`, and Observation content are preserved in request order.

- [ ] **Step 2: Add failing fragmented SSE tests**

Enqueue chunks where one Tool Call is split across multiple deltas:

```text
data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_","function":{"name":"echo_","arguments":"{\\\"text\\\":"}}]}}]}
data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"1","function":{"name":"text","arguments":"\\\"hello\\\"}"}}]}}]}
data: [DONE]
```

Assert the response contains exactly `ToolCall("call_1", "echo_text", "{\"text\":\"hello\"}")` and accepts empty content. Add text-plus-tool and multiple-index fixtures. Add a `[DONE]` stream with neither text nor calls and assert `LlmErrorType.EMPTY_RESPONSE`.

- [ ] **Step 3: Run `DeepSeekClientTest` and confirm RED**

```bash
./mvnw -Dtest=DeepSeekClientTest test
```

Expected: compilation failures for the tool-aware signature and response shape, followed by behavioral failures until serialization and accumulation exist.

- [ ] **Step 4: Extend `LlmClient`, request serialization and SSE parsing**

Add the four-argument method and keep this compatibility overload:

```java
default ChatResponse stream(
        List<ChatMessage> messages,
        StreamListener listener,
        CancellationToken cancellationToken) throws LlmException {
    return stream(messages, List.of(), listener, cancellationToken);
}
```

Add `EMPTY_RESPONSE` to `LlmErrorType`. Accumulate Tool Call fields by index without silently dropping incomplete calls. Preserve existing retry, timeout, cancellation, redaction, HTTP/1.1 and `[DONE]` behavior.

- [ ] **Step 5: Update Fake clients for the new abstract method**

Change Phase 01 test clients to implement the four-argument method or rely on a compatible interface default in a direction that does not recurse. Do not loosen existing Phase 01 assertions.

- [ ] **Step 6: Run LLM and Phase 01 session tests**

```bash
./mvnw -Dtest=DeepSeekClientTest,ChatSessionTest,ChatLoopTest test
```

Expected: all selected tests pass, including existing SSE/error/cancellation cases.

- [ ] **Step 7: Commit the LLM protocol extension**

```bash
git add src/main/java/com/xhlcli/llm src/test/java/com/xhlcli/llm src/test/java/com/xhlcli/app/ChatSessionTest.java
git commit -m "feat: stream structured model tool calls"
```

---

### Task 4: Run Lifecycle, Repetition Detection, and Timeout Control

**Files:**

- Create: `src/main/java/com/xhlcli/agent/RunLimits.java`
- Create: `src/main/java/com/xhlcli/agent/RunLifecycle.java`
- Create: `src/main/java/com/xhlcli/agent/RepetitionGuard.java`
- Create: `src/main/java/com/xhlcli/agent/TimeoutScheduler.java`
- Create: `src/main/java/com/xhlcli/agent/ScheduledTimeoutScheduler.java`
- Test: `src/test/java/com/xhlcli/agent/RunLifecycleTest.java`
- Test: `src/test/java/com/xhlcli/agent/RepetitionGuardTest.java`
- Test: `src/test/java/com/xhlcli/agent/TimeoutSchedulerTest.java`

**Interfaces:**

- Consumes: Run status and structured Tool Results.
- Produces: the independently tested safety controls used by `ReactAgent`.

- [ ] **Step 1: Write failing lifecycle and limit tests**

Assert exact transitions, terminal idempotence and validation:

```java
RunLifecycle lifecycle = new RunLifecycle();
lifecycle.transitionTo(RunStatus.THINKING);
lifecycle.transitionTo(RunStatus.CALLING_TOOL);
lifecycle.transitionTo(RunStatus.OBSERVING);
assertTrue(lifecycle.finish(RunStatus.COMPLETED));
assertFalse(lifecycle.finish(RunStatus.FAILED));
assertThrows(IllegalStateException.class, lifecycle::requireActive);
```

`RunLimits` rejects zero/negative values, iterations above 100 and timeouts above one hour.

- [ ] **Step 2: Write failing repetition tests**

Build results whose argument objects use different field order and assert they count as identical. Assert call ID, elapsed time and event time do not affect the signature. Assert changed data resets the consecutive count and only the third identical completed iteration reports repetition.

- [ ] **Step 3: Run lifecycle/repetition tests and confirm RED**

```bash
./mvnw -Dtest=RunLifecycleTest,RepetitionGuardTest test
```

- [ ] **Step 4: Implement lifecycle, limits and canonical repetition fingerprinting**

Use recursively sorted object fields, preserve array order, and serialize with the injected `ObjectMapper`. `RunLifecycle.finish` accepts only terminal statuses and returns false if already terminal.

- [ ] **Step 5: Add and run a scheduler test without sleep**

Test `ScheduledTimeoutScheduler` with a `CountDownLatch` and bounded `await`, then close it and assert new scheduling is rejected. Keep the interface small:

```java
public interface TimeoutScheduler extends AutoCloseable {
    Registration schedule(Duration delay, Runnable action);
    interface Registration extends AutoCloseable { void close(); }
}
```

- [ ] **Step 6: Run all agent safety-unit tests**

```bash
./mvnw -Dtest=RunLifecycleTest,RepetitionGuardTest,TimeoutSchedulerTest test
```

- [ ] **Step 7: Commit the safety controls**

```bash
git add src/main/java/com/xhlcli/agent src/test/java/com/xhlcli/agent
git commit -m "feat: add react lifecycle safety controls"
```

---

### Task 5: ReAct Agent Loop and Terminal Invariants

**Files:**

- Create: `src/main/java/com/xhlcli/agent/AgentRunner.java`
- Create: `src/main/java/com/xhlcli/agent/ReactAgent.java`
- Test: `src/test/java/com/xhlcli/agent/ReactAgentTest.java`

**Interfaces:**

- Consumes: `LlmClient`, `ToolExecutor`, Tool definitions, Run safety units, `RunEventSink`, and `CancellationToken`.
- Produces: stateful `AgentRunner.run`, `clearHistory`, and immutable committed history for CLI integration.

- [ ] **Step 1: Write a failing three-step success scenario**

Create a queue-backed Fake Model that captures each request and returns:

1. assistant Tool Call `current_time`;
2. assistant Tool Call `echo_text` using the first Observation;
3. final text `done`.

Assert three model requests, two tool executions, tool messages immediately follow their matching assistant calls, IDs match, result order is stable, final status is `COMPLETED`, and the committed history contains the complete protocol.

- [ ] **Step 2: Write failing recovery and batch-order scenarios**

Cover unknown tool, malformed arguments and a throwing tool followed by a corrected call and final answer. Add one response containing two calls and assert the second does not start until the first completes. Include text plus Tool Calls and assert the text is not treated as completion.

- [ ] **Step 3: Run success/recovery tests and confirm RED**

```bash
./mvnw -Dtest=ReactAgentTest test
```

Expected: compilation failure because `AgentRunner` and `ReactAgent` do not exist.

- [ ] **Step 4: Implement the minimal success/recovery loop**

Use this boundary:

```java
public interface AgentRunner {
    RunResult run(String input, RunEventSink events, CancellationToken cancellationToken);
    void clearHistory();
    List<ChatMessage> history();
}
```

Keep per-run working history separate and commit it only on `COMPLETED`. Publish all events through a run-local sequencer using injected `Clock` and `Supplier<String>`.

- [ ] **Step 5: Run success/recovery tests and confirm GREEN**

```bash
./mvnw -Dtest=ReactAgentTest test
```

- [ ] **Step 6: Add failing limit, cancellation, timeout and protocol tests**

Add deterministic scenarios for:

- max iteration reached before another model request;
- three identical canonical calls and identical results;
- user token canceled while Fake Model waits on a latch;
- manually fired Fake TimeoutScheduler while Fake Tool waits on cancellation;
- duplicate call ID within one batch and reused ID in a later iteration;
- two `EMPTY_RESPONSE` failures;
- an empty Tool Registry returning normal text;
- EventSink recording exactly one terminal event, strictly increasing sequence, and no later event/model/tool start.

- [ ] **Step 7: Run the new safety scenarios and confirm RED**

```bash
./mvnw -Dtest=ReactAgentTest test
```

Expected: focused assertions fail until each stop condition and terminal guard exists.

- [ ] **Step 8: Implement stop reasons and terminal event mapping**

Use first-wins stop reasons `USER`, `TIMEOUT`, and `NONE`; link them to a separate per-run operation token. Map timeout to `FAILED` with reason code `TIMEOUT`, user cancellation to `CANCELED`, and iteration/repetition to `LIMIT_REACHED` with distinct reason codes.

Check stop/lifecycle immediately before every LLM request and every Tool Start. Do not publish a terminal event from a timeout callback.

- [ ] **Step 9: Run all Agent tests and full regression**

```bash
./mvnw -Dtest='com.xhlcli.agent.*Test' test
./mvnw test
```

- [ ] **Step 10: Commit the ReAct loop**

```bash
git add src/main/java/com/xhlcli/agent src/test/java/com/xhlcli/agent
git commit -m "feat: run bounded observable react loops"
```

---

### Task 6: Configuration, CLI, Cancellation, and Plain Run Rendering

**Files:**

- Create: `src/main/java/com/xhlcli/config/AgentSettings.java`
- Create: `src/main/java/com/xhlcli/render/PlainRunRenderer.java`
- Modify: `src/main/java/com/xhlcli/config/ChatConfig.java`
- Modify: `src/main/java/com/xhlcli/config/ChatConfigLoader.java`
- Modify: `src/main/java/com/xhlcli/config/ConfigKey.java`
- Modify: `src/main/java/com/xhlcli/cli/ChatBootstrap.java`
- Modify: `src/main/java/com/xhlcli/cli/ChatLoop.java`
- Modify: `src/main/java/com/xhlcli/cli/CliApplication.java`
- Modify: `src/main/java/com/xhlcli/cli/JLineTerminalSession.java`
- Delete after replacement: `src/main/java/com/xhlcli/app/ChatEvent.java`
- Delete after replacement: `src/main/java/com/xhlcli/app/ChatEventSink.java`
- Delete after replacement: `src/main/java/com/xhlcli/app/ChatSession.java`
- Delete after replacement: `src/main/java/com/xhlcli/render/PlainChatRenderer.java`
- Modify: `pom.xml`
- Modify: `.env.example`
- Test: `src/test/java/com/xhlcli/config/ChatConfigLoaderTest.java`
- Test: `src/test/java/com/xhlcli/render/PlainRunRendererTest.java`
- Modify: `src/test/java/com/xhlcli/cli/ChatLoopTest.java`
- Modify: `src/test/java/com/xhlcli/cli/CliApplicationTest.java`
- Delete after replacement: `src/test/java/com/xhlcli/app/ChatSessionTest.java`
- Delete after replacement: `src/test/java/com/xhlcli/render/PlainChatRendererTest.java`

**Interfaces:**

- Consumes: completed `AgentRunner`, demo Registry/Executor, DeepSeek client, existing terminal input and redaction.
- Produces: default Phase 02 CLI behavior, Ctrl+C cancellation and user-visible RunEvent rendering.

- [ ] **Step 1: Write failing configuration tests**

Assert defaults 10/600, CLI precedence for `--max-iterations` and `--agent-timeout`, environment keys, user JSON fields `agentMaxIterations`/`agentTimeoutSeconds`, range rejection, and `/config` source-safe output.

- [ ] **Step 2: Write failing Renderer tests**

Feed a fixed event timeline and assert:

- `Thinking...` and streamed assistant prefix appear once;
- Tool Start shows name and redacted/truncated parameters;
- Tool Completed shows status, elapsed time and safe summary;
- completed/failed/canceled/limit statuses are distinct;
- API Key never appears;
- output contains no `\u001B[` ANSI sequence.

- [ ] **Step 3: Rewrite CLI tests against a Fake `AgentRunner` and confirm RED**

`ChatLoopTest` must continue proving five turns, `/clear`, provider failure recovery, EOF/idle interrupt and active cancellation. Replace `ChatSession` with the injected `AgentRunner`, and assert cancellation reaches the active Run token.

Run:

```bash
./mvnw -Dtest=ChatConfigLoaderTest,PlainRunRendererTest,ChatLoopTest,CliApplicationTest test
```

Expected: compilation or assertion failures for the missing Phase 02 configuration and event-driven CLI.

- [ ] **Step 4: Implement `AgentSettings` and configuration merging**

Add CLI options and sources with the existing precedence. Keep the existing `ChatConfig` constructor as a compatibility overload that supplies default `AgentSettings`, then migrate production/test call sites to the canonical constructor.

- [ ] **Step 5: Implement Plain rendering and CLI assembly**

`ChatBootstrap` creates:

```text
DeepSeekClient
ToolRegistry(EchoTool, CurrentTimeTool)
DefaultToolExecutor
ScheduledTimeoutScheduler
ReactAgent
PlainRunRenderer
ChatLoop
```

The Bootstrap owns and closes the timeout scheduler. `ChatLoop` invokes `AgentRunner.run`, `/clear` calls `clearHistory`, and `cancelActiveResponse` cancels the same user token bridged by `ReactAgent`.

Remove the replaced Phase 01 ChatEvent/ChatSession/PlainChatRenderer classes only after all production references are gone.

- [ ] **Step 6: Bump the development version and update help/config examples**

Set Maven version to `0.3.0-SNAPSHOT`. Add non-secret `.env.example` entries for the two Agent settings. Show both CLI options in `--help`; never add an API Key option.

- [ ] **Step 7: Run CLI/config/render and full tests**

```bash
./mvnw -Dtest=ChatConfigLoaderTest,PlainRunRendererTest,ChatLoopTest,CliApplicationTest test
./mvnw test
```

- [ ] **Step 8: Commit the Phase 02 application integration**

```bash
git add pom.xml .env.example src/main/java/com/xhlcli src/test/java/com/xhlcli
git commit -m "feat: integrate react agent terminal sessions"
```

---

### Task 7: Documentation, Verification, Completion Audit, and Push

**Files:**

- Modify: `AGENTS.md`
- Modify: `README.md`
- Modify: `TECH_DESIGN.md`
- Modify: `ROADMAP.md`
- Modify: `CHANGELOG.md`
- Modify: `docs/prd/phase-02-react-agent.md`
- Modify: `docs/engineering/source-adoption-map.md`
- Keep: `docs/specs/2026-08-27-phase-02-react-agent-design.md`
- Keep: `docs/plans/2026-08-27-phase-02-react-agent.md`

**Interfaces:**

- Consumes: verified runtime behavior and exact test counts.
- Produces: repository truth, reproducible verification evidence, final commits and pushed `origin/main`.

- [ ] **Step 1: Update documentation to match actual delivered behavior**

Mark Phase 02 delivered only after implementation tests pass. Document:

- ReAct loop and demo-only tool boundary;
- structured Tool Call/Observation protocol;
- max iteration, 600-second overall timeout, cancellation, empty response and repetition behavior;
- unified RunEvent and Plain output;
- exact new automated test count;
- explicit absence of filesystem/Shell/Git/Plan/parallel/Multi-Agent/MCP/RAG;
- fixed Paicli commits/files/tests and XhlCLI active differences;
- no manual demo/recording requirement by explicit user decision;
- no Phase 02 version tag.

- [ ] **Step 2: Run focused tests**

```bash
./mvnw -Dtest='com.xhlcli.model.*Test,com.xhlcli.tool.*Test,com.xhlcli.llm.DeepSeekClientTest,com.xhlcli.agent.*Test,com.xhlcli.render.*Test,com.xhlcli.cli.*Test,com.xhlcli.config.*Test' test
```

Expected: zero failures, errors and skipped tests.

- [ ] **Step 3: Run the clean phase gate**

```bash
./mvnw clean verify
java -jar target/xhlcli-0.3.0-SNAPSHOT.jar --help
java -jar target/xhlcli-0.3.0-SNAPSHOT.jar --version
javap -verbose -classpath target/classes com.xhlcli.cli.Main | rg 'major version: 65'
```

Expected: build success, help lists Agent settings without secrets, version is `0.3.0-SNAPSHOT`, and bytecode is Java 21.

- [ ] **Step 4: Run repository consistency and boundary scans**

```bash
rg -n 'TODO|TBD|待补充|占位符' \
  --glob '!target/**' \
  --glob '!docs/plans/**' .

rg -n 'com\.paicli|~/.paicli|\.paicli/' \
  src pom.xml README.md CHANGELOG.md ROADMAP.md

rg -n 'read_file|write_file|execute_command|git_' \
  src/main/java/com/xhlcli/tool src/main/java/com/xhlcli/agent

git ls-files | rg '(^|/)(\.env|target/|.*\.log$)' || true
git diff --check
git status --short
```

Expected: no placeholders in delivered docs/code, no reference brand in product sources, no real local tools, no tracked secrets/build output, no whitespace errors, and only intended Phase 02 changes before the final documentation commit.

- [ ] **Step 5: Perform requirement-by-requirement completion audit**

Map every FR-02-01 through FR-02-08, every PRD acceptance criterion, every non-goal, terminal invariant and user instruction to an exact test, source path or command output. Treat missing evidence as incomplete and add a failing test before fixing any discovered gap.

- [ ] **Step 6: Commit documentation and any evidence-driven fixes**

```bash
git add AGENTS.md README.md TECH_DESIGN.md ROADMAP.md CHANGELOG.md \
  docs/prd/phase-02-react-agent.md docs/engineering/source-adoption-map.md \
  docs/specs/2026-08-27-phase-02-react-agent-design.md \
  docs/plans/2026-08-27-phase-02-react-agent.md
git commit -m "docs: mark phase two react agent verified"
```

- [ ] **Step 7: Re-run final verification on committed HEAD**

```bash
./mvnw clean verify
git status --short --branch
git log --oneline --decorate -10
```

Expected: clean tree, all tests green, HEAD contains only explainable Phase 02 commits, and no tag was created.

- [ ] **Step 8: Push the verified commits**

```bash
git push origin main
git status --short --branch
```

Expected: push succeeds and branch reports `main...origin/main` with no ahead/behind count.


<!-- Source: plans/2026-08-29-phase-03-local-tools.md -->

# Phase 03 本地工具集 (Local Tools) 实施计划与记录

> **对应设计：** `docs/specs/2026-08-29-phase-03-local-tools-design.md`
> **对应需求：** `docs/prd/phase-03-local-tools.md`

## 1. 任务拆分与执行状态

- [x] **Task 1: 路径安全与基础文件读写工具**
  - 创建 `WorkspacePathResolver` 并增加路径穿越与越界单测 `WorkspacePathResolverTest`
  - 创建 `ListDirTool` 及单测 `ListDirToolTest`
  - 创建 `ReadFileTool` 及全量/分页行号读取单测 `ReadFileToolTest`
  - 创建 `WriteFileTool` 及 5MB 大小限制、目录自建单测 `WriteFileToolTest`

- [x] **Task 2: 代码精确补丁与 Git 差异工具**
  - 创建 `ApplyPatchTool`（唯一匹配替换）及 0 匹配/多匹配报错单测 `ApplyPatchToolTest`
  - 创建 `GitDiffTool` 及空差异/无仓库容错单测 `GitDiffToolTest`

- [x] **Task 3: 受控 Shell 命令执行工具**
  - 创建 `ExecuteCommandTool`（超时/截断/取消中断）及单测 `ExecuteCommandToolTest`

- [x] **Task 4: 文件查找与代码搜索体系**
  - 创建不可变搜索领域 Record (`CodeSearchRequest`, `CodeSearchResult`, `GrepMatch`, `ContextLine`, `CodeSearchEngine`)
  - 创建 `GlobFilesTool` 及单测 `GlobFilesToolTest`
  - 创建 `JavaCodeSearchEngine` 并迁移二进制跳过、上下文抓取等逻辑，创建 `JavaCodeSearchEngineTest`
  - 创建 `RipgrepCodeSearchEngine`（流式 JSON 解析与降级机制）
  - 创建 `GrepCodeTool`（预算控制与 suggested_reads 推荐）及 `GrepCodeToolTest`
  - 创建 `CodeSearchGoldenSetTest` 与 `src/test/resources/code-search/golden-set.json` 真实评测集

- [x] **Task 5: 真实 Agent 循环集成与 Prompt 更新**
  - 在 `ChatBootstrap` 中为真实会话装配 8 个本地工具与路径解析器
  - 更新 System Prompt 指引模型在代码任务中正确组合搜索、读取、修改与测试工具
  - 编写 `LocalToolsCodingLoopTest` 模拟真实 ReAct 编程工作流验证完整闭环

- [x] **Task 6: 工程文档与状态同步**
  - 编写 Phase 03 技术设计与实施记录文档
  - 更新 `CHANGELOG.md`、`AGENTS.md` 和 `source-adoption-map.md`


<!-- Source: plans/2026-08-30-phase-04-safety-and-approval.md -->

# Phase 04 安全策略与人工审批 (Safety and Approval) 实施计划与记录

> **对应设计：** `docs/specs/2026-08-30-phase-04-safety-and-approval-design.md`  
> **对应需求：** `docs/prd/phase-04-safety-and-approval.md`  

## 1. 任务拆分与执行状态

- [x] **Task 1: 系统硬策略与审计 (`com.xhlcli.policy`)**
  - 创建 `PolicyException` 异常
  - 创建 `PathGuard` 及单测 `PathGuardTest`（路径穿越、软链逃逸与越界拦截）
  - 创建 `CommandGuard` 及单测 `CommandGuardTest`（高危命令 Fast-fail 拦截）
  - 创建 `AuditLog` 及单测 `AuditLogTest`（JSONL 每日审计与敏感词脱敏）

- [x] **Task 2: 人工审批与风险策略 (`com.xhlcli.hitl`)**
  - 创建 `RiskLevel` 风险枚举
  - 创建 `ApprovalPolicy` 及单测 `ApprovalPolicyTest`（只读/中危/高危分类与未注册默认高危）
  - 创建 `ApprovalRequest` 及单测 `ApprovalRequestTest`（基于 CJK 宽度的终端边框格式化）
  - 创建 `ApprovalResult` 决策模型
  - 创建 `HitlHandler` 交互契约
  - 创建 `TerminalHitlHandler` 及单测 `TerminalHitlHandlerTest`（处理 y/a/n/s/m 交互、会话放行与安全拒绝）

- [x] **Task 3: DefaultToolExecutor 安全编排**
  - 整合硬策略检查、HITL 审批判断与审计日志记录
  - 编写 `DefaultToolExecutorSafetyTest` 验证拦截、放行与修改参数重新校验

- [x] **Task 4: 启动装配与命令联动**
  - `ChatBootstrap` 装配 `PathGuard`、`AuditLog` 与 `TerminalHitlHandler`
  - `ChatLoop` /clear 命令联动清除会话临时授权
  - 编写 `AgentSafetyIntegrationTest` 验证 ReAct 循环安全闭环

- [x] **Task 5: 文档与全量验证**
  - 编写 Phase 04 设计与计划记录文档
  - 更新 `CHANGELOG.md`、`AGENTS.md` 与 `source-adoption-map.md`
  - 全量 187 项自动化测试全部通过
