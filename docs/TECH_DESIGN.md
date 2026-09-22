# XhlCLI 技术设计

> 文档状态：已确认，可用于分期实现
> 设计版本：v1.0
> Java 基线：21
> 更新日期：2026-09-21
> 产品需求：[`PRD.md`](PRD.md)

> 实现状态（2026-09-22）：已完成 Phase 00~13 的完整交付（v0.11.0）。当前已具备受控 ReAct、9 个本地工具、安全围栏与人工审批 (HITL)、上下文预算与分层记忆、精确代码检索、代码库增量 RAG、Plan-and-Execute DAG 拓扑调度、有界受控并发调度 (Bounded Parallelism)、Multi-Agent 专职协作架构、多模型路由适配层、Model Context Protocol (MCP) 生态扩展子系统，以及 Web 检索与浏览器安全沙箱子系统（SSRF 安全围栏与限流、多搜索引擎抽象与免 Key 降级、Readability 正文提取、浏览器 ISOLATED/SHARED 模式分层、敏感页面写操作硬审批、宿主标签页防误关保护与 /browser 终端控制台）。后续章节中的 Skills 仍为目标架构。

## 1. 文档目的

本文档定义 XhlCLI 的全局技术边界、核心数据结构、模块职责、依赖方向、运行流程、持久化、安全、并发、测试和分期兼容策略。各 Phase 开发前仍需建立当期技术设计或在实施计划中明确接口；本文档不替代逐任务实现计划。

## 2. 设计目标

1. 用一套 Run/Message/Tool/Event 模型支撑 ReAct、Plan 和 Multi-Agent。
2. 新增模型不修改 Agent 主循环，新增工具不修改模型客户端。
3. 所有工具经过统一参数校验、安全策略、审批、执行和审计管线。
4. 终端、Plain 输出和 Runtime API 消费同一事件流，不直接侵入 Agent。
5. 上下文、记忆、RAG 和 Skill 按需注入，有清晰预算与来源。
6. 分期迁移时每个阶段独立编译、测试和演示，不提前引入后期模块。
7. 在授权范围内复用成熟实现和测试，同时完成品牌、包名、Java 21 与产品差异适配。

## 2.1 当前已交付技术基线 (Phase 13, v0.11.0)

- **Web 检索与浏览器安全沙箱子系统 (Phase 13)**：
  - **网络安全围栏 (`NetworkPolicy`)**：强制实施协议白名单（仅允许 `http`, `https`），通过 DNS 解析深度拦截私网 IPv4/IPv6、Loopback (`127.0.0.1`, `localhost`)、Link-Local (`169.254.x.x`) 与 `0.0.0.0`；重定向跃点全链路动态校验防 SSRF 穿透；内置 Token Bucket 算法执行 30 次/60 秒速率控制。
  - **多源搜索引擎抽象与降级 (`SearchProvider`)**：统一领域抽象 `SearchResult` 与 `SearchProvider` 接口，完整实现 `SerpApiSearchProvider` (Google Search)、`SearxngSearchProvider` (开源元搜索)、`ZhipuSearchProvider` (智谱 Web Search) 以及零配置免 Key 的 `DuckDuckGoSearchProvider` 兜底降级方案，通过 `SearchProviderFactory` 根据环境配置自动组装最佳 Provider。
  - **网页正文抓取与 Readability 抽取 (`WebFetcher` + `HtmlExtractor`)**：5MB 受限流式读取防 OOM，智能响应头及 HTML Meta 编码嗅探；基于 Jsoup 清理 `<script>`、`<style>`、`<nav>`、`<footer>` 等噪声标签与广告容器，结合语义节点与文本密度打分算法提取正文，并精细转换为格式规范的 Markdown（标题、段落、列表、表格、代码块及缩进）；反爬/空内容页面自动提示浏览器工具降级。
  - **浏览器沙箱分层与安全守护 (`BrowserGuard` + `BrowserSession`)**：默认处于 `ISOLATED` 独立物理沙箱模式；支持通过 CDP 协议连接宿主已打开调试端口的 Chrome 进入 `SHARED` 共享模式；`SensitivePagePolicy` 建立银行、支付、云控制台规则库并支持用户自定义扩展；`BrowserGuard` 在 `SHARED` 模式下严禁关闭非 Agent 自建的宿主标签页，对敏感页面的写操作（`click`, `fill`, `evaluate_script` 等）强制触发单步 HITL 审批，即使用户在会话中配置全部放行（approve-all）亦绝对无法穿透。
  - **本地工具与终端指令打通**：向 `ToolRegistry` 注入 `web_search`、`web_fetch`、`browser_connect`、`browser_disconnect`、`browser_status` 工具，并在终端主循环挂载 `/browser [status|connect|disconnect|tabs]` 命令族。
