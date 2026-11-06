# XhlCLI 项目全局进度 (Progress)

| 范围 | 当前事实 | 阻塞/未验证 | 下一步 |
|---|---|---|---|
| 桌面 MVP | 核心适配、独立审查和 GUI 验收完成；620 项 Java、2 项 Node、前端构建及本机打包通过 | 真实 Provider、其他硬件和部分故障分支未覆盖，见[验收边界](engineering/desktop-mvp-verification.md) | 按既有授权提交推送 origin/main，核对远程 SHA 与 CI |
| 前次交付 | 记忆召回、后台 task、技能入口、Phase 19 的原有交付保留 | 各自验证范围沿用历史记录 | 下方前次任务记录及 PROJECT 索引 |
| 后续 v1.1 | 可信任务生命周期仍为独立规划，未整体交付 | D01 等待设计核实 | 按原计划推进，与本次桌面交付分开 |

---

## 本次任务：桌面 MVP

2026-11-06，Shipping / READY_TO_SHIP。用户已冻结 macOS 普通编程核心闭环并授权开发、桌面验证及推送；本次交付以[桌面需求](prd/desktop-codex-ui.md)为准。核心离线闭环 VERIFIED，完整 AC 的边缘故障、真实模型质量与其他平台为 PARTIAL；逐项证据和人工步骤见[验收记录](engineering/desktop-mvp-verification.md)。

| 任务 | 当前状态 | 结果入口 |
|---|---|---|
| D1 | VERIFIED | Java 桌面适配、历史、审批、流式、模型配置及附件，见验收矩阵 |
| D2 | VERIFIED | Side-History 差异与保守撤销，含用户编辑、索引及符号链接回归 |
| D3 | VERIFIED | Electron/React 主界面、设置、桥接构建与最终 `.app` 启动 |
| D4 | VERIFIED（表列范围） | GUI 核心流程、截图、启动说明及人工验收步骤已记录 |
| D5 | IN_PROGRESS | 全量回归和独立复核完成；待提交、推送及远程核验 |

## 前次任务记录

长期记忆召回第一版：Reflecting / 本次代码交付完成，离线功能 VERIFIED，真实模型质量未验证。TASK-M01～05 完成；`./mvnw verify` 604 项通过，真实 JAR 验收 4 轮恰好 4 次本地模型请求。30 题样例命中 28/30，无关条目 570 → 2，平均记忆 Token 386 → 62；保留同义漏召回限制。实现提交 `730810de464733f30a4179ef1d99629a3a431ed7` 已同步 origin/main，远程 SHA 一致；GitHub CI `42198339753` 的 Verify 与 JAR 冒烟成功。不打 Tag、不发版本。设计、人工步骤和证据见[验收](engineering/memory-recall-verification.md)。

2026-11-06 显式技能调用入口修复：仅在未知内置命令分支查询技能注册表，复用 `LoadSkillTool` 读取正文，再进入现有任务链路。已覆盖禁用/损坏拒绝、缺少任务提示、同名内置命令优先、大小写与多行输入、重复调用、项目覆盖与热重载。接入远程后台任务更新前，`./mvnw test -Dtest=SkillPromptCliTest,ChatLoopTest,ChatCommandParserTest,LoadSkillToolTest,SkillRegistryTest` 34 项通过；`./mvnw test` 559 项通过（0 失败、0 错误、0 跳过）。README、终端帮助及 TECH_DESIGN 已同步；未打包或发布。 用户授权以 `feat` 提交同步 `origin/main`，接入远程更新后不重复测试。

task：功能开发、独立审查与真实 JAR 离线验收完成；`./mvnw verify` 575 项通过。实现提交 `252d37df8356d848804b0d9adc929b1f4df534f5` 已推送 origin/main，远程 CI `42190335434` 成功；不打 Tag、不发布版本。原工作区的其他未提交修改保留，交付代码位于独立工作区。最新终端验收及同步状态见 [task 交付证据](engineering/task-background-lifecycle-verification.md)。

前次 Phase 19：Reflecting / 本次代码交付完成，功能 VERIFIED（离线范围）。TASK-19-01～05 已完成。需求 FROZEN，用户已授权同步 origin/main；不打 Tag 或发布新版本。实现提交 `2cbd7e0c13b13d7ab8cc5c861896c20b956030e8` 已推送，远程 SHA 核对一致。成果、失败边界、人工步骤与证据见 [本次验收](engineering/team-routing-and-replanning-verification.md)。

## 1. 后续 v1.1 独立规划基线

