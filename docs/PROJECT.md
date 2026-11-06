# XhlCLI 项目定位与资产映射 (Project & Document Map)

> 本文档遵循 `vibe-workflow` 约定，作为项目全局资产索引导航（Document Map）。
> **定位准则**：索引只导航与映射职责，不承载产品或技术实现结论。

---

本次后台任务交付：[实施计划](plans/2026-11-06-task-background-lifecycle.md) · [用户能力、人工验收与业务流程](engineering/task-background-lifecycle-verification.md)。

## 1. 当前项目定位 (Current Positioning)

- **Project Name**: XhlCLI (小火龙终端 Coding Agent)
- **本次实施**：[主对话长期记忆召回](prd/memory-recall.md)；当前状态与验证见 [PROGRESS](PROGRESS.md)。
- **后续独立规划**: [`v1.1` 可信任务生命周期](prd/v1.1-trusted-task-lifecycle.md)（目标版本 `v1.1.0`；第一批范围已确定，设计与实施计划已形成提案，尚未完整实现；后台只读任务子集已单独交付）
- **Published Baseline**: `v1.0.1`（全局 `~/.xhlcli/config.json` 支持 API Key 与多 Provider 凭据映射，2026-10-07）
  - 基线：Phase 18 现代化静态官网 (`website/`)、交互式终端模拟器、全量指令速查、Vercel 部署配置与跨平台 `install.sh`
- **Quality Profile**: 沿用离线单元、集成、契约与终端验收；本次结果见 [记忆召回验收](engineering/memory-recall-verification.md)。2026-10-16 发现的 v1.1 缺陷仍按其独立验收矩阵追踪。
- **Product Summary**: 基于 Java 21 与本地优先原则构建的高性能轻量终端 Coding Agent，具备受控 ReAct、安全沙箱围栏、AST 代码库 RAG、DAG 规划执行、多 Agent 协同、Side-History 隔离快照恢复、后台持久任务/Runtime API 与图片多模态上下文预处理/视觉防御护栏。
- **Primary Users**: 后端及全栈软件工程师，偏好在终端高效完成跨文件重构、代码探索与工程任务。

---

## 2. 事实源映射矩阵 (Document Map)

按职责区分持续演进事实源、当前 Release 制品和已发布阶段封存制品：

### 2.1 持续演进事实源 (Living Documents)

| 职责类别 | 实际路径 | 事实所有权与说明 |
| :--- | :--- | :--- |
| **Agent 行为准则与入口** | [`AGENTS.md`](../AGENTS.md) | 用户指定的通用工作约定、事实源入口与修改边界 |
| **项目定位与资产映射** | [`docs/PROJECT.md`](PROJECT.md) | 当前定位、Release 状态、文档地图与追溯索引 |
| **项目全局执行进度** | [`docs/PROGRESS.md`](PROGRESS.md) | 当前 Workflow 状态、Release 状态、Last Stable Commit 与验证物证 |
| **真实架构事实源** | [`docs/TECH_DESIGN.md`](TECH_DESIGN.md) | 现有全局设计与实现记录；当前行为结合代码和审查证据核验，v1.1 目标方案单独维护 |
| **总览需求与边界** | [`docs/PRD.md`](PRD.md) | 总体产品定位、能力矩阵与跨阶段规划 |
| **路线图与交付状态** | [`docs/ROADMAP.md`](ROADMAP.md) | 历史阶段交付状态与当前 v1.1 规划边界 |
| **技术预研与竞品分析** | [`docs/RESEARCH.md`](RESEARCH.md) | 竞品分析 (Claude Code, Cline 等) 与关键技术选型 |
| **2026-10-16 源码审查与竞品证据** | [专项报告](engineering/xhlcli-claude-qoder-review-2026-10-16.md) | 当前代码缺陷、Claude Code / Qoder 指定发行物分析、验证记录与候选路线；报告中的第一批已纳入 v1.1，其余仍为候选 |
| **参考源码迁移约定** | [源码采用映射](engineering/source-adoption-map.md) | 涉及授权参考源码时读取其边界、来源与署名规则 |
| **版本演进履历** | [`CHANGELOG.md`](../CHANGELOG.md) | 基于 Keep a Changelog 规范记录版本变更详情 |