- **MCP 生态扩展子系统 (Phase 12)**：
  - **协议层**：基于 JSON-RPC 2.0 规范，完整实现 MCP 2024-11-05 协议握手、版本协商、双向请求/响应与单向通知机制。
  - **双通道传输 (Transports)**：
    - `StdioMcpTransport`：通过 Java `ProcessBuilder` 与子进程 UTF-8 管道全双工交互，具备标准非协议行日志过滤、64KB 循环缓冲环提取 stderr 异常，以及协作式中断取消能力。
    - `StreamableHttpMcpTransport`：基于 OkHttp 实现 HTTP POST/SSE 通信，支持自定义 Headers 与 Bearer Token 注入。
  - **两级配置与环境变量安全解析**：`McpConfigLoader` 实现全局 `~/.xhlcli/mcp.json` 与项目级 `.xhlcli/mcp.json` 覆盖合并，严格解析 `${VAR}` 与 `${VAR:-default}`；未定义变量自动隔离对应服务并标记 `ERROR`，绝不造成进程崩溃。
  - **工具发现与动态适配**：`McpToolAdapter` 将 MCP 工具映射至 `mcp__{server}__{tool}` 隔离命名空间，自动清洗 inputSchema，映射多模态结果（Image 转为安全元数据文本），并在 `ToolRegistry` 中实现并发安全动态注入与 Agent 全局同步。
  - **资源管理**：支持 `resources/list` 与 `resources/read` 协议操作，实现文本与二进制资源读取与终端展现。
  - **生命周期与启动预算**：`McpServerManager` 管理服务启动、停止、重启与状态追踪，施加 3 秒启动硬性预算控制（超时降级），支持监听 `notifications/tools/list_changed` 动态热更新工具。
  - **安全沙箱与人机审批**：MCP 外部工具默认评定为 `MEDIUM_RISK`，在交互式终端强制触发 HITL 确认，支持 `trustedReadOnly` 白名单放行；敏感凭证由 `StreamingSecretRedactor` 深度脱敏。
- **多模型路由与能力声明 (Phase 11)**：
  - `ModelCapabilities`：模型能力元信息统一声明（上下文窗口、工具支持、多模态视觉、Prompt 缓存模式、思考链要求）。
  - 多协议适配层：
    - OpenAI-compatible 族：`AbstractOpenAiCompatibleClient`, `DeepSeekClient`, `OpenAiClient`；
    - 协议差异 Provider 1：`AnthropicClaudeClient` 与 `AnthropicSseParser`（原生 `/v1/messages` 协议、顶级 `system` 拆分、`input_schema`、`tool_use`/`tool_result` 块）；
    - 协议差异 Provider 2：`OllamaClient`（本地 `/api/chat` 原生 NDJSON 逐行流式解析、免 API Key）；
  - `LlmProviderRegistry` 与 `ModelDescriptor`：支持环境变量/`.env` 凭据探测、模型别名模糊匹配与安全工厂实例化；
  - 终端运行时动态模型切换（`/model list`、`/model use <model>`、`/model status`），自动联动更新 `TokenBudget` / `ContextAssembler` 上下文预算并提供超额预警；
  - 工具能力安全护栏：不支持工具调用的模型在 Agent/Plan/Team 模式启动前提前拦截拒绝，杜绝无效与不兼容请求。