- **Current Release**: `v1.1`（目标版本 `v1.1.0`，可信任务生命周期）
- **Published Baseline**: `v1.0.1`（用户全局配置支持 API Key）
- **Current Stage / Workflow State**: `Planning / SPECIFIED`（范围基线已确定，设计与计划为提案，尚未进入 Building）
- **Operational Status**: `ACTIVE`（文档归并完成，下一步为技术能力核实与具体方案评审）
- **Verification Status / Shipping Authorization**: `UNVERIFIED / NOT_REQUESTED`（针对 v1.1 功能）
- **Current Task / Next Task（v1.1 独立规划）**: 文档归并与维护规则更新已完成；后续核对 `TASK-01`；独立 task 交付已覆盖部分 REQ-02～04，不替代完整 Release 计划
- **Last Stable Commit**: `690a4e17578369c62eef04b5b426e994b8b1d153`
- **Last Stable Tag**: `v1.0.0` (Commit: `2741d36`)
- **Working Tree**: 当前任务为桌面 MVP 适配；记忆召回、后台任务及技能入口的已提交成果保留。完整 v1.1 仍未交付，Git 状态以本次同步结果核验。
- **Last Audited Date**: v1.1 研究基线为 2026-10-16；Phase 19 的实际验证另见本次验收。
- **Document Map**: [`docs/PROJECT.md`](PROJECT.md)
- **Architecture Baseline**: [`docs/TECH_DESIGN.md`](TECH_DESIGN.md)
- **Changelog**: [`CHANGELOG.md`](../CHANGELOG.md)

---

## 2. 演进阶段交付矩阵 (Phase Delivery Matrix)

| 阶段 / Release | 交付范围 | 状态 | 核心物证 / 验证方式 |
|---|---|---|---|
| **Phase 00** (`v0.1.0`) | Java 21 工程基线、CLI 元信息与 CI 门禁 | `VERIFIED` | 自动化单测与 CI 基线 |
| **Phase 01** (`v0.2.0`) | 终端流式对话、安全配置、脱敏与 DeepSeek 适配 | `VERIFIED` | 演示 Demo GIF 与真实 5 轮会话 |
| **Phase 02** (`v0.2.0`) | 受控 ReAct 循环、Tool Call/Observation 与终止控制 | `VERIFIED` | MockWebServer 契约与单元测试 |
| **Phase 03** (`v0.2.0`) | 9 个本地开发工具（文件读写、补丁、命令执行、代码搜索、Diff） | `VERIFIED` | 工具套件单元与集成测试 |
| **Phase 04** (`v0.2.0`) | 路径越界保护 `WorkspacePathResolver`、CommandGuard、审计日志与 HITL 审批 | `VERIFIED` | 安全沙箱测试与人机审批模拟 |
| **Phase 05** (`v0.3.0`) | Token 预算、层级上下文组装、长期记忆与对话压缩 | `VERIFIED` | 记忆持久化与压缩金标测试 |
| **Phase 06** (`v0.4.0`) | Ripgrep/Java 双引擎精准检索、代码探索流水线与 `/search-text` | `VERIFIED` | Code Search Golden Set (8/8) |
| **Phase 07** (`v0.5.0`) | SQLite + AST 增量语义索引、混合加权检索、`/index`、`/search` | `VERIFIED` | Codebase RAG 评测物证 |
| **Phase 08** (`v0.6.0`) | DAG 拓扑分层调度、Kahn 算法批次、HITL 审阅交互与 `/plan` | `VERIFIED` | Plan & Execute 评测报告 |
| **Phase 09** (`v0.7.0`) | 有界受控并发调度 (Bounded Parallelism)、乱序保序归并、超时与取消传播 | `VERIFIED` | 并行执行基准 Benchmark |
| **Phase 10** (`v0.8.0`) | Multi-Agent 协作团队（1+2+1 体系、审查熔断、Worker 池与 `/team`） | `VERIFIED` | Multi-Agent 评测报告 |
| **Phase 11** (`v0.9.0`) | 多模型路由与能力声明（OpenAI/Anthropic/Ollama、ModelCapabilities、`/model`） | `VERIFIED` | 多模型路由评测报告 |
| **Phase 12** (`v0.10.0`) | MCP 协议集成、双通道传输、工具发现与调用、`/mcp` | `VERIFIED` | MCP 扩展评测报告 |
| **Phase 13** (`v0.11.0`) | SSRF 围栏、多搜索引擎抽象、受限正文抽取、CDP 浏览器控制台与 `/browser` | `VERIFIED` | Web & Browser 评测报告 |
| **Phase 14** (`v0.12.0`) | 8 层提示词装配、系统规则不可变、三层技能注册中心、`/skill` `/prompt` | `VERIFIED` | Skill & Prompt 评测报告 |
| **Phase 15** (`v0.13.0`) | JLine 4 动态状态栏、双模渲染、WCWidth 对齐、代码框与 Diff 着色、`/history` | `VERIFIED` | 终端产品化交互评测报告 |
| **Phase 16** (`v0.14.0`) | JGit 物理隔离快照还原、零宿主污染、自愈工具 `revert_turn`、`/snapshot` `/restore` | `VERIFIED` | 快照与版本恢复评测报告 |
| **Phase 17A** (`v0.15.0`) | SQLite 后台持久任务队列、崩溃自愈、Localhost Runtime API、SSE 游标流 | `VERIFIED` | Runtime & Task 评测报告 |
| **Phase 17B** (`v0.15.1`) | 图片多模态上下文预处理、Alpha Flatten、缩放压缩、剪贴板抓图与视觉防御护栏 | `VERIFIED` | Multimodal Golden Test 物证 |
| **Phase 18** (`v1.0.0`) | 现代化静态官网 (`website/`)、终端模拟器、指令速查表、Vercel 部署、`install.sh` | `VERIFIED` | 静态官网评测报告与发版 |
| **Patch 1.0.1** (`v1.0.1`) | 全局用户配置 `~/.xhlcli/config.json` 支持 API Key 与多 Provider 级联 | `VERIFIED` | 525 项测试全绿，jar 冒烟通过 |
| **Phase 19** | 自动 Team、失败重规划、结果上下文交接与输入历史修复 | `VERIFIED`（离线验收，已同步远程） | [需求](prd/phase-19-team-routing-and-replanning.md) → [验收](engineering/team-routing-and-replanning-verification.md) |
| **v1.1**（目标 `v1.1.0`） | R01～R07 修复、能力声明校准 | `SPECIFIED`；设计 `PROPOSED`，计划 `DRAFT` | [需求](prd/v1.1-trusted-task-lifecycle.md) → [设计](specs/2026-10-16-v1.1-trusted-task-lifecycle-design.md) → [计划](plans/2026-10-16-v1.1-trusted-task-lifecycle.md) → [待执行验收](engineering/v1.1-trusted-task-lifecycle-verification.md) |

