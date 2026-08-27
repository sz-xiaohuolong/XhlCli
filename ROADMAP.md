# XhlCLI 路线图

路线图描述交付顺序，不代表计划能力已经实现。状态以源码、测试、CHANGELOG 和发布记录为准。

| 优先级 | 阶段 | 状态 | 交付目标 |
|---|---|---|---|
| P0 | [Phase 00](docs/prd/phase-00-project-foundation.md) | 已交付（2026-08-25） | Java 21 工程、CLI 元信息、测试与 CI 基线 |
| P0 | [Phase 01](docs/prd/phase-01-terminal-chat.md) | 已交付（2026-08-26） | 流式终端对话与首个模型 Provider |
| P0 | [Phase 02](docs/prd/phase-02-react-agent.md) | 已交付（2026-08-27） | 受控 ReAct 执行循环、结构化 Tool Call/Observation 与终止控制；仅含演示工具 |
| P0 | [Phase 03](docs/prd/phase-03-local-tools.md) | 规划中 | 文件、搜索、命令与 Git 本地工具 |
| P0 | [Phase 04](docs/prd/phase-04-safety-and-approval.md) | 规划中 | 风险策略、人工审批与审计 |
| P0 | [Phase 05](docs/prd/phase-05-context-and-memory.md) | 规划中 | 上下文预算与分层记忆 |
| P0 | [Phase 06](docs/prd/phase-06-code-search.md) | 规划中 | 结构化代码检索闭环 |
| P1 | [Phase 07](docs/prd/phase-07-codebase-rag.md) | 规划中 | 代码切分、向量检索与上下文组装 |
| P1 | [Phase 08](docs/prd/phase-08-plan-and-execute.md) | 规划中 | Plan 模式与任务执行 |
| P1 | [Phase 09](docs/prd/phase-09-parallel-execution.md) | 规划中 | 可控并行执行 |
| P1 | [Phase 10](docs/prd/phase-10-multi-agent.md) | 规划中 | Multi-Agent 调度与汇总 |
| P1 | [Phase 11](docs/prd/phase-11-multi-model.md) | 规划中 | 多模型路由与能力声明 |
| P1 | [Phase 12](docs/prd/phase-12-mcp.md) | 规划中 | MCP 工具发现与调用 |
| P2 | [Phase 13](docs/prd/phase-13-web-and-browser.md) | 规划中 | Web 检索与浏览器能力 |
| P2 | [Phase 14](docs/prd/phase-14-skills-and-prompts.md) | 规划中 | Skill 与 Prompt 管理 |
| P2 | [Phase 15](docs/prd/phase-15-terminal-productization.md) | 规划中 | 终端交互产品化 |
| P2 | [Phase 16](docs/prd/phase-16-lsp-and-snapshots.md) | 规划中 | LSP、诊断与快照 |
| P2 | [Phase 17](docs/prd/phase-17-runtime-and-multimodal.md) | 规划中 | Runtime API 与图片输入 |
| P2 | [Phase 18](docs/prd/phase-18-open-source-release.md) | 规划中 | 开源发行与发布工程 |

## 状态定义

- 规划中：已有 PRD，尚未进入实现。
- 设计完成：本期边界与方案已明确，尚未交付代码。
- 发布验证中：实现已存在，正在完成阶段级验收。
- 已交付：代码、测试、文档和阶段验收均已完成。