- **核心 Agent 架构**：
  - `ReactAgent`：基于不可变 `ChatMessage`、`ToolCall`、`ToolResult` 协议驱动串行/并行 ReAct 循环，集成 `RunLifecycle`、严格递增序号事件流与取消/超时保护。
  - `PlanExecuteAgent`：基于 Kahn 算法与 DAG 拓扑排序实现分层批次推进，支持终端 HITL 人机审阅交互、重排熔断与短期记忆自动回写。
  - `TeamOrchestrator`：实现 1+2+1 专职协作架构（Planner 纯规划无工具、Worker 池化排他借用持完整工具、Reviewer 质量审查无写工具），基于最小化上下文交接包 `HandoverPackage` 与结构化审查重试熔断闭环，支持无依赖步骤受控并发借用与独立内存流隔离日志，批次完成后保序 flush 到终端。
- **本地工具与安全审计**：
  - 注册 9 个本地开发工具（`read_file`, `write_file`, `apply_patch`, `git_diff`, `execute_command`, `list_dir`, `glob_files`, `grep_code`, `search_code`）。
  - 工作区路径越界防护 `WorkspacePathResolver`、硬策略围栏（`PathGuard`, `CommandGuard`）、脱敏审计日志 `AuditLog` 与终端交互审批 `TerminalHitlHandler`。
- **上下文、检索与并发**：
  - Token 预算控制 `TokenBudget`、层级上下文组装 `ContextAssembler`、长期记忆 `MemoryManager`、历史会话压缩 `ConversationHistoryCompactor`。
  - Ripgrep/Java 双引擎精准搜索与 SQLite + AST 增量语义向量检索引擎 `CodeIndex` / `CodeRetriever`。
  - 有界并发执行器 `BoundedParallelExecutor`：读读共享/读写互斥检测、乱序保序归并、故障超时隔离与协作式取消。
- **CLI 交互打通**：
  - JLine 终端会话集成，支持交互式指令：`/help`, `/config`, `/clear`, `/context`, `/compact`, `/save`, `/memory`, `/search-text`, `/index`, `/search`, `/plan`, `/team`, `/model`, `/mcp`。

## 3. 非目标

- 不采用微服务拆分本地 CLI。
- 不在核心模块绑定具体模型 Provider、数据库或终端库。
- 不通过继承层级模拟所有未来 Agent 类型。
- 不使用 Java Preview API 作为主路径。
- 不在早期阶段建立云端多租户、分布式队列或插件市场。
- 不为保持参考实现文件结构而保留已经确认的职责混杂。

## 4. 技术基线

| 类别 | 选择 | 说明 |
|---|---|---|
| 语言 | Java 21 | 使用 Records、sealed 类型、模式匹配、虚拟线程等稳定特性 |
| 构建 | Maven + Maven Wrapper | 便于本地、CI 和面试演示一致执行 |
| HTTP | OkHttp | 模型、Web 和 Streamable HTTP |
| JSON | Jackson | 消息、配置、审计和 JSON-RPC |
| 测试 | JUnit 5 + MockWebServer | 单元、契约与 HTTP 集成测试 |
| 终端 | JLine | 在 Phase 15 引入，早期使用 Plain I/O |
| 本地数据 | 文件 + SQLite | 简单配置用文件，检索/任务等结构化数据用 SQLite |
| Git | JGit | Phase 16 用于隔离快照，不取代用户系统 Git |
| HTML | Jsoup | Phase 13 正文抽取 |
| 代码解析 | JavaParser + 可选 LSP | RAG 切分和 Java 轻量诊断 |

依赖版本在各期实现计划中固定，并由依赖更新工具单独升级。迁移某模块时沿用已验证版本优先于盲目追新。

## 5. 总体架构

