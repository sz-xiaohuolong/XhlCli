# Release v0.2 Product Specification

<!-- Source: specs/2026-08-26-phase-01-terminal-chat-design.md -->

# Phase 01 终端对话技术设计

> 日期：2026-08-26  
> 状态：已完成方案评审，等待实施批准  
> 对应需求：`docs/prd/phase-01-terminal-chat.md`

## 1. 目标与边界

Phase 01 交付一个只具备文本对话能力的 XhlCLI：用户配置 DeepSeek 后，可以在终端中进行流式多轮对话、查看非敏感配置、清空会话和安全退出。

本期不引入 ReAct、Tool Call、本地文件或命令工具、多 Provider 切换、会话持久化、Markdown TUI 和模型私有思维展示。模型输出不会获得任何本地工具权限。

## 2. 参考实现采用策略

选择性采用已授权 Paicli 实现，不复制最终源码树：

| 参考提交或文件 | 采用内容 | 主动差异 |
|---|---|---|
| `e2b8df4` `cli/Main` | 交互循环和退出/清理命令的基本流程 | 使用 XhlCLI 品牌、斜杠命令和可测试边界 |
| `530bb9c` 流式客户端 | SSE 按行读取、增量回调和 Token 汇总 | 补齐断流检测、错误分类和取消 |
| `f49d33c` `LlmClient`、`AbstractOpenAiCompatibleClient`、`DeepSeekClient` | OpenAI-compatible 消息序列化、DeepSeek Bearer 鉴权和模型适配 | 删除工具调用、多 Provider 和 reasoning 展示 |
| Paicli 当前 `DeepSeekClient` | HTTP/1.1 兼容处理、当前端点和模型默认值 | 超时改为实例配置，避免隐藏全局状态 |

迁移后的包名统一为 `com.xhlcli`，用户目录统一为 `~/.xhlcli`。不迁移参考仓库工作区现有未提交改动、Git 历史、运行数据和品牌资源。

## 3. 架构与依赖方向

```text
cli/Main + CliApplication
        │
        ├── config/ChatConfigLoader ──> ChatConfig
        │
        └── app/ChatLoop ──> ChatSession ──> llm/LlmClient
                  │                 │             │
                  └── render/PlainChatRenderer    └── DeepSeekClient
                                                    └── OkHttp + Jackson
```

- `config` 只负责加载、合并、校验和描述配置来源。
- `llm` 只负责统一消息、HTTP/SSE 协议、取消和 Provider 错误映射。
- `app` 只负责会话历史、命令语义和事件顺序，不直接访问环境变量或 HTTP。
- `cli` 负责参数、终端输入和生命周期装配。
- `render` 把聊天事件转换为 Plain 文本，核心逻辑不直接写 stdout/stderr。

## 4. 核心模型与接口

### 4.1 消息与响应

`ChatMessage` 是不可变值对象，角色只允许 `system`、`user`、`assistant`，内容不得为空白。Phase 01 不定义 tool role。

新会话和 `/clear` 后都只保留一条固定 system message：`You are XhlCLI, a helpful coding assistant. In Phase 01 you have no tools and must not claim to inspect or modify local files.`

`ChatResponse` 包含完整 assistant 文本和 `TokenUsage`。TokenUsage 分别表示输入、输出和是否已知；Provider 未返回 usage 时界面显示 `unknown`，不用零冒充未知。

### 4.2 流式客户端

`LlmClient.stream(messages, listener, cancellationToken)` 同步完成一次请求：

- `listener` 只接收用户可见文本增量；
- `cancellationToken` 注册 OkHttp `Call.cancel()` 回调；
- 成功时返回完整 `ChatResponse`；
- 失败时抛出带稳定分类的 `LlmException`；
- 不暴露 Provider JSON、Authorization 或模型私有 reasoning。

