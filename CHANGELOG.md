# Changelog

本项目的重要变更记录在此文件中，格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [0.10.0] - 2026-09-21

### Added
- **Model Context Protocol (MCP) 生态扩展（MCP Extensibility Subsystem）** (Phase 12):
  - 核心协议模型与 JSON-RPC 2.0 序列化：实现标准 JSON-RPC 2.0 协议包（`JsonRpcRequest`, `JsonRpcResponse`, `JsonRpcNotification`, `JsonRpcError`）及 MCP 2024-11-05 规约模型（`McpToolDefinition`, `McpResource`, `McpResourceContent`, `McpCallResult` 等）。
  - 双通道传输抽象与实现：
    - `StdioMcpTransport`：利用 `ProcessBuilder` 与 UTF-8 管道与本地子进程交互，非协议行文本隔离过滤，64KB 循环缓冲环捕获 stderr 崩溃诊断，结合 `CancellationToken` 支持协作式销毁。
    - `StreamableHttpMcpTransport`：基于 OkHttp 实现 HTTP POST / SSE 通信，支持自定义 Header 与 Bearer Token 注入，支持 Socket 取消断开。
  - 两级配置安全合并与环境变量解析器 `McpConfigLoader`：用户全局 `~/.xhlcli/mcp.json` 与项目级 `.xhlcli/mcp.json` 双层解析，项目配置就近覆盖，严格支持 `${ENV}` 变量插值与默认值语法；缺失未定义环境变量抛出 `UnresolvedEnvException` 并隔离标记故障服务器，绝不阻断系统启动。
  - 工具发现与命名空间适配器 `McpToolAdapter`：动态映射工具至 `mcp__{server}__{tool}` 隔离命名空间，自动清洗 inputSchema（剥离 `$schema`/`definitions` 冗余约束），映射 Text 与 Image（转为安全摘要描述），接入 `ToolRegistry` 实现热加载与并发线程安全查询。
  - 安全沙箱与人机确认联动：所有 MCP 动态工具默认评定为 `RiskLevel.MEDIUM_RISK`，支持 `trustedReadOnly` 白名单放行机制；调用入参与出参经 `StreamingSecretRedactor` 自动脱敏并存入 `AuditLog`。
  - 资源读取协议支持：实现 `resources/list`、`resources/read` 接口，支持文本与 base64 二进制资源提取与终端展示。
  - 服务器生命周期管理与启动预算 `McpServerManager`：支持启动、停止、重启、日志查看；内置 3 秒并发拉起启动预算（`DEFAULT_STARTUP_BUDGET = Duration.ofSeconds(3)`），启动超时自动降级；监听 `notifications/tools/list_changed` 通知并实时热刷新工具列表。
  - 交互终端命令族扩展：新增 `/mcp` 命令族（`/mcp list`、`/mcp status <server>`、`/mcp tools`、`/mcp resources`、`/mcp read <uri>`、`/mcp restart <server>`、`/mcp stop <server>`、`/mcp start <server>`、`/mcp logs <server>`）。
  - 全套新增 24 项专项单元测试与端到端集成测试（`McpIntegrationTest`, `McpCliIntegrationTest` 等），全量 329 项自动化测试 100% 绿灯。
  - 产出设计规范 `docs/specs/2026-09-21-phase-12-mcp-design.md`、实施计划 `docs/plans/2026-09-21-phase-12-mcp.md` 与评测报告 `docs/engineering/mcp-evaluation.md`。

## [0.9.0] - 2026-09-18