```text
┌───────────────────────────────────────────────────────────────┐
│ Entrypoints                                                    │
│ CLI / Plain / Inline Terminal / Runtime API                    │
└──────────────────────────────┬────────────────────────────────┘
                               │ Command / UserInput
┌──────────────────────────────▼────────────────────────────────┐
│ Application                                                    │
│ Bootstrap / CommandRouter / RunService / Configuration         │
└───────────────┬──────────────────────────────┬────────────────┘
                │                              │ RunEvent
┌───────────────▼────────────────┐   ┌─────────▼────────────────┐
│ Agent Runtime                  │   │ Presentation              │
│ ReAct / Plan / Team            │   │ Renderer / Event Stream   │
│ Budget / Cancellation          │   │ Approval Interaction      │
└───────────────┬────────────────┘   └──────────────────────────┘
                │ ToolCall
┌───────────────▼───────────────────────────────────────────────┐
│ Tool Execution Pipeline                                       │
│ Registry → Validation → Policy → HITL → Execute → Audit        │
└──────┬─────────────┬───────────────┬───────────────┬─────────┘
       │             │               │               │
  Local Tools       MCP          Web/Browser      Snapshot/LSP
       │
┌──────▼────────────────────────────────────────────────────────┐
│ Context & Knowledge                                            │
│ Conversation / Project Rules / Memory / Search / RAG / Skill   │
└───────────────────────────────────────────────────────────────┘
       │
┌──────▼────────────────────────────────────────────────────────┐
│ Infrastructure                                                 │
│ LLM Providers / HTTP / SQLite / File System / Git / Process    │
└───────────────────────────────────────────────────────────────┘
```

依赖方向从入口指向应用、领域接口和基础设施实现。领域层不依赖终端、OkHttp、SQLite、JLine 或具体 Provider 类。

## 6. 目标目录结构

```text
xhlcli/
├── pom.xml
├── mvnw
├── mvnw.cmd
├── .mvn/wrapper/
├── src/main/java/com/xhlcli/
│   ├── cli/          # 启动、命令路由、输入规范化、补全与历史
│   ├── app/          # RunService、装配和应用级用例
│   ├── agent/        # ReAct、Plan、SubAgent、Orchestrator
│   ├── model/        # Run、Message、ToolCall、ToolResult、RunEvent
│   ├── llm/          # LlmClient 接口、Provider 适配与能力声明
│   ├── tool/         # Tool、ToolRegistry、执行管线与本地工具
│   ├── policy/       # 路径、命令、网络和敏感数据策略
│   ├── hitl/         # 审批请求、决策与交互适配
│   ├── context/      # 请求预算、上下文组装与压缩
│   ├── memory/       # 会话、项目规则与长期记忆
│   ├── search/       # glob、grep、范围读取与搜索结果
│   ├── rag/          # 代码切分、索引、Embedding、检索
│   ├── plan/         # ExecutionPlan、Task、DAG 与调度
│   ├── mcp/          # 客户端、Transport、协议、资源和通知
│   ├── web/          # 搜索、正文抽取和网络策略
│   ├── browser/      # 浏览器会话与敏感页面策略
│   ├── skill/        # Skill 发现、解析、状态和上下文缓冲
│   ├── prompt/       # Prompt 文件加载和分层组装
│   ├── render/       # Renderer 与用户可见事件投影
│   ├── lsp/          # 诊断模型与 Language Server 适配
│   ├── snapshot/     # Side-History 与恢复
│   ├── runtime/      # 取消、持久任务和本地 API
│   ├── image/        # 图片引用、处理和 ContentPart
│   └── config/       # 配置模型、加载与校验
├── src/main/resources/
│   ├── prompts/
│   ├── skills/
│   └── logback.xml
├── src/test/java/com/xhlcli/
├── docs/prd/
├── docs/engineering/
├── docs/plans/
└── benchmark/
```

目录随阶段逐步出现。Phase 00 不建立空业务包，只有实际交付功能进入源码树。

## 7. 核心领域模型

### 7.1 Run

`Run` 表示一次从用户任务到终态的执行，包含：