`LlmErrorType` 至少包含：`MISSING_CONFIGURATION`、`AUTHENTICATION`、`RATE_LIMIT`、`NETWORK`、`SERVER`、`INVALID_RESPONSE`、`TIMEOUT`、`CANCELLED` 和 `INVALID_CONFIGURATION`。

### 4.3 聊天事件

应用层产生 `ChatEvent`：等待开始、文本增量、响应完成、失败和取消。Renderer 消费事件并串行输出。Phase 02 可以继续消费相同的文本事件，并在其上增加模型/工具事件。

## 5. DeepSeek HTTP 与 SSE

- 默认 Base URL：`https://api.deepseek.com`。
- 实际地址：规范化 Base URL 后追加 `/chat/completions`；已经包含该路径时不重复追加。
- 默认模型：`deepseek-v4-flash`。
- 请求使用 `Authorization: Bearer <key>`、`Content-Type: application/json`、`stream: true`。
- 消息按当前顺序发送，不包含工具定义和 reasoning 历史。
- SSE 只处理 `data:` 字段；空行和未知字段安全忽略。
- 每个 `choices[0].delta.content` 立即产生文本增量。
- 最后一个 chunk 的 usage 用于 TokenUsage；缺失时标记未知。
- 收到 `data: [DONE]` 才视为完整结束；连接提前关闭标记为不完整响应。
- Provider 返回 reasoning 字段时解析器忽略，不输出也不保存。

DeepSeek 使用 OpenAI-compatible API；官方当前提供 `deepseek-v4-flash`，因此该名称作为 2026-08-26 的阶段默认值。用户可通过配置覆盖模型和 Base URL。

## 6. 配置与秘密管理

配置优先级从高到低为：

1. 命令行中的非敏感参数；
2. 进程环境变量；
3. 项目根目录 `.env`；
4. `~/.xhlcli/config.json`；
5. 内置默认值。

支持字段：

| 字段 | 环境变量 | 默认值 |
|---|---|---|
| API Key | `DEEPSEEK_API_KEY` | 无，缺失时禁止请求 |
| 模型 | `DEEPSEEK_MODEL` | `deepseek-v4-flash` |
| Base URL | `DEEPSEEK_BASE_URL` | `https://api.deepseek.com` |
| 连接超时 | `XHLCLI_CONNECT_TIMEOUT_SECONDS` | 30 秒 |
| 读取超时 | `XHLCLI_READ_TIMEOUT_SECONDS` | 300 秒 |
| 整体超时 | `XHLCLI_REQUEST_TIMEOUT_SECONDS` | 600 秒 |
| 日志级别 | `XHLCLI_LOG_LEVEL` | `WARN` |

命令行提供 `--model`、`--base-url`、`--connect-timeout`、`--read-timeout`、`--request-timeout` 和 `--log-level`。API Key 不提供命令行参数，避免出现在 shell 历史和进程列表中。

`.env.example` 只包含占位值和获取方式；真实 `.env` 已被 Git 忽略。`/config` 显示 Provider、模型、Base URL、超时和来源，只把 Key 显示为 `configured (source)` 或 `missing`。任何异常、日志和测试失败信息都经过已知 Key、Bearer Token 和常见凭据键脱敏。

`~/.xhlcli/config.json` 只保存模型、Base URL、超时和日志级别等非敏感字段，不接受或写回 API Key。API Key 的实际优先级固定为进程环境变量 > 项目 `.env` > 缺失，避免秘密长期落入普通 JSON 配置。

Base URL 在创建客户端前校验 URI、scheme 和 host。生产默认使用 HTTPS；为 MockWebServer 和用户显式的本地兼容服务允许 HTTP。

## 7. 终端交互与命令

无参数启动进入聊天模式；`--help` 和 `--version` 保持 Phase 00 契约。启动聊天前展示 Provider 与模型，不展示 Key。

基础命令：

- `/help`：显示本期命令；
- `/config`：显示脱敏配置和来源；
- `/clear`：清空 user/assistant 历史并恢复初始 system message；
- `/exit`：关闭当前客户端和终端后退出。

