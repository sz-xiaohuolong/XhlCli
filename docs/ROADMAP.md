# XhlCLI 路线图

路线图描述交付顺序，不代表计划能力已经实现。状态以源码、测试、CHANGELOG 和发布记录为准。

| 优先级 | 阶段 | 状态 | 交付目标 |
|---|---|---|---|
| P0 | [Phase 00](prd/phase-00-project-foundation.md) | 已交付（2026-08-25，v0.1.0） | Java 21 工程、CLI 元信息、测试与 CI 基线 |
| P0 | [Phase 01](prd/phase-01-terminal-chat.md) | 已交付（2026-08-26，v0.2.0） | 流式终端对话与首个模型 Provider (DeepSeek) |
| P0 | [Phase 02](prd/phase-02-react-agent.md) | 已交付（2026-08-27，v0.2.0） | 受控 ReAct 执行循环、结构化 Tool Call/Observation 与终止控制 |
| P0 | [Phase 03](prd/phase-03-local-tools.md) | 已交付（2026-08-29，v0.2.0） | 9 个本地开发工具（文件读写、补丁、命令执行、代码搜索、Git Diff 等） |
| P0 | [Phase 04](prd/phase-04-safety-and-approval.md) | 已交付（2026-08-30，v0.2.0） | 工作区路径越界防护、CommandGuard、审计日志与终端人工审批 (HITL) |
| P0 | [Phase 05](prd/phase-05-context-and-memory.md) | 已交付（2026-09-02，v0.3.0） | Token 预算管理、层级上下文组装、长期记忆持久化与对话自动压缩 |
| P0 | [Phase 06](prd/phase-06-code-search.md) | 已交付（2026-09-07，v0.4.0） | Ripgrep/Java 双引擎精准检索、代码探索流水线、无结果建议与 `/search-text` |
| P1 | [Phase 07](prd/phase-07-codebase-rag.md) | 已交付（2026-09-10，v0.5.0） | SQLite + AST 增量语义索引、混合加权检索、`/index`、`/search` |
| P1 | [Phase 08](prd/phase-08-plan-and-execute.md) | 已交付（2026-09-11，v0.6.0） | DAG 拓扑分层调度、Kahn 算法批次、HITL 审阅交互、重排熔断保护与 `/plan` |
| P1 | [Phase 09](prd/phase-09-parallel-execution.md) | 已交付（2026-09-14，v0.7.0） | 有界受控并发调度 (Bounded Parallelism)、乱序执行保序归并、超时隔离与取消传播 |
| P1 | [Phase 10](prd/phase-10-multi-agent.md) | 已交付（2026-09-16，v0.8.0） | Multi-Agent 协作（1+2+1 体系、最小交接包、审查熔断循环、Worker 池并发与 `/team`） |
| P1 | [Phase 11](prd/phase-11-multi-model.md) | 已交付（2026-09-18，v0.9.0） | 多模型路由与能力声明（OpenAI/Anthropic/Ollama、ModelCapabilities、注册中心与 `/model`） |
| P1 | [Phase 12](prd/phase-12-mcp.md) | 已交付（2026-09-21，v0.10.0） | MCP 协议集成、双通道传输、工具发现与调用、生命周期管理与 `/mcp` |
| P2 | [Phase 13](prd/phase-13-web-and-browser.md) | 规划中 | Web 检索与浏览器能力 |
| P2 | [Phase 14](prd/phase-14-skills-and-prompts.md) | 规划中 | Skill 与 Prompt 管理 |
| P2 | [Phase 15](prd/phase-15-terminal-productization.md) | 规划中 | 终端交互产品化 |
| P2 | [Phase 16](prd/phase-16-lsp-and-snapshots.md) | 规划中 | LSP、诊断与快照 |
| P2 | [Phase 17](prd/phase-17-runtime-and-multimodal.md) | 规划中 | Runtime API 与图片输入 |
| P2 | [Phase 18](prd/phase-18-open-source-release.md) | 规划中 | 开源发行与发布工程 |

## 状态定义

- 规划中：已有 PRD，尚未进入实现。
- 设计完成：本期边界与方案已明确，尚未交付代码。
- 发布验证中：实现已存在，正在完成阶段级验收。
- 已交付：代码、测试、文档和阶段验收均已完成。
