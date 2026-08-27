# Phase 02 ReAct Agent 技术设计

> 日期：2026-08-27
> 状态：已完成方案评审，批准实施
> 对应需求：`docs/prd/phase-02-react-agent.md`

## 1. 目标与边界

Phase 02 在 Phase 01 的 DeepSeek 流式聊天之上增加一个可测试、可停止、可观察的单 Agent ReAct 循环：模型可以返回结构化 Tool Call，XhlCLI 按原始顺序执行演示工具，把结构化 Observation 与调用 ID 关联后回灌，再继续请求模型，直到模型返回不含工具调用的完整最终文本，或 Run 触发失败、取消、超时、迭代限制、重复无进展等终止条件。

本期只使用 `echo_text` 和 `current_time` 两个进程内演示工具验证协议。它们不读取或修改项目文件，不执行 Shell 或 Git，也不访问网络。

本期不实现：真实文件系统、代码搜索、写入、Shell、Git、Policy、HITL、审计、Plan、DAG、并行工具、Multi-Agent、Memory、RAG、MCP、持久 Run、崩溃恢复、图片消息及 reasoning 展示。模型动作只由结构化 `tool_calls` 决定，不解析 `Thought:` 或其他自然语言标记。

## 2. 参考实现采用策略

`../paicli` 是已授权、严格只读的参考仓库。参考工作树存在用户修改，所有采用依据固定到 Git 对象：

| 提交与文件 | 采用内容 | XhlCLI 主动差异 |
|---|---|---|
| `e2b8df4:agent/Agent.java` | 最小 ReAct 循环、assistant Tool Call 入历史、Observation 回灌、无调用即完成 | 拆分生命周期、事件和执行器；不直接输出终端 |
| `e2b8df4:llm/GLMClient.java` | OpenAI-compatible `Message`、`ToolCall`、`Tool` 和 `tool_call_id` 格式 | 该提交没有独立 `LlmClient`；XhlCLI 使用顶层不可变领域类型 |
| `e2b8df4:tool/ToolRegistry.java` | 注册、定义暴露、按名称执行的最小意图 | 重名失败、Schema 校验、结构化结果、无真实本地工具 |
| `f49d33c:llm/LlmClient.java` | Provider-neutral LLM 边界和 Tool Call 消息 | 融合现有取消、错误分类和流式接口；不迁移 reasoning |
| `a6fa3a8:agent/AgentBudget.java` 与测试 | 最大轮次和连续调用停滞检测 | 重复签名加入规范化 JSON 和相同 Observation |
| `b7ee842:agent/Agent.java` | 最终循环的消息顺序和统一工具执行入口 | 排除 Memory、RAG、Skill、LSP、图片、并行和巨型渲染逻辑 |
| `b7ee842:llm/AbstractOpenAiCompatibleClient.java` | 流式 Tool Call 按 index 累积碎片 | 保留 XhlCLI 的 `[DONE]` 完整性、脱敏、取消和错误映射 |
| `b7ee842:tool/ToolRegistry.java` | `ToolInvocation`/`ToolExecutionResult` 的职责分离 | 不迁移 1423 行 Registry、并行执行、MCP 或真实工具 |

不复制 Paicli 的 Git 历史、工作区修改、构建产物、配置、品牌、作者身份、最终大类或后期能力。包名统一为 `com.xhlcli`。来源采用范围和主动差异同步记录到 `docs/engineering/source-adoption-map.md`。

## 3. 架构与依赖方向

```text
cli/ChatLoop
    │
    ▼
agent/AgentRunner ────────────────► model/RunEventSink
    │                                      │
    └── agent/ReactAgent                   ▼
          ├── llm/LlmClient          render/PlainRunRenderer
          ├── tool/ToolExecutor
          │      └── ToolRegistry
          │             ├── EchoTool
          │             └── CurrentTimeTool
          ├── RunLifecycle
          ├── RepetitionGuard
          └── TimeoutScheduler
```