- `runId`
- `threadId`
- `mode`：CHAT、REACT、PLAN、TEAM
- `workspace`
- `status`
- `createdAt` / `startedAt` / `finishedAt`
- `budget`
- `cancellationToken`

Run 状态只能通过应用服务迁移。进入 `completed`、`failed`、`canceled` 或 `limit_reached` 后，不得产生新的模型请求或工具执行。

### 7.2 Message

统一消息包含角色、内容块和可选 Provider 元数据。文本、工具调用、工具结果和图片使用明确内容类型，不用混合字符串解析协议。

```text
Message
  ├── TextPart
  ├── ToolCallPart
  ├── ToolResultPart
  └── ImagePart
```

Provider 私有字段保留在适配层元数据中，不能污染工具或 Agent API。

### 7.3 Tool

每个工具公开：

- 唯一名称与描述。
- 输入 JSON Schema。
- 风险元数据与资源键。
- 是否只读、是否支持取消、是否允许并行。
- 异步或可取消执行入口。

工具返回 `ToolResult`，明确区分成功、验证错误、策略拒绝、用户拒绝、执行失败、超时和取消。

### 7.4 RunEvent

事件是 UI、审计和 Runtime API 的共享语言，至少包含：

- Run 开始与终态。
- 模型请求开始、文本增量和完成。
- 工具开始、结束、失败和审批等待。
- 计划或任务状态变化。
- 上下文压缩、诊断和快照状态。

事件带 Run ID、序号和时间。持久 API 使用同一序号作为恢复游标。

## 8. 核心接口边界

### 8.1 `LlmClient`

职责：接收统一消息与工具描述，返回流式统一事件；声明模型能力；支持取消。

不得负责：执行工具、读取项目、审批、终端输出和长期记忆。

### 8.2 `AgentRunner`

职责：驱动模型决策、工具调用回灌、停止条件和最终结果。

不得负责：直接调用具体 HTTP Client、直接打印终端、跳过工具管线。

### 8.3 `ToolRegistry`

职责：注册、发现和按名称解析工具定义。

不得负责：把所有工具实现写入一个巨型 `switch`。内置工具通过独立类或小型功能组注册。

### 8.4 `ToolExecutor`

职责：参数校验 → Policy → HITL → 并发资格 → 工具执行 → Audit → 结果预算。

Agent、Plan、Worker 和 MCP 都必须使用同一执行入口。

### 8.5 `ContextAssembler`

职责：按层组装最终请求、执行预算、选择记忆与检索片段、保持消息协议边界。

不得负责：模型 HTTP 序列化或持久化全部会话。

### 8.6 `Renderer`

职责：把 RunEvent 和 ApprovalRequest 投影为用户可见交互。

不得负责：Agent 决策、状态机或安全判断。

## 9. ReAct 执行时序

```text
User → RunService: createRun(input, workspace)
RunService → ContextAssembler: buildContext(run)
RunService → AgentRunner: execute(context)
AgentRunner → LlmClient: stream(messages, tools)
LlmClient → EventSink: text/tool-call events
AgentRunner → ToolExecutor: execute(toolCall)
ToolExecutor → Policy: evaluate
Policy → HITL: request approval when needed
ToolExecutor → Tool: invoke
Tool → ToolExecutor: ToolResult
ToolExecutor → Audit/EventSink: result
AgentRunner → ContextAssembler: append observation
AgentRunner → LlmClient: next iteration
AgentRunner → RunService: final result
RunService → EventSink: completed/failed/canceled
```

## 10. 工具执行管线

固定顺序：

1. 查找工具并校验调用 ID。
2. 校验 JSON 参数和字段类型。
3. 规范化路径、URL、命令和资源键。
4. 执行系统硬策略。
5. 计算风险与审批需求。
6. 获取用户或非交互策略决定。
7. 绑定超时与取消信号。
8. 执行工具。
9. 裁剪和结构化结果。
10. 写入脱敏审计并发布事件。

任何新工具都不能绕过第 2–10 步。

