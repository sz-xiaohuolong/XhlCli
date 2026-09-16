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