- `model` 保存跨模块不可变协议类型。
- `llm` 只负责 Provider 请求/响应、SSE Tool Call 拼接、取消和错误映射。
- `tool` 负责工具定义、注册、参数校验、执行隔离和结果预算。
- `agent` 驱动决策循环、消息回灌、限制和终态。
- `render` 只消费 `RunEvent`，不影响 Agent 决策。
- `cli` 只负责输入、命令、Ctrl+C 和依赖装配。

Agent 不依赖 `DeepSeekClient`、OkHttp、Jackson 的 Provider DTO、终端流或具体演示工具类。新增工具不修改 Agent，新增 Provider 不修改工具执行器。

## 4. 核心领域协议

### 4.1 消息与 Tool Call

`ToolCall` 是顶层 record：

```java
public record ToolCall(String id, String name, String argumentsJson) {}
```

`ChatMessage` 扩展为：

```java
public record ChatMessage(
        Role role,
        String content,
        List<ToolCall> toolCalls,
        String toolCallId) {
    enum Role { SYSTEM, USER, ASSISTANT, TOOL }
}
```

构造规则：

- system/user 文本必须非空白，不允许 Tool Call 或 `toolCallId`。
- assistant 可以是非空文本、非空 Tool Call 列表，或两者同时存在；二者都空是非法消息。
- tool 必须有非空 `toolCallId` 和非空 Observation 文本，不允许 Tool Call 列表。
- 所有列表在构造时防御性复制。
- 保留 `new ChatMessage(Role, content)` 兼容构造器和 `system/user/assistant/tool` 工厂。

`ChatResponse` 包含 `content`、不可变 `toolCalls` 和 `TokenUsage`。有 Tool Call 时允许文本为空；无 Tool Call 时最终文本必须非空。文本与 Tool Call 同时存在时，文本作为过程说明保存在 assistant 消息中，但 Run 继续执行工具，不视为完成。

### 4.2 工具定义

`ToolDefinition` 包含稳定 snake_case 名称、描述、JSON Schema 和元数据。元数据包括风险级别、只读、可取消、允许并行和资源键；Phase 02 只传递这些信息，不实现 Policy、审批或并行。

新工具元数据默认使用高风险、非只读、不可取消、不可并行的保守值。两个演示工具显式声明为低风险、只读、可取消、不可并行。

`Tool` 接口：

```java
public interface Tool {
    ToolDefinition definition();
    ToolOutput execute(JsonNode arguments, CancellationToken cancellationToken) throws Exception;
}
```

`ToolOutput` 包含面向用户的摘要、供模型判断的结构化 `JsonNode data` 和可选继续建议。工具不得返回裸异常作为协议结果。

### 4.3 Tool Result / Observation

`ToolResult` 至少包含：

- `callId`、`toolName`；
- `status`：`SUCCESS`、`UNKNOWN_TOOL`、`VALIDATION_ERROR`、`EXECUTION_ERROR`、`TIMEOUT`、`CANCELLED`；
- `summary`；
- 结构化 `data`；
- `elapsedMillis`；
- `truncated`、`originalChars`、`continueHint`。

回灌给模型的 tool message content 是稳定 JSON：

```json
{
  "status": "success",
  "summary": "Echoed 5 characters.",
  "data": {"text": "hello"},
  "truncated": false,
  "original_chars": 16,
  "continue_hint": ""
}
```

Tool Result 总预算默认 8,000 字符。超过预算时保留合法 JSON envelope，以 preview 替换完整 data，记录原始字符数并提供缩小调用范围的建议，不能截断成非法 JSON。

## 5. Tool Registry 与参数校验

`ToolRegistry` 使用 `LinkedHashMap` 保持注册顺序。构造或注册时校验：

- 名称非空且匹配 `[a-z][a-z0-9_]*`；
- 描述非空；
- Schema 根节点是 `type: object`；
- 重复名称立即抛出 `IllegalArgumentException`，不覆盖已有工具。

Registry 只负责注册、查找和按顺序导出定义，不解析参数、不捕获执行异常。

`DefaultToolExecutor` 固定执行：

```text
取消检查
→ 调用 ID / 名称检查
→ Registry 查找
→ arguments JSON 解析
→ JSON object / required / property type / additionalProperties 校验
→ 再次取消检查
→ Tool.execute
→ 结构化结果预算
```