## 11. 上下文与记忆设计

### 11.1 组装层级

```text
base
→ tool/safety policy
→ agent mode
→ runtime context
→ project rules
→ retrieved memory
→ skill content
→ compacted conversation
→ current input
```

每层独立预算并记录来源。项目规则和长期记忆不能在 `/clear` 时误删；临时检索结果不能持久保存为长期记忆。

### 11.2 压缩门槛

门槛按模型窗口动态计算，并至少预留最大输出、下一轮工具结果和安全缓冲。压缩结果使用结构化段落，保留目标、约束、变更、验证和剩余任务。

### 11.3 代码知识双路径

- 精确路径：glob → grep → range read。
- 语义路径：query → embedding → vector candidates → merge → current file verification。

RAG 结果不能直接用于写入，必须读取当前文件验证。

## 12. 配置设计

### 12.1 来源优先级

```text
CLI 参数
> 系统属性
> 环境变量
> 项目 .xhlcli/config.json
> 用户 ~/.xhlcli/config.json
> 默认值
```

API Key 默认仅引用环境变量名。`.env` 只作为本地开发便利，不提交仓库。

### 12.2 用户数据目录

```text
~/.xhlcli/
├── config.json
├── history/
├── memory/
├── indexes/
├── logs/
├── audit/
├── tasks/
├── snapshots/
├── prompts/
└── skills/
```

项目级扩展位于 `<workspace>/.xhlcli/`。所有格式包含版本或兼容判断。

## 13. 安全设计

### 13.1 信任边界

- 用户输入、模型输出、网页内容、MCP 内容和 Skill 文本均为不可信输入。
- Policy 是确定性硬边界；Prompt 只指导模型行为。
- 用户批准可以授权策略允许的风险操作，不能覆盖硬拒绝。

### 13.2 路径

使用规范化真实路径检查项目根；目标不存在时检查最近存在父目录；符号链接解析后再次检查。所有恢复和写入使用原子策略。

### 13.3 命令

命令执行固定工作目录、环境白名单、超时和输出预算。高风险语义快速拒绝只是辅助，不能宣称提供进程隔离。

### 13.4 网络

协议、DNS 结果和每次重定向都做目标检查，默认拒绝私网与 loopback。浏览器共享会话需要额外授权。

### 13.5 凭据

配置、事件、审批、日志和导出统一使用脱敏器。测试用假凭据也需要覆盖嵌套 JSON、Header、URL 参数和命令文本。

## 14. 并发与取消

- Java 21 使用有界调度器；适合阻塞 I/O 的任务可使用虚拟线程。
- 不使用 Preview `StructuredTaskScope`，避免运行参数和发行复杂度。
- Tool 和 Task 声明资源键；相同写资源串行。
- Run 取消信号传播到模型、命令、MCP、Web 和后台任务。
- 终态后迟到事件只记录，不改变 Run 状态。
- 所有用户可见输出通过事件序列化，工作线程不直接写 stdout。

## 15. 持久化

| 数据 | 早期实现 | 后期实现 | 恢复要求 |
|---|---|---|---|
| 非敏感配置 | JSON | JSON | 原子替换与备份 |
| 会话导出 | JSONL/Markdown | 同左 | 损坏行可跳过 |
| 长期记忆 | JSONL | 可选 SQLite | 可列出、删除和迁移 |
| RAG 索引 | 不适用 | SQLite | 索引版本不兼容时重建 |
| 审计 | JSONL | JSONL | 追加写、按天滚动 |
| 后台任务 | 不适用 | SQLite | 事务状态与租约恢复 |
| 快照 | 不适用 | 独立 JGit 仓库 | 不影响用户 `.git` |

## 16. 测试架构

### 16.1 测试金字塔

- 单元测试：状态机、预算、解析、策略、裁剪、路径和配置。
- 契约测试：LlmClient、Tool、MCP Transport、Renderer。
- 集成测试：Mock HTTP、临时目录、临时 Git、SQLite。
- 端到端测试：可执行 JAR + Fake Model 完成确定性任务。
- 手工测试：真实 Provider、终端、浏览器和跨平台行为。