空白输入不发送请求。普通用户输入和成功的 assistant 回答成对加入历史；请求失败、超时、取消或断流时不提交本轮历史，避免产生不完整消息序列。

应用使用轻量 JLine Terminal 处理 Ctrl+C：

- 请求进行中：取消对应 OkHttp Call，显示取消提示并回到输入；
- 空闲输入中：第一次 Ctrl+C 清空当前输入并继续；
- `/exit` 和 EOF：走同一关闭路径。

本期只使用 Plain 输出，不引入全屏 TUI、状态栏、Markdown 渲染和补全。

## 8. 错误、重试与部分输出

- 缺少 Key：请求前失败并提示在 `.env` 设置 `DEEPSEEK_API_KEY`。
- 401：鉴权失败，提示检查或重新签发 Key。
- 429：限流，提示稍后重试。
- 500/503 等 5xx：服务端错误，提示稍后重试。
- 连接/DNS 失败：网络错误，提示检查网络和 Base URL。
- 读取或整体超时：超时错误，提示调整网络或超时配置。
- JSON/SSE 不合法、空响应或缺少 `[DONE]`：响应格式错误。
- 用户取消：取消类型，不显示异常堆栈。

只在尚未产生任何文本增量时，对网络错误、429 和 5xx 自动重试一次；固定等待 250 毫秒。401、参数错误、格式错误、超时、取消和已经产生部分文本后的失败不重试。

响应中途失败时，已输出文本保留在终端，并追加“响应未完整结束”标记；该轮不进入会话历史。

## 9. 测试策略

自动测试不读取真实用户主目录、不依赖网络或真实 API Key：

- 配置单元测试：优先级、`.env` 解析、默认值、非法 URI/超时和来源；
- 消息测试：合法角色、空内容拒绝和历史顺序；
- SSE 集成测试：正常多 chunk、usage、未知事件、空内容、非法 JSON 和断流；
- HTTP 错误测试：401、429、5xx、网络失败和一次重试边界；
- 取消/超时测试：延迟 MockWebServer、Call 取消和可继续下一轮；
- ChatSession 测试：多轮顺序、`/clear`、失败不提交历史；
- CLI 测试：命令、空输入、脱敏输出、`--help`、`--version` 和未知参数。
- 发布 CI：macOS 使用 Java 21 运行测试与 JAR 冒烟；JLine 取消逻辑通过可调用的信号处理边界做确定性测试。本期不承诺 Windows/Linux 兼容性。

阶段门禁：

1. `./mvnw clean verify` 全部通过；
2. 可执行 fat JAR 的帮助、版本、缺 Key 和 Mock/真实聊天路径通过；
3. Java 字节码版本为 65；
4. 日志和仓库敏感信息扫描通过；
5. 用户在本地 `.env` 填写 Key 后，真实 DeepSeek 完成五轮短对话；
6. 真实请求期间 Ctrl+C 能取消并继续输入；
7. 使用本地 Mock 流录制不包含真实 Key、用户路径和网络依赖的终端演示 GIF；
8. GitHub Actions 的 macOS Java 21 验证通过后才创建 `v0.2.0` 标签；本期不以 Windows/Linux 兼容性作为发布门禁。

真实验证不打印、回显、提交或上传 Key。测试完成后 `.env` 继续只保留在用户本机。

## 10. 文件落点

预计新增或修改：

```text
pom.xml
.env.example
src/main/java/com/xhlcli/
  app/
  cli/
  config/
  llm/
  model/
  render/
src/test/java/com/xhlcli/
  app/
  cli/
  config/
  llm/
README.md
ROADMAP.md
CHANGELOG.md
AGENTS.md
docs/prd/phase-01-terminal-chat.md
docs/plans/2026-08-26-phase-01-terminal-chat.md
docs/assets/xhlcli-phase-01-demo.gif
```