Phase 02 的 Schema 校验支持 object、string、integer、number、boolean、array、object、required 和 `additionalProperties: false`，足以覆盖演示工具及 Phase 03 的基础本地工具。组合 Schema、引用和条件 Schema 留到动态协议真正需要时扩展。

未知工具、非法 JSON、错误类型、缺少字段和多余字段都返回失败 `ToolResult`；工具抛出的异常转为 `EXECUTION_ERROR`，不会穿透到 Agent 或 CLI。

## 6. 演示工具

### 6.1 `echo_text`

参数：

```json
{
  "type": "object",
  "properties": {"text": {"type": "string"}},
  "required": ["text"],
  "additionalProperties": false
}
```

返回 `{ "text": <原文本> }`。超长内容由统一 Tool Result 预算处理。

### 6.2 `current_time`

参数是禁止额外字段的空 object。工具构造器注入 `java.time.Clock`，返回 UTC ISO-8601 时间；测试使用 fixed Clock，不读取不可控当前时间。

## 7. LLM Tool Call 协议

`LlmClient` 的主接口改为：

```java
ChatResponse stream(
        List<ChatMessage> messages,
        List<ToolDefinition> tools,
        StreamListener listener,
        CancellationToken cancellationToken) throws LlmException;
```

原三参数重载作为 default 方法传入空工具列表，保证无工具场景和现有测试平滑迁移。

OpenAI-compatible 请求规则：

- tools 非空时序列化为 `type: function`、name、description 和 parameters。
- assistant Tool Call 消息包含 `tool_calls`；文本为空时 content 写 JSON null。
- tool Observation 消息包含 `role: tool`、`tool_call_id` 和 JSON content。
- reasoning 字段继续忽略，不进入历史、事件或动作判断。

SSE 解析规则：

- 继续只接受 `data:`，必须收到 `[DONE]`。
- `delta.content` 立即发送可见文本增量。
- `delta.tool_calls` 按 `index` 聚合，ID、name 和 arguments 均允许跨 chunk 拼接。
- 有完整 Tool Call 时允许 content 为空。
- content 与 Tool Call 都为空时抛出新的 `EMPTY_RESPONSE`；Agent 最多重试一次，连续两次空响应后失败。
- 缺失 ID、名称或 arguments 的调用交由 Agent 协议验证，不能像 Paicli 最终版一样静默丢弃。

## 8. Run 生命周期与终态保护

状态：

```text
CREATED → THINKING → CALLING_TOOL → OBSERVING → THINKING
        → COMPLETED | FAILED | CANCELED | LIMIT_REACHED
```

`RunLifecycle` 是单一状态所有者：

- 非终态按允许迁移表变更；非法迁移立即失败。
- 终态使用 compare-and-set 语义，只能设置一次。
- 每次模型请求和 Tool Start 前必须调用 `requireActive()`。
- 终态事件由设置终态的同一线程发出，事件后不再发出任何事件。

终态语义：

- `COMPLETED`：模型返回不含 Tool Call 的非空完整文本。
- `FAILED`：LLM 不可恢复错误、协议损坏、空响应耗尽、整体超时或内部不变量损坏。
- `CANCELED`：用户取消。
- `LIMIT_REACHED`：最大迭代或重复无进展。

整体超时不在调度线程直接发布事件。调度器只用 first-wins 原子停止原因标记 `TIMEOUT` 并取消当前操作 token；Agent 线程收到返回或取消异常后统一进入 `FAILED/TIMEOUT`。用户取消和超时竞争时先发生者决定终态。

## 9. ReAct 执行流程

每次 `run(input, events, userCancellation)`：