### Added
- **多模型路由与能力声明（Multi-Model Adaptation & Capability Declarations）** (Phase 11):
  - 核心领域模型与能力元信息 `ModelCapabilities`：解耦并精确声明最大上下文窗口、工具调用支持（`supportsTools`）、视觉多模态支持（`supportsImageInput` / `supportsVision()`）、Prompt Cache 模式（`promptCacheMode`）及推理模型标记（`requiresReasoningEffort`）。
  - 通用 LLM 抽象接口扩展 `LlmClient`：解耦 Provider 名称（`providerName()`）、模型名称（`modelName()`）与模型能力元信息（`capabilities()`）。
  - 统一 OpenAI-compatible 族适配：抽象出可复用的 `AbstractOpenAiCompatibleClient`，重构 `DeepSeekClient`，并新增 `OpenAiClient`（标准 OpenAI `/v1/chat/completions` 协议），支持 `gpt-4o`、`o1`、`o3-mini` 及推理模型特性识别。
  - 原生 Anthropic 协议适配器 `AnthropicClaudeClient` 与流式解析器 `AnthropicSseParser`：支持原生 `/v1/messages` 协议、顶层 `system` 拆分、Anthropic 格式工具声明（`input_schema`）与双向转化（`tool_use` / `tool_result`）、多事件 SSE 增量解析（`content_block_delta`、`input_json_delta`）及自动重试与鉴权。
  - 本地隐私优先 Ollama 适配器 `OllamaClient`：本地免 Key 直连 `/api/chat`，原生逐行 NDJSON 流式解析与工具调用双向映射。
  - Provider 注册中心与模型工厂 `LlmProviderRegistry`：统一注册与管理 deepseek、openai、anthropic、ollama 等多 Provider，支持环境变量与 `.env` 凭据自动探测、模型别名模糊匹配（`claude-3-7-sonnet` -> `claude-3-7-sonnet-20250219`、`gpt-4o`、`deepseek-chat` 等）与运行时代工实例化。
  - 上下文预算动态联动与超限告警：模型切换触发 `TokenBudget.updateContextWindow()` 动态重算分层配额，当历史对话超出新模型容量时发出结构化超限预警并建议执行 `/compact`。
  - 工具能力硬拦截与防御护栏：在 `ReactAgent`、`PlanExecuteAgent`、`Planner` 与 `TeamOrchestrator` 中引入 `supportsTools` 前置能力校验，拦截无工具调用能力模型（如 `o1`、轻量无 tool 权重）的无效 Agent/Plan/Team 执行请求并给出明确指导。
  - 终端命令与交互增强：新增 `/model` 指令族（`/model list` 查看可用模型与凭据探测状态，`/model status` 查看当前激活模型及其详细能力参数，`/model use <model>` 运行时动态热切换）。
  - 全套新增 14 项专项契约测试与集成测试（`LlmProviderContractTest`, `AnthropicClaudeClientTest`, `OllamaClientTest`, `LlmProviderRegistryTest`, `ModelSwitchingIntegrationTest`），全量 305 项自动化测试 100% 绿灯。
  - 产出设计规范 `docs/specs/2026-09-18-phase-11-multi-model-design.md`、实施计划 `docs/plans/2026-09-18-phase-11-multi-model.md` 与评测报告 `docs/engineering/multi-model-evaluation.md`。

## [0.8.0] - 2026-09-16

### Added
- **Multi-Agent 协作架构（Multi-Agent Collaboration）** (Phase 10):
  - 专职角色架构（1+2+1 体系）：明确划分 `PLANNER`（专职规划解耦，严禁工具调用）、`WORKER`（池化执行单元，持有完整开发工具与多轮 ReAct 能力）、`REVIEWER`（专职质量审查，纯逻辑审查无写工具）与 `TeamOrchestrator`（统筹编排调度，对全局目标负责）。
  - 角色提示词工程 `TeamPrompts`：针对规划者、执行者、审查者深度定制 System Prompt，强制规范 JSON 协议输出与 Local Code First 原则。
  - 最小化上下文交接包 `HandoverPackage`：规范 Agent 间任务交接标准，仅传递当前步骤目标、依赖结果摘要、验收标准与文件改动，杜绝全量对话历史交叉污染。
  - 结构化质量审查与容错降级 `ReviewResult`：支持标准 JSON 审查报告（approved, summary, issues, suggestions）解析与文本启发式容错降级，具备保守性安全兜底。
  - 有限重试与熔断保护机制：执行质量不达标时条目化反馈打回 Worker 修正，单步支持最多重试 2 次（`MAX_RETRIES = 2`），超出立即熔断终止，杜绝死循环与 Token 浪费。
  - 独立专职子代理 `SubAgent`：具备独立会话生命周期、`clearHistory` 历史复位与工具权限动态下发控制，支持流式日志重定向输出。
  - 无依赖步骤受控并发（Worker 池排他调度）：复用 `BlockingQueue<SubAgent>` 保证 Worker 实例互斥借用，每个并发步骤使用独立内存流（`ByteArrayOutputStream`）缓冲输出，批次完成后按步骤保序 flush 到终端，彻底根除多 Agent 并发控制台交错乱序。
  - 交付汇总看板：汇总各步骤状态、重试次数、审查结论与最终交付物明细，自动沉淀至项目级长期记忆（`MemoryManager`）。
  - CLI 指令打通：`/team` 交互式提示与 `/team <任务描述>` 一键拉起，内置极简轻量任务降级引导提示。
  - 全套新增 20 项 Multi-Agent 专项单元测试，全量 291 项测试 100% 绿灯。
  - 产出设计规范 `docs/specs/2026-09-16-phase-10-multi-agent-design.md`、实施计划 `docs/plans/2026-09-16-phase-10-multi-agent.md` 与评测报告 `docs/engineering/multi-agent-evaluation.md`。