只在实际实现需要时创建文件，不建立 Phase 02+ 空包或占位接口。

## 11. 发布与完成定义

Phase 01 完成时版本提升为 `0.2.0`，README 明确“已支持终端文本对话，但不具备 Agent 或本地工具权限”。更新 CHANGELOG、ROADMAP、AGENTS 和 Phase 01 PRD 状态。

离线测试通过后暂停，由用户填写 `.env`。真实五轮对话、取消验证、敏感信息检查、本地最终构建和远程 CI 全部通过后，推送 `main` 并创建 `v0.2.0` 标签。


<!-- Source: specs/2026-08-27-phase-02-react-agent-design.md -->

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
| `e2b8df4:src/main/java/com/paicli/agent/Agent.java` | 最小 ReAct 循环、assistant Tool Call 入历史、Observation 回灌、无调用即完成 | 拆分生命周期、事件和执行器；不直接输出终端 |
| `e2b8df4:src/main/java/com/paicli/llm/GLMClient.java` | OpenAI-compatible `Message`、`ToolCall`、`Tool` 和 `tool_call_id` 格式 | 该提交没有独立 `LlmClient`；XhlCLI 使用顶层不可变领域类型 |
| `e2b8df4:src/main/java/com/paicli/tool/ToolRegistry.java` | 注册、定义暴露、按名称执行的最小意图 | 重名失败、Schema 校验、结构化结果、无真实本地工具 |
| `f49d33c:src/main/java/com/paicli/llm/LlmClient.java` | Provider-neutral LLM 边界和 Tool Call 消息 | 融合现有取消、错误分类和流式接口；不迁移 reasoning |
| `a6fa3a8:src/main/java/com/paicli/agent/AgentBudget.java`、`a6fa3a8:src/test/java/com/paicli/agent/AgentBudgetTest.java` | 最大轮次和连续调用停滞检测 | 重复签名加入规范化 JSON 和相同 Observation |
| `b7ee842:src/main/java/com/paicli/agent/Agent.java` | 最终循环的消息顺序和统一工具执行入口 | 排除 Memory、RAG、Skill、LSP、图片、并行和巨型渲染逻辑 |
| `b7ee842:src/main/java/com/paicli/llm/AbstractOpenAiCompatibleClient.java` | 流式 Tool Call 按 index 累积碎片 | 保留 XhlCLI 的 `[DONE]` 完整性、脱敏、取消和错误映射 |
| `b7ee842:src/main/java/com/paicli/tool/ToolRegistry.java` | `ToolInvocation`/`ToolExecutionResult` 的职责分离 | 不迁移 1423 行 Registry、并行执行、MCP 或真实工具 |

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


<!-- Source: specs/2026-08-29-phase-03-local-tools-design.md -->

# Phase 03 本地工具集 (Local Tools) 技术设计

> 日期：2026-08-29
> 状态：已完成实施与全量自动化测试验证
> 对应需求：`docs/prd/phase-03-local-tools.md`

## 1. 目标与边界

Phase 03 在 Phase 02 ReAct Agent 的通用工具执行与结构化协议之上，为 XhlCLI 提供第一套真正的本地开发工具集（Local Tools），使模型能够探索工作区、阅读代码、修改文件并执行受控命令：

1. **工作区安全边界** (`WorkspacePathResolver`)：强制所有文件与目录路径限制在当前项目根目录内，阻断 `..` 路径穿越、绝对路径逃逸与非工作区访问。
2. **只读文件探索**：
   - `list_dir`：列出指定目录下的文件与子目录（带 `[D] `/`[F] ` 前缀，自动过滤 `.git`、`target`、`node_modules`、`.xhlcli` 等构建与隐藏目录）。
   - `read_file`：支持全文件读取或 1-indexed 按行 `offset`/`limit` 分页读取（默认 200 行，上限 2000 行），输出带行号和截断提示。
   - `glob_files`：支持按照 glob 表达式递归匹配项目文件名或相对路径，自动忽略构建与版本控制目录。