1. 拒绝空白输入，创建 runId、事件序号、工作历史和内部操作 token。
2. 注册用户取消桥接并调度整体超时。
3. 发布 `RunStarted`，把用户消息加入工作历史。
4. 检查停止原因、最大迭代和生命周期，进入 `THINKING`。
5. 发布 `ModelRequestStarted`，请求 LLM；文本 delta 转为 `TextDelta`。
6. 发布 `ModelRequestCompleted` 并记录 TokenUsage。
7. 响应既无文本也无 Tool Call 时，不修改历史并重试；连续两次后失败。
8. 有 Tool Call 时先验证本批所有 ID 非空、在整个 Run 唯一、名称非空；违反则协议失败。
9. 将 assistant 文本及完整 Tool Call 批次加入工作历史。
10. 按原始顺序逐个检查停止原因、发布 `ToolStarted`、调用统一 `ToolExecutor`、发布 `ToolCompleted`、把 JSON Observation 作为 tool message 回灌。
11. 完成批次后发布 `IterationCompleted`，计算本轮“规范化调用 + 相同结果”指纹。
12. 连续三轮指纹一致则进入 `LIMIT_REACHED`；否则进入下一轮。
13. 无 Tool Call 且文本非空时加入 assistant 最终消息，原子提交工作历史，进入 `COMPLETED`。
14. `finally` 取消超时任务并注销取消回调。

一次响应包含多个 Tool Call 时本期严格顺序执行。取消或超时发生后，不启动剩余调用，也不再请求模型。

## 10. 重复与无进展检测

每轮完成工具执行后生成指纹：

```text
ordered list of (
  tool name,
  recursively key-sorted canonical arguments JSON,
  result status,
  recursively key-sorted canonical result JSON
)
```

调用 ID、耗时和事件时间不参与指纹。只有连续三轮完全相同才停止；中间任何名称、参数或结果变化都会清空连续计数。这样避免 JSON 字段顺序导致漏报，也不会把参数相同但 Observation 已变化的有效轮次判为无进展。

## 11. RunEvent 协议

所有事件实现同一个 sealed `RunEvent`，共享：

```java
record Metadata(String runId, long sequence, Instant timestamp, int iteration) {}
```

事件集合：

- `RunStarted`
- `ModelRequestStarted`
- `TextDelta`
- `ModelRequestCompleted`
- `ToolStarted`
- `ToolCompleted`
- `IterationCompleted`
- `RunCompleted`
- `RunFailed`
- `RunCancelled`
- `RunLimitReached`

sequence 从 1 严格递增。事件只携带安全参数摘要和结果摘要，不携带完整 Provider body、Authorization、API Key、完整 Observation 或 reasoning。Renderer 默认折叠 Observation；完整结构只存在于 Agent 工作历史和测试对象中。

## 12. 配置

在 `ChatConfig` 中加入不可变 `AgentSettings`：

| 配置 | 环境变量 | CLI | 默认 | 有效范围 |
|---|---|---|---|---|
| 最大迭代 | `XHLCLI_AGENT_MAX_ITERATIONS` | `--max-iterations` | 10 | 1–100 |
| 整体超时 | `XHLCLI_AGENT_TIMEOUT_SECONDS` | `--agent-timeout` | 600 秒 | 1–3600 秒 |

重复窗口固定 3，空响应上限固定 2，Observation 预算固定 8,000 字符；本期不增加无明确用户价值的额外配置项。`/config` 显示最大迭代和整体超时。

## 13. 会话、取消和终端集成

`ReactAgent` 保持进程内跨轮已提交历史。每个 Run 使用历史副本工作，只有正常 `COMPLETED` 才提交全部 user/assistant/tool 消息；失败、取消和限制不会把不完整协议写入下一轮历史。`/clear` 恢复唯一 system message。

现有 `ChatEvent`/`ChatEventSink` 被 `RunEvent`/`RunEventSink` 完整替换，不维护双事件系统。Plain Renderer：

- Model 请求开始显示 `Thinking...`；
- 文本 delta 保持 Phase 01 流式输出；
- Tool Start 显示名称和脱敏、截断后的参数摘要；
- Tool Completed 显示成功/失败、耗时和结果摘要；
- 四种终态给出明确状态；
- Plain 模式不输出 ANSI。

Ctrl+C 继续使用现有 `CancellationToken`。响应或工具进行中时取消当前 Run 并返回输入循环；空闲输入行为保持 Phase 01 契约。

## 14. 错误处理