### 2.2 按职责保存的正文

| 权威目录 | 正文职责 | 维护约定 |
|---|---|---|
| [`docs/prd/`](prd/) | 功能目标、范围、用户行为、约束与 REQ/AC | 可在同一文件合并 Brief 与 SPEC 职责，不重复写产品正文 |
| [`docs/specs/`](specs/) | 尚未实现的目标方案、影响、取舍及批准状态 | 当前方案与历史设计同目录保存，文件名带功能及必要版本/日期 |
| [`docs/plans/`](plans/) | 实施任务、依赖、测试接缝与完成条件 | 小任务复用已有记录；不为每个切片新建文档套件 |
| [`docs/engineering/`](engineering/) | 调研、评测、逐项验收与必要证据 | 区分原历史摘要、实测通过与未验证项，不补造运行结果 |

使用 `vibe-workflow` 继续开发时沿用以上目录。项目规则见 [AGENTS](../AGENTS.md)，当前执行事实见 [PROGRESS](PROGRESS.md)；版本只通过下表关联正文，不复制材料到另一套目录。

长期记忆召回：[需求](prd/memory-recall.md) · [设计](specs/2026-11-06-memory-recall.md) · [计划](plans/2026-11-06-memory-recall.md) · [验收与评测](engineering/memory-recall-verification.md)。

前次 Phase 19：[需求](prd/phase-19-team-routing-and-replanning.md) · [设计](specs/2026-11-06-phase-19-team-routing-and-replanning-design.md) · [实施计划](plans/2026-11-06-phase-19-team-routing-and-replanning.md) · [验收与使用说明](engineering/team-routing-and-replanning-verification.md)。

后续独立规划 v1.1：[需求与验收条件](prd/v1.1-trusted-task-lifecycle.md) · [拟议设计](specs/2026-10-16-v1.1-trusted-task-lifecycle-design.md) · [实施计划](plans/2026-10-16-v1.1-trusted-task-lifecycle.md) · [验收矩阵](engineering/v1.1-trusted-task-lifecycle-verification.md)。

---

## 3. 版本与文档追溯

需求、设计、计划与验收各保留一个正文位置。历史文件中的“提案中”“待批准”等文字保留当时写作状态，交付归属结合本表和原有证据解释；本次整理不回写历史批准或测试结论。早期四份简要验收记录已[合并保存并说明证据限制](engineering/historical-release-verification.md)。