3. **代码搜索能力**：
   - `JavaCodeSearchEngine`：纯 Java 跨平台降级引擎，支持大小写敏感/不敏感、正则、行上下文、`head_limit` 和 2MB 大小/二进制文件跳过。
   - `RipgrepCodeSearchEngine`：优先调用本地 `rg --json` 快速搜索，超时或不可用时无缝降级到 Java 引擎。
   - `grep_code`：统一搜索工具，包含字符预算截断控制与精准的 `suggested_reads` 行号推荐。
4. **受控写入与补丁**：
   - `write_file`：受控全文件写入（单文件上限 5MB），自动创建缺失父目录。
   - `apply_patch`：精确单处替换（必须在文件中唯一匹配，0 处或多处匹配时安全报错拒绝）。
5. **版本控制与命令执行**：
   - `git_diff`：在项目工作区执行 `git diff`，无暂存修改时返回友好提示。
   - `execute_command`：在项目工作区执行短时 Shell 命令（默认 60 秒，上限 300 秒超时，输出字符上限 8000 截断），接入 `CancellationToken` 支持随时中断。

## 2. 架构与依赖关系

```text
com.xhlcli.tool.local
├── WorkspacePathResolver (工作区路径安全解析)
├── ListDirTool (list_dir)
├── ReadFileTool (read_file)
├── WriteFileTool (write_file)
├── ApplyPatchTool (apply_patch)
├── GitDiffTool (git_diff)
├── ExecuteCommandTool (execute_command)
├── GlobFilesTool (glob_files)
└── search
    ├── CodeSearchEngine (接口)
    ├── CodeSearchRequest (记录)
    ├── CodeSearchResult (记录)
    ├── GrepMatch (记录)
    ├── ContextLine (记录)
    ├── JavaCodeSearchEngine (纯 Java 搜索实现)
    ├── RipgrepCodeSearchEngine (Ripgrep 包装与降级)
    └── GrepCodeTool (grep_code)
```

## 3. 参考实现采用策略

| 参考提交/文件 | 采用内容 | XhlCLI 演进与设计 |
|---|---|---|
| `e2b8df4:src/main/java/com/paicli/tool/ToolRegistry.java` | 文件读写、执行命令、glob 与 grep 的最小意图 | 彻底拆分巨型单体类，每个工具作为独立 `Tool` 实现，支持依赖注入 |
| `72a7e90:src/main/java/com/paicli/tool/JavaCodeSearchEngine.java` | 递归文件遍历、二进制过滤、行上下文与预算限制 | 统一不可变 Record 协议，严格路径安全约束 |
| `c69be83:src/main/java/com/paicli/tool/RipgrepCodeSearchEngine.java` | `rg --json` 事件流式解析与降级机制 | 保持纯净进程通信，支持系统属性禁用与 8 秒超时保护 |
| `c69be83:src/test/resources/code-search/golden-set.json` | 搜索与读取联动 Golden Set 评测集 | 构建针对 XhlCLI 自身架构的 Golden Set 自动化验证测试 |

## 4. 验证结果

- 所有 8 个本地工具及 2 个搜索引擎均具备完整单元测试。
- `CodeSearchGoldenSetTest` 评测集验证端到端代码定位与 `suggested_reads` 读取联动。
- `LocalToolsCodingLoopTest` 验证 `ReactAgent` 驱动下的多步写代码、读代码、打补丁与命令测试循环。
- 全项目 155 项自动化测试 100% 通过。


<!-- Source: specs/2026-08-30-phase-04-safety-and-approval-design.md -->

# Phase 04 安全策略与人工审批 (Safety and Approval) 技术设计

> 日期：2026-08-30  
> 状态：已完成实施与全量自动化测试验证  
> 对应需求：`docs/prd/phase-04-safety-and-approval.md`  

## 1. 目标与设计原则

