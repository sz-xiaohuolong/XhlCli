# Phase 10：Multi-Agent 协作架构实施路线

> **对应设计：** `docs/specs/2026-09-16-phase-10-multi-agent-design.md`  
> **评测物证：** `docs/engineering/multi-agent-evaluation.md`

## 1. 交付阶段概览

- 对应里程碑：M3 复杂任务与智能架构
- 版本升级：`0.7.0-SNAPSHOT` $\rightarrow$ `0.8.0-SNAPSHOT`
- 交付 Git Tag：`v0.8.0`

## 2. 详细任务执行清单

- [x] **Task 1: 角色模型与专职提示词体系**
  - [x] 实现 `com.xhlcli.team.TeamRole`（`PLANNER`, `WORKER`, `REVIEWER`）
  - [x] 实现 `com.xhlcli.team.TeamPrompts`（各角色定制化 System Prompt 与格式约束）
- [x] **Task 2: 上下文交接包与结构化审查模型**
  - [x] 实现 `com.xhlcli.team.HandoverPackage`（最小化任务描述、依赖摘要、验收标准）
  - [x] 实现 `com.xhlcli.team.ReviewResult`（JSON 解析与条目化/关键词安全容错降级）
  - [x] 编写并通过 `HandoverPackageTest`（2/2 绿灯）与 `ReviewResultTest`（7/7 绿灯）
- [x] **Task 3: 专职独立子代理 SubAgent**
  - [x] 实现 `com.xhlcli.team.SubAgent`（独立消息生命周期管理）
  - [x] 增加工具下发拦截（强制限制非 Worker 角色无法获得工具定义）
  - [x] 增加 `clearHistory` 历史重置支持
  - [x] 编写并通过 `SubAgentTest`（5/5 绿灯）
- [x] **Task 4: 团队编排器与重试熔断闭环**
  - [x] 实现 `com.xhlcli.team.TeamOrchestrator`，实现 `AgentRunner` 接口
  - [x] 实现 DAG 依赖分层推进与 Worker 池排他借用
  - [x] 实现独立内存流（`ByteArrayOutputStream`）日志缓冲与批次保序 flush
  - [x] 接入审查未通过打回 Worker 重试与熔断机制（`MAX_RETRIES = 2`）
  - [x] 实现 ASCII 交付汇总看板与长期记忆自动回写
  - [x] 编写并通过 `TeamOrchestratorTest`（5/5 绿灯）
- [x] **Task 5: CLI 交互集成与轻量降级保护**
  - [x] 更新 `ChatCommand.java` 与 `ChatCommandParser.java` 支持 `/team` 指令
  - [x] 更新 `ChatLoop.java` 与 `ChatBootstrap.java` 接入 `TeamOrchestrator`
  - [x] 增加极简轻量任务的降级保护提示
  - [x] 更新并通过 `ChatCommandParserTest` 与 `ChatLoopTest`（10/10 绿灯）
- [x] **Task 6: 评测报告、全量回归与版本发布**
  - [x] 编写工程评测报告 `multi-agent-evaluation.md`（记录 1+2+1 权限实测与上下文缩减 85%~95%）
  - [x] 全量 291 项自动化测试 100% 绿灯通过
  - [x] 升级 `pom.xml` 为 `0.8.0-SNAPSHOT`，更新 `CHANGELOG.md` 与 `AGENTS.md`
  - [x] Git Commit、打 Tag `v0.8.0` 并推送到 GitHub