## [0.7.0] - 2026-09-14

### Added
- **并行执行（Bounded Parallelism）** (Phase 09):
  - 有界并发执行器 `BoundedParallelExecutor`：基于 Java 21 平台线程池受控并发，实现单批次工具并发加速，并发度限制支持 1-16（默认 4）。
  - 并发资格与资源互斥检测 `ParallelEligibilityDecider` / `ResourceAccess`：严格遵循“读读共享、读写互斥、写写互斥”原则，对只读且显式声明 `allowsParallel` 的工具开放并发，写操作、命令执行、未声明工具及同文件操作强制串行。
  - 批次划分调度器 `ParallelBatchScheduler`：贪心将模型单轮次返回的多个工具调用切分为连续的并行批次与串行批次，精确记录原始索引 `IndexedToolCall`。
  - 乱序保序归并（In-order Merge）：多线程异步完成的工具结果在主线程按原始下标保序重构，确保 Prompt 上下文与 Observation 序列的绝对确定性。
  - 失败隔离与协作式取消（Fault Isolation & Cooperative Cancellation）：单工具超时（`toolTimeout`，默认 60s）转化为结构化错误，不波及同批次其他成功工具；Run 级 `CancellationToken` 协作式中断运行中与未启动任务。
  - `ReactAgent` 与 `PlanExecuteAgent` 深度整合：`EventSequencer` 线程安全升级，DAG 任务层支持受控有界并发度分块。
  - 配置与 CLI 选项扩展：支持 `--max-concurrency <1-16>` 与 `--tool-timeout <seconds>`，对应环境变量 `XHLCLI_MAX_CONCURRENCY`、`XHLCLI_TOOL_TIMEOUT_SECONDS` 及配置文件。
  - 全套新增 25 项单元与集成测试（全量 271 项测试 100% 绿灯）。
  - 产出设计规范 `docs/specs/2026-09-14-phase-09-parallel-execution-design.md`、实施计划 `docs/plans/2026-09-14-phase-09-parallel-execution.md` 与基准报告 `docs/engineering/parallel-execution-benchmark.md`。

## [0.6.0] - 2026-09-11

### Added
- **智能规划（Plan-and-Execute）** (Phase 08):
  - 任务领域模型 `Task`：支持多任务类型与生命周期状态，细粒度依赖绑定（`dependencies` / `dependents`）。
  - 执行计划聚合根 `ExecutionPlan`：实现 DFS 三色标记拓扑排序与严格有向环检测（Cycle Detection），杜绝非法/循环依赖任务进入执行层。
  - Kahn 分层批次算法（`getExecutionBatches`）：实现 DAG 拓扑分层推进、ASCII 边框可视化（`visualize`）与紧凑折叠摘要（`summarize`）。
  - 智能规划器 `Planner`：内置单步轻量任务快速识别与规则短路（`isSimpleGoal`），复杂任务 LLM 两遍建图解析，支持错误驱动的自适应重规划（`replan`）。
  - 人机协同审阅交互（HITL Review）：提供 `PlanReviewInputParser` 与 `PlanReviewHandler`，支持回车/run 确认执行、cancel/esc 零副作用取消、输入补充约束触发结合新条件的重新规划。
  - 执行引擎 `PlanExecuteAgent`（实现 `AgentRunner`）：DAG 拓扑分层调度，单任务主线程直跑，多任务受控并发（最多 4 线程）与独立内存缓冲流（`ByteArrayOutputStream`）日志隔离，彻底根除并发日志交错；单步任务受限 ReAct 循环（最多 5 轮）；具备 `MAX_REPLAN_ATTEMPTS = 2` 失败重排熔断保护与短期记忆自动回写。
  - CLI 指令打通：`/plan` 与 `/plan <任务描述>`，支持终端交互式任务规划与审阅。
  - 全套新增 31 项单元测试与端到端集成测试，全量 246 项测试 100% 绿灯。
  - 技术架构设计规范 `docs/specs/2026-09-11-phase-08-plan-and-execute-design.md`、实施计划 `docs/plans/2026-09-11-phase-08-plan-and-execute.md` 与评测报告 `docs/engineering/plan-and-execute-evaluation.md`。