Phase 04 建立了独立于 Agent 决策的安全与人工审批防御层，确保所有工具调用在执行前均经过硬策略拦截、风险识别、人工审批与脱敏审计：

1. **系统硬策略高于用户批准 (Hard Policy)**：
   - 当模型请求越界路径（如 `/etc/passwd`、`../secret`、逃逸软链接）或高危破坏性命令（如 `sudo`、`rm -rf /`、`mkfs`、`fork bomb`、`curl|sh` 等）时，系统直接在最前端快速拒绝并记录审计，**绝不弹出审批框**，用户亦无法通过批准强制执行。
2. **风险分级与精准打断 (HITL)**：
   - **只读操作**（`read_file`、`list_dir`、`glob_files`、`grep_code`、`git_diff`、`echo_text`、`current_time`）：自动放行并审计，不打断用户。
   - **中高危操作**（`write_file`、`apply_patch`、`execute_command` 及未注册的新工具）：弹出结构化终端审批框，展示工具名、风险等级、执行参数与说明。
   - **决策选项**：
     - `[y/Enter]` 批准单次执行；
     - `[a]` 批准本次会话后续所有同类工具调用（临时授权，`/clear` 或退出时销毁）；
     - `[n]` 拒绝执行并可附带原因；
     - `[s]` 跳过当前步骤；
     - `[m]` 修改参数（修改后重新进行 Schema 校验与硬策略检查）。
3. **脱敏审计 (AuditLog)**：
   - 每日在 `~/.xhlcli/audit/audit-YYYY-MM-DD.jsonl` 中记录 JSONL 格式审计条目；
   - 自动截断超长字段并掩码敏感信息（Bearer Token、API Key、Password、Secret 等）；
   - 审计写入失败采用 fail-safe 降级打印警告，不影响开发主流程。

---

## 2. 架构分层

```text
模型提出 ToolCall
  │
  ▼
DefaultToolExecutor
  ├── 1. ToolSchemaValidator (参数 Schema 校验)
  ├── 2. PathGuard / CommandGuard (系统硬策略检查)
  ├── 3. ApprovalPolicy + HitlHandler (风险分级与终端 HITL 审批)
  ├── 4. Tool.execute (工具真正执行)
  ├── 5. AuditLog (脱敏审计日志记录)
  └── 6. ToolResultBudget (结果预算裁剪)
```

---

## 3. 参考实现采用策略

| 参考提交/文件 | 采用内容 | XhlCLI 演进与设计 |
|---|---|---|
| `75e6642:src/main/java/com/paicli/policy/PathGuard.java` | 路径围栏、符号链接解析、向上查找父级目录以验证新建文件软链 | 提取为标准组件，支持 Path 与 String 入参，规范化跨平台真实根路径 |
| `75e6642:src/main/java/com/paicli/policy/CommandGuard.java` | 命令 Fast-fail 正则黑名单 | 支持组合标志与多参数匹配（如 `rm -r -f /`），提供 `check` 与 `validateSafe` |
| `75e6642:src/main/java/com/paicli/policy/AuditLog.java` | 每日 JSONL 审计、敏感凭据正则脱敏 | 统一落盘至 `~/.xhlcli/audit`，集成不可变 `AuditEntry` 与多级审计者标记 |
| `f90d9f5:src/main/java/com/paicli/hitl/*` | `ApprovalPolicy`、`ApprovalRequest`、`ApprovalResult`、`TerminalHitlHandler` | 基于 CJK/Emoji 显示宽度精确对齐终端边框，与 `ChatLoop` /clear 联动清理临时放行 |

---

## 4. 验证结果

- 覆盖 `PathGuardTest`、`CommandGuardTest`、`AuditLogTest`、`ApprovalPolicyTest`、`ApprovalRequestTest`、`TerminalHitlHandlerTest`、`DefaultToolExecutorSafetyTest`、`AgentSafetyIntegrationTest` 等完整测试集。
- 全项目 187 项自动化测试 100% 通过。