| 阶段 / 版本 | 需求 | 设计 | 实施计划 | 验收记录 | 发布标识 |
|---|---|---|---|---|---|
| **v0.1** Phase 00 工程骨架 | [需求](prd/phase-00-project-foundation.md) | [原基线说明](engineering/historical-release-verification.md) | [计划](plans/2026-08-25-phase-00-project-foundation.md) | [验收](engineering/historical-release-verification.md) | v0.1.0 |
| **v0.2** Phase 01～04 对话、Agent、工具与审批 | [Phase 01](prd/phase-01-terminal-chat.md)、[Phase 02](prd/phase-02-react-agent.md)、[Phase 03](prd/phase-03-local-tools.md)、[Phase 04](prd/phase-04-safety-and-approval.md) | [Phase 01](specs/2026-08-26-phase-01-terminal-chat-design.md)、[Phase 02](specs/2026-08-27-phase-02-react-agent-design.md)、[Phase 03](specs/2026-08-29-phase-03-local-tools-design.md)、[Phase 04](specs/2026-08-30-phase-04-safety-and-approval-design.md) | [Phase 01](plans/2026-08-26-phase-01-terminal-chat.md)、[Phase 02](plans/2026-08-27-phase-02-react-agent.md)、[Phase 03](plans/2026-08-29-phase-03-local-tools.md)、[Phase 04](plans/2026-08-30-phase-04-safety-and-approval.md) | [验收](engineering/historical-release-verification.md) | v0.2.0 |
| **v0.3** Phase 05 上下文与记忆 | [需求](prd/phase-05-context-and-memory.md) | [设计](specs/2026-09-02-phase-05-context-and-memory-design.md) | [计划](plans/2026-09-02-phase-05-context-and-memory.md) | [验收](engineering/historical-release-verification.md) | v0.3.0 |
| **v0.4** Phase 06 代码检索 | [需求](prd/phase-06-code-search.md) | [设计](specs/2026-09-07-phase-06-code-search-design.md) | [计划](plans/2026-09-07-phase-06-code-search.md) | [验收](engineering/code-search-golden-set.md) | v0.4.0 |
| **v0.5** Phase 07 代码库 RAG | [需求](prd/phase-07-codebase-rag.md) | [设计](specs/2026-09-10-phase-07-codebase-rag-design.md) | [计划](plans/2026-09-10-phase-07-codebase-rag.md) | [验收](engineering/codebase-rag-evaluation.md) | v0.5.0 |
| **v0.6** Phase 08 规划执行 | [需求](prd/phase-08-plan-and-execute.md) | [设计](specs/2026-09-11-phase-08-plan-and-execute-design.md) | [计划](plans/2026-09-11-phase-08-plan-and-execute.md) | [验收](engineering/plan-and-execute-evaluation.md) | v0.6.0 |
| **v0.7** Phase 09 并发执行 | [需求](prd/phase-09-parallel-execution.md) | [设计](specs/2026-09-14-phase-09-parallel-execution-design.md) | [计划](plans/2026-09-14-phase-09-parallel-execution.md) | [验收](engineering/parallel-execution-benchmark.md) | v0.7.0 |
| **v0.8** Phase 10 Multi-Agent | [需求](prd/phase-10-multi-agent.md) | [设计](specs/2026-09-16-phase-10-multi-agent-design.md) | [计划](plans/2026-09-16-phase-10-multi-agent.md) | [验收](engineering/multi-agent-evaluation.md) | v0.8.0 |
| **v0.9** Phase 11 多模型路由 | [需求](prd/phase-11-multi-model.md) | [设计](specs/2026-09-18-phase-11-multi-model-design.md) | [计划](plans/2026-09-18-phase-11-multi-model.md) | [验收](engineering/multi-model-evaluation.md) | v0.9.0 |
| **v0.10** Phase 12 MCP | [需求](prd/phase-12-mcp.md) | [设计](specs/2026-09-21-phase-12-mcp-design.md) | [计划](plans/2026-09-21-phase-12-mcp.md) | [验收](engineering/mcp-evaluation.md) | v0.10.0 |
| **v0.11** Phase 13 Web 与浏览器 | [需求](prd/phase-13-web-and-browser.md) | [设计](specs/2026-09-22-phase-13-web-and-browser-design.md) | [计划](plans/2026-09-22-phase-13-web-and-browser.md) | [验收](engineering/web-and-browser-evaluation.md) | v0.11.0 |
| **v0.12** Phase 14 Skill 与 Prompt | [需求](prd/phase-14-skills-and-prompts.md) | [设计](specs/2026-10-05-phase-14-skills-and-prompts-design.md) | [计划](plans/2026-10-05-phase-14-skills-and-prompts.md) | [验收](engineering/skill-and-prompt-evaluation.md) | v0.12.0 |
| **v0.13** Phase 15 终端交互 | [需求](prd/phase-15-terminal-productization.md) | [设计](specs/2026-10-06-phase-15-terminal-productization-design.md) | [计划](plans/2026-10-06-phase-15-terminal-productization.md) | [验收](engineering/terminal-productization-evaluation.md) | v0.13.0 |
| **v0.14** Phase 16 隔离快照 | [需求](prd/phase-16-lsp-and-snapshots.md) | [设计](specs/2026-10-07-phase-16-snapshot-and-recovery-design.md) | [计划](plans/2026-10-07-phase-16-snapshot-and-recovery.md) | [验收](engineering/snapshot-and-recovery-evaluation.md) | v0.14.0 |
| **v0.15** Phase 17A Runtime 与后台任务 | [需求](prd/phase-17-runtime-and-multimodal.md) | [设计](specs/2026-10-07-phase-17-runtime-and-multimodal-design.md) | [计划](plans/2026-10-07-phase-17-runtime-and-multimodal.md) | [验收](engineering/runtime-and-task-evaluation.md) | v0.15.0 |
| **v0.15.1** Phase 17B 多模态 | [需求](prd/phase-17-runtime-and-multimodal.md) | [设计](specs/2026-10-07-phase-17-runtime-and-multimodal-design.md) | [计划](plans/2026-10-07-phase-17-runtime-and-multimodal.md) | [验收](engineering/multimodal-evaluation.md) | v0.15.1 |
| **v1.0** Phase 18 官网与开源发布 | [需求](prd/phase-18-open-source-release.md) | [设计](specs/2026-10-07-phase-18-release-and-website-design.md) | [计划](plans/2026-10-07-phase-18-release-and-website.md) | [验收](engineering/release-and-website-evaluation.md) | v1.0.0 |
| **v1.0.1** 全局配置 API Key | [需求](prd/v1.0.1-user-config-api-key.md) | [设计](specs/2026-10-07-v1.0.1-user-config-api-key-design.md) | [计划](plans/2026-10-07-v1.0.1-user-config-api-key.md) | [验收](engineering/historical-release-verification.md) | v1.0.1 |
| **Phase 19** 自动 Team 与失败重规划 | [需求](prd/phase-19-team-routing-and-replanning.md) | [设计](specs/2026-11-06-phase-19-team-routing-and-replanning-design.md) | [计划](plans/2026-11-06-phase-19-team-routing-and-replanning.md) | [验收](engineering/team-routing-and-replanning-verification.md) | 代码同步，不打 Tag；pom 仍为 1.0.1 |
| **记忆召回第一版** | [需求](prd/memory-recall.md) | [设计](specs/2026-11-06-memory-recall.md) | [计划](plans/2026-11-06-memory-recall.md) | [验收](engineering/memory-recall-verification.md) | 代码同步，不打 Tag；pom 仍为 1.0.1 |
| **v1.1** 可信任务生命周期 | [需求](prd/v1.1-trusted-task-lifecycle.md) | [设计](specs/2026-10-16-v1.1-trusted-task-lifecycle-design.md) | [计划](plans/2026-10-16-v1.1-trusted-task-lifecycle.md) | [验收](engineering/v1.1-trusted-task-lifecycle-verification.md) | 未发布，目标 v1.1.0 |

---

## 4. 支持的工程命令 (Supported Commands)

| 目的 | 命令 | 备注 |
| :--- | :--- | :--- |
| **环境检查** | `java -version` | 必须为 Java 21+ (`export JAVA_HOME=/opt/homebrew/opt/openjdk`) |
| **全量自动化测试** | `./mvnw test` | 当前结果见 Phase 19 验收；v1.1 独立验收尚未执行 |
| **单测运行** | `./mvnw test -Dtest=<TestClass>` | 运行指定测试类 |
| **打包产物** | `./mvnw package -DskipTests` | 构建 `target/xhlcli-1.0.1.jar` 可执行 Fat JAR |
| **离线 Team 终端验收** | `python3 scripts/team_acceptance_demo.py` | 先打包；临时目录、本地假模型，无需真实 Key |
| **运行 CLI** | `java -jar target/xhlcli-1.0.1.jar` | 启动交互式终端 Agent |