## [0.5.0] - 2026-09-10

### Added
- **Codebase RAG** (Phase 07):
  - 引入嵌入式 SQLite 存储（`sqlite-jdbc:3.49.1.0`）与 Java AST 解析（`javaparser-core:3.28.0`）。
  - 基于文件 SHA-256 哈希的确定性增量索引机制，未修改文件毫秒级跳过，修改/删除实时同步。
  - 混合语义检索引擎 `CodeRetriever`：融合自然语言 Embedding、结巴与 ASCII 关键字分词（`RagQueryTokenizer`）、类型优先（method/class）及双重命中加权算法。
  - 结构化检索结果格式化器 `SearchResultFormatter`：CLI 友好卡片摘要与代码片段安全截断。
  - 只读 Agent 工具 `search_code`：作为自然语言代码探索入口注册到 `ToolRegistry`，并更新 Agent System Prompt。
  - CLI 交互指令支持：`/index [status|clean]`、`/search <query>` 与精确正则搜索 `/search-text <pattern>`。
  - 多 Provider 向量客户端 `EmbeddingClient`（Fake/Ollama/OpenAI/智谱），支持 100% 离线确定性单元测试。
  - 全套 24 项 RAG 专项测试与端到端黄金评测集 `CodeRetrieverGoldenSetTest`，全量测试 223 项全部通过。
  - 技术设计与评测报告 `docs/engineering/codebase-rag-evaluation.md`。

## [0.4.0] - 2026-09-07

### Added
- **Code Search** (Phase 06):
  - 全面扩充代码检索 Golden Set 评测集至 7 大真实场景（类定义、接口实现、方法调用、配置键、测试、入口函数、不存在符号安全负向用例），双引擎通过率 100%。
  - `GrepCodeTool` 无结果智能建议：依据当前搜索参数自动提示放宽大小写敏感、移除 glob 或缩短 pattern。
  - CLI 人工调试指令 `/search-text <pattern>`：终端直出高亮检索结果与 suggested_reads，无需消耗大模型 Token。
  - Agent System Prompt 核心检索流水线强化：明确 `glob_files -> grep_code -> read_file` 代码探索路径，强制本地代码首选原则，杜绝误触发网络搜索。
  - 发布交付物报告 `docs/engineering/code-search-golden-set.md`。

## [0.3.0] - 2026-09-02

### Added
- **Context & Memory** (Phase 05):
  - `TokenBudget` 字符级上下文预算管理与预估。
  - `ContextAssembler` 8 层标准上下文组装管线。
  - `MemoryManager` 与 `LongTermMemory`：项目级（`<workspace>/.xhlcli/memory`）与全局级（`~/.xhlcli/memory`）双重作用域隔离。
  - `ConversationHistoryCompactor`：长对话大模型驱动结构化无损压缩，保护 Tool Call / Tool Result 成对关系。
  - CLI 控制指令：`/context`、`/compact`、`/save`、`/memory`。
  - 动态实时长期记忆注入与端到端集成测试 `AgentMemoryIntegrationTest`。