- 未知工具、非法 JSON、参数校验失败、工具异常：形成失败 Observation，允许模型纠正。
- 重复 Tool Call ID、空 ID、空工具名：Run 协议失败，不执行该批工具。
- LLM `EMPTY_RESPONSE`：不追加 assistant 消息，最多重试一次；连续两次后失败。
- LLM `CANCELLED`：依据 first-wins 停止原因映射为用户取消或整体超时。
- 单个工具失败不让 CLI 崩溃；结果回灌后模型可以继续。
- Renderer 或 EventSink 抛出运行时异常视为内部失败，终止当前 Run，CLI 外层仍可处理下一次输入。
- 已经流出的过程文本不作为最终完成证据。

## 15. 测试策略

自动测试不使用真实 Key、网络、用户主目录、当前时间或固定 sleep：

- 模型协议：消息不变量、Tool Call request JSON、分片 SSE、tool result 回灌、文本加调用、空响应。
- 工具协议：重名、稳定顺序、JSON 解析、required/type/additionalProperties、未知工具、异常、取消、预算。
- Agent 场景：三步工具链、失败纠正、多调用顺序和 ID 关联、无工具退化。
- 停止条件：最大迭代、整体超时、用户取消、重复无进展、空响应耗尽、重复 ID。
- 性质检查：终态事件恰好一个，终态后无模型或工具事件，sequence 严格递增。
- Renderer：工具状态、四种终态、无 ANSI、API Key 脱敏。
- Phase 01 回归：原 50 项测试保持通过；已有行为变化的测试先修改为 RED，再实现。

阶段自动验收：

```bash
./mvnw clean verify
java -jar target/xhlcli-0.3.0-SNAPSHOT.jar --help
java -jar target/xhlcli-0.3.0-SNAPSHOT.jar --version
javap -verbose -classpath target/classes com.xhlcli.cli.Main | rg 'major version: 65'
git status --short
```

本期按用户决定不要求手工 `current_time → echo_text` 演示、失败纠正录屏或迭代限制录屏。自动测试、构建、CLI 冒烟、文档一致性和敏感信息扫描全部通过即可提交并推送；不创建版本标签。

## 16. 文件落点

预计新增：

```text
src/main/java/com/xhlcli/agent/
src/main/java/com/xhlcli/tool/
src/main/java/com/xhlcli/tool/demo/
src/main/java/com/xhlcli/model/ToolCall.java
src/main/java/com/xhlcli/model/ToolDefinition.java
src/main/java/com/xhlcli/model/ToolMetadata.java
src/main/java/com/xhlcli/model/ToolOutput.java
src/main/java/com/xhlcli/model/ToolResult.java
src/main/java/com/xhlcli/model/ToolResultStatus.java
src/main/java/com/xhlcli/model/RunEvent.java
src/main/java/com/xhlcli/model/RunEventSink.java
src/main/java/com/xhlcli/model/RunStatus.java
src/main/java/com/xhlcli/model/RunResult.java
src/main/java/com/xhlcli/config/AgentSettings.java
src/main/java/com/xhlcli/render/PlainRunRenderer.java
```

预计修改或替换：`ChatMessage`、`ChatResponse`、`LlmClient`、SSE/HTTP 客户端、配置加载、Bootstrap、ChatLoop、Phase 01 app/renderer 事件类型和对应测试。

不创建未来阶段空包或占位接口。

## 17. 完成定义

Phase 02 完成必须同时满足：

1. 所有 Phase 02 PRD 验收行为有确定性自动测试。
2. 现有 Phase 01 回归和新增测试全部通过。
3. 统一 RunEvent 足以按序重建用户可见时间线。
4. 终态后不能观察到新的模型请求或工具启动。
5. 仓库不包含 Paicli 品牌、包名、真实凭据、真实本地工具或后期能力。
6. README、AGENTS、PRD、TECH_DESIGN、ROADMAP、CHANGELOG 和采用地图与实际代码一致。
7. 在真实当前工作树执行干净验证并保存命令结果。
8. 创建真实、可解释的 Phase 02 提交并推送到 `origin/main`；不创建版本标签。
