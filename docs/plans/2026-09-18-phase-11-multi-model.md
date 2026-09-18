# Phase 11：多模型路由与能力声明实施路线

> **对应设计：** `docs/specs/2026-09-18-phase-11-multi-model-design.md`  
> **评测物证：** `docs/engineering/multi-model-evaluation.md`

## 1. 交付阶段概览

- 对应里程碑：M3 开放生态与产品体验
- 版本升级：`0.8.0-SNAPSHOT` $\rightarrow$ `0.9.0-SNAPSHOT`
- 交付 Git Tag：`v0.9.0`

## 2. 详细任务执行清单

- [x] **Task 1: 核心领域模型与能力声明 (`com.xhlcli.llm`)**
  - [x] 实现 `com.xhlcli.llm.ModelCapabilities`（窗口、工具、视觉、Prompt Cache、Reasoning 属性）
  - [x] 扩展 `com.xhlcli.llm.LlmClient`（`providerName()`, `modelName()`, `capabilities()`）
- [x] **Task 2: 多协议适配器层实现 (`com.xhlcli.llm`)**
  - [x] 重构 `AbstractOpenAiCompatibleClient`，支持通用 Provider 标识与脱敏
  - [x] 更新 `DeepSeekClient` 实现新接口与能力声明（128k 窗口、Tools、Prompt Cache）
  - [x] 实现 `OpenAiClient`（标准 `/v1/chat/completions`、128k 窗口、Vision、Tools）
  - [x] 实现 `AnthropicSseParser` 与 `AnthropicClaudeClient`（原生 `/v1/messages` 协议、顶级 `system` 拆分、`input_schema`、`tool_use`/`tool_result` 块、200k 窗口）
  - [x] 实现 `OllamaClient`（本地 `/api/chat` NDJSON 逐行流式解析、免 API Key、32k 窗口）
- [x] **Task 3: Provider 注册中心与模型工厂 (`com.xhlcli.llm`)**
  - [x] 实现 `com.xhlcli.llm.ModelDescriptor`
  - [x] 实现 `com.xhlcli.llm.LlmProviderRegistry`（多 Provider 管理、环境凭据探测、别名模糊匹配与工厂实例化）
- [x] **Task 4: Agent 与上下文预算联动**
  - [x] 扩展 `TokenBudget`，增加 `updateContextWindow` 动态重算分层配额
  - [x] 更新 `ReactAgent`，支持 `setClient` 动态切换与 `supportsTools` 能力守卫
  - [x] 更新 `PlanExecuteAgent` 与 `Planner`，支持 `setClient` 与能力守卫
  - [x] 更新 `SubAgent` 与 `TeamOrchestrator`，支持 `setClient` 与能力守卫
  - [x] 更新 `ConversationHistoryCompactor`，支持 `setClient`
- [x] **Task 5: 终端交互命令集成 (`com.xhlcli.cli`)**
  - [x] 扩展 `ChatCommand.java` 与 `ChatCommandParser.java` 支持 `/model`
  - [x] 改造 `ChatLoop.java` 实现 `/model list`, `/model use <model>`, `/model status` 并支持历史超额预警
  - [x] 优化 `PlainRunRenderer.java` 帮助手册
  - [x] 更新 `ChatBootstrap.java` 初始化 `LlmProviderRegistry` 并绑定到运行时
- [x] **Task 6: 契约测试与单元测试套件**
  - [x] 编写 `LlmProviderContractTest.java`（同源验证 DeepSeek / OpenAI / Anthropic 文本流、工具流与错误分类）
  - [x] 编写 `AnthropicClaudeClientTest.java`（系统词拆分、报文双向转化、非重试/重试异常）
  - [x] 编写 `OllamaClientTest.java`（NDJSON 流式解析、本地免 Key 验证）
  - [x] 编写 `LlmProviderRegistryTest.java`（别名解析、配置探测、工厂创建）
  - [x] 编写 `ModelSwitchingIntegrationTest.java`（运行时切换、预算动态调整、不支持工具拦截）
  - [x] 全量 305 项自动化测试 100% 绿灯通过
- [x] **Task 7: 交付与文档闭环 (DoD 铁律)**
  - [x] 产出封存制品：`docs/specs/2026-09-18-phase-11-multi-model-design.md`, `docs/plans/2026-09-18-phase-11-multi-model.md`, `docs/engineering/multi-model-evaluation.md`
  - [x] 更新 Living Docs: `docs/PROJECT.md`, `docs/TECH_DESIGN.md`, `docs/ROADMAP.md`, `docs/plans/README.md`, `docs/specs/README.md`, `CHANGELOG.md`
  - [x] 升级 `pom.xml` 为 `0.9.0-SNAPSHOT`，Git Commit、打 Tag `v0.9.0` 并 Push