- Phase 04 安全策略与人工审批 (Safety and Approval)：
  - 系统硬策略 `PathGuard`：路径围栏防逃逸（绝对路径越界、`..` 穿越、符号链接指向外部），不可被用户批准绕过。
  - 系统硬策略 `CommandGuard`：危险 Shell 命令 Fast-fail 拦截（`sudo`、`rm -rf /`、`mkfs`、`dd of=/dev`、fork bomb、`curl|sh`、`find /`、`chmod 777`、`shutdown/reboot`）。
  - 脱敏审计日志 `AuditLog`：每日 JSONL 结构化审计落盘（`~/.xhlcli/audit/`），自动掩码 Bearer Token、API Key、Password、Secret 等敏感凭据。
  - 风险分级策略 `ApprovalPolicy`：只读工具自动放行，写入/命令工具需人工审批，未注册工具默认高危。
  - 人工审批交互 `TerminalHitlHandler`：结构化终端审批框（CJK/Emoji 显示宽度精确对齐），支持 `y/a/n/s/m` 五种决策。
  - `DefaultToolExecutor` 编排：Schema → 硬策略 → HITL 审批 → 执行 → 审计，修改参数重新校验。
  - `ChatBootstrap` 装配与 `ChatLoop` `/clear` 联动清除会话临时授权。
  - `AgentSafetyIntegrationTest` 端到端安全闭环验证。
- Phase 03 本地工具集 (Local Tools)：
  - `WorkspacePathResolver`：强制限制文件操作在项目工作区内，防止路径穿越与非法越界。
  - 只读探索与读取：`list_dir`（过滤系统隐藏/构建目录）、`read_file`（支持 offset/limit 分页读取与行号标注）、`glob_files`（按 glob 匹配项目文件）。
  - 代码搜索体系：`JavaCodeSearchEngine`（纯 Java 跨平台搜索）、`RipgrepCodeSearchEngine`（流式 `rg --json` 快速搜索与超时降级）、`grep_code`（行上下文展示、预算截断与 suggested_reads 推荐）。
  - 受控写入与补丁：`write_file`（单文件 5MB 上限、自动建目录）、`apply_patch`（单处唯一匹配安全替换）。
  - 版本控制与 Shell：`git_diff`（工作区 Git 差异查看）、`execute_command`（短时受控 Shell 命令执行、输出截断与实时取消）。
  - `CodeSearchGoldenSetTest` 评测集与 `LocalToolsCodingLoopTest` 真实 Agent 循环集成测试。
- Phase 02 ReAct Agent：结构化 Tool Call/Observation、按原顺序的调用执行与调用 ID 关联回灌。
- 演示工具；参数 Schema 校验、结果预算、结构化工具失败和安全摘要。
- 最大迭代、600 秒整体超时、Ctrl+C 取消、一次空响应重试、连续三轮重复无进展保护，以及终态后禁止启动新的模型或工具工作。
- 统一 `RunEvent` 和 Plain 终端渲染；事件及渲染输出不包含 reasoning 或未脱敏凭据。

### Verification

- `./mvnw test` 全量通过 187 项自动化测试（覆盖 Phase 04 安全策略、HITL 审批、审计日志、所有 8 个本地工具、两个搜索引擎、Golden Set 评测集及完整 ReAct 工具循环）。

## [0.2.0] - 2026-08-26

### Added

- DeepSeek 流式终端对话、进程内多轮历史和 Token 用量展示。
- `/help`、`/config`、`/clear`、`/exit` 与响应期间 Ctrl+C 取消。
- `.env`、环境变量、用户 JSON 和命令行参数的安全配置分层。
- OpenAI-compatible SSE 解析、错误分类、超时和受控一次重试。
- 50 项离线自动测试、可执行 shaded JAR 和 macOS CI。

### Security

- API Key 不接受命令行参数或用户 JSON 持久化；终端、错误和 DEBUG 元数据统一脱敏。

### Verification

- Phase 01 通过真实 DeepSeek 五轮会话、Ctrl+C 取消恢复、演示脱敏和 macOS Java 21 CI 验收。

## [0.1.0] - 2026-08-25

### Added

- Java 21 Maven Wrapper 工程基线。
- 可执行 XhlCLI JAR，以及默认说明、`--help` 和 `--version` 命令。
- CLI 行为自动测试和 GitHub Actions Java 21 构建验证。
- Phase 00–18 产品需求、全局技术设计、路线图和协作规范。

[Unreleased]: https://github.com/sz-xiaohuolong/XhlCli/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/sz-xiaohuolong/XhlCli/releases/tag/v0.2.0
[0.1.0]: https://github.com/sz-xiaohuolong/XhlCli/releases/tag/v0.1.0