### 16.2 可替换边界

测试中必须能替换时间、ID、模型、HTTP、进程执行、文件系统根和事件 Sink。核心回归不依赖真实 Key、网络和本机 MCP。

### 16.3 分期门禁

每期门禁以阶段 DoD 要求的自动化和可复现验收证据为准：

```text
针对性单元/集成测试
→ quick 回归
→ package
→ 自动化/可复现验收证据
```

手工验收仅在阶段 DoD 要求且未明确豁免时执行。

正式发布再执行全量、跨平台、安全与基准任务。

## 17. 构建与发行

- Maven Wrapper 是文档和 CI 的唯一默认入口。
- 编译使用 `--release 21`，而不是独立 source/target 漂移。
- 普通测试默认执行；不采用“package 默认跳过所有测试”的长期策略。
- 可执行 Fat JAR 的 Main-Class 指向 `com.xhlcli.cli.Main`。
- 构建产物不提交 Git。
- 版本来自单一构建属性，Banner、`--version` 和发行标签保持一致。

## 18. 选择性迁移流程

每一期实施按以下顺序：

```text
固定参考 commit
→ 读取历史最小实现
→ 读取最终对应模块与测试
→ 建立当期文件清单和依赖图
→ 迁移测试与领域接口
→ 迁移最小实现
→ 包名/配置/品牌替换
→ Java 21 适配
→ XhlCLI 主动差异
→ 验证、文档、真实提交
```

详细映射见 [`docs/engineering/source-adoption-map.md`](docs/engineering/source-adoption-map.md)。参考仓库工作树可能包含用户改动，读取历史必须使用 `git show <commit>:<path>`；不得通过 reset 或 checkout 清理其工作树。

## 19. 分期兼容策略

- 早期版本保存的数据格式可以简单，但一旦公开就必须增加版本字段。
- 后期接口通过新增实现扩展，不要求早期提交提前包含空接口。
- 模块进入仓库的阶段由 PRD 决定，最终架构目录不等于 Phase 00 目录。
- 历史迁移时保留当期可运行状态，不让后期 Prompt、命令或文档提前出现。
- 每期 README 只展示已经通过当前提交验收的功能。

## 20. 关键架构决策

| 决策 | 选择 | 原因 |
|---|---|---|
| 执行模型 | 单进程模块化单体 | 本地 CLI 部署简单，模块接口足够隔离 |
| 核心闭环 | ReAct 默认，Plan/Team 显式 | 简单任务低开销，复杂任务可控 |
| 工具扩展 | 内置 Tool + MCP | 本地核心可靠，外部能力标准化 |
| 代码理解 | 精确搜索优先、RAG 辅助 | 可解释、低成本、减少语义噪声 |
| 安全 | Policy + HITL + Audit + Snapshot | 多层控制，不把 Prompt 当防线 |
| UI | 事件驱动 Inline + Plain | 保留终端历史并支持降级 |
| 并发 | 有界虚拟线程 | 适合 I/O，Java 21 稳定能力 |
| 持久化 | 文件 + SQLite | 避免为简单配置引入数据库，同时满足索引与任务事务 |

## 21. 已知风险

- 授权参考实现的超大类会诱导直接复制职责混杂，必须按阶段提取边界。
- Provider 工具调用协议差异容易泄漏到 Agent，必须用契约测试约束。
- 终端、并行事件与审批同时出现容易破坏 UI，所有输出必须事件化。
- RAG、记忆和 Skill 都会消费上下文，必须共享统一预算。
- 授权声明的具体署名和再发布条件尚需在首次源代码迁移前落实到仓库文件。

## 22. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-08-25 | 确立 Java 21、模块边界、事件模型、安全管线与选择性迁移方案 |
| v1.1 | 2026-08-27 | 记录已交付的 Phase 02 受控 ReAct 基线及其与后续目标架构的边界 |