---

## 3. 历史交付与当前证据

task 的最新结果见 [task 证据](engineering/task-background-lifecycle-verification.md)。Phase 19 的打包与 CLI 验收单独记录于[本次证据](engineering/team-routing-and-replanning-verification.md)。下表为历史记录，不覆盖本次源码；v1.1 功能结果以[本批验收矩阵](engineering/v1.1-trusted-task-lifecycle-verification.md)为准。历史阶段 VERIFIED 表示当时验收，不覆盖后续发现的 R01～R07。

| 检查项 | 命令 / 流程 | 结果 | 耗时 / 细节 | 证据位置 |
|---|---|---|---|---|
| **2026-10-16 基线测试** | `./mvnw test` | `525 Tests, 0 Failures, 0 Errors, 0 Skipped` | 16.160s；适用于当时的基线提交 | [验证记录](engineering/research-2026-10-16/verification.md)与日志摘要 |
| **历史 Jar 打包与 Shade** | `./mvnw clean package -DskipTests` | `BUILD SUCCESS` | 生成 `target/xhlcli-1.0.1.jar` | `target/` |
| **历史 CLI 启动与元信息** | `java -jar target/xhlcli-1.0.1.jar --version` | `XhlCLI 1.0.1` | 冒烟通过 | 标准输出 |
| **历史一键安装器测试** | `bash install.sh` | 成功安装并输出快捷配置指导 | 本地验证通过 | `~/.xhlcli/bin/xhlcli` |

---

## 4. 后续 v1.1 行动指南（独立于本次交付）

1. 沿用用户已明确的第一批范围，不再启动需求访谈；入口为 [v1.1 目标](prd/v1.1-trusted-task-lifecycle.md)。
2. 执行 TASK-01 的目录操作能力验证与旧库迁移条件调查，评审设计 D01/D02；未闭合前保留提案，不迁移真实任务库或进入完整 Release Building。
3. 按 TASK-02～TASK-08 推进小闭环，逐项将 R01～R07 转成正式失败回归，补能力声明证据；其中 task 子集已有独立交付，其他部分未开始。
4. TASK-09 汇总适用证据，满足所有 AC 后再评估验证与发布状态。报告第二、三批仍不属于 v1.1，v1.1 尚未整体发布；本轮 task 已获提交、推送授权，不升级版本。后续继续使用原分类目录，以 PROJECT 为唯一文档地图、PROGRESS 为唯一当前进度。
