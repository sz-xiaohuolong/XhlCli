# Phase 19：Team 自动选择与重规划实施计划

需求：[PRD](../prd/phase-19-team-routing-and-replanning.md)；方案：[设计](../specs/2026-11-06-phase-19-team-routing-and-replanning-design.md)。实际状态由 [PROGRESS](../PROGRESS.md) 维护，结果见 [验收](../engineering/team-routing-and-replanning-verification.md)。

## 1. 前提与命令

用户已授权实现、测试与同步 origin/main。沿用 Java 21、Maven、现有配置与安全执行器；本次不处理 v1.1 的全部修复，不打 Tag 或部署。测试使用假模型和临时工作区，不读取真实 Key。

## 2. 文件映射

| 文件范围 | 变化 |
|---|---|
| config/ChatConfig、ChatConfigLoader、ConfigKey、TeamMode | 模式配置与兼容构造函数 |
| team/TeamRouter | 无工具分类与降级 |
| cli/ChatLoop、ChatBootstrap、命令及帮助 | 自动入口、手动兼容、上下文与快照 |
| team/TeamOrchestrator、SubAgent、规划校验与工作区证据组件 | 失败评估、修订、成果保留 |
| agent/ReactAgent | 接收 Team 外部完成回合 |
| cli/terminal/SafeHistory | 修复实际终端验收发现的加载时机与重复追加，保留输入历史格式 |
| scripts/team_acceptance_demo.py | 本地假模型驱动真实 JAR 的可复现验收 |
| src/test/java 下对应模块 | 行为回归、并发取消与有界重规划 |
| docs/prd、specs、plans、engineering 与现有索引 | 权威文档及验收入口 |

## 3. 任务与依赖

| 任务 | 交付与步骤 | 依赖 | 验收 |
|---|---|---|---|
| TASK-19-01 | 写配置/路由失败测试；实现开关、无工具分类与 CLI 入口 | 技术基线 | AC-01～03 |
| TASK-19-02 | 写计划修订、图校验失败测试；实现两次上限和晚期局部约束 | 现有 Team | AC-04～06 |
| TASK-19-03 | 写真实文件、工具拒绝、并发与取消测试；接通证据与上下文 | 01、02 | AC-07～09 |
| TASK-19-04 | 针对性审查、全套测试、打包和 CLI 冒烟，补人工验收 | 01～03 | AC-10 及回归 |
| TASK-19-05 | 核对差异与敏感信息，提交并同步 origin/main，核对远程提交 | 04 | Git 同步证据 |

每项先失败测试，再最小实现，通过后推进。相同输入未改变时复用已有结果。

## 4. 验证矩阵

路由：ChatLoopTest、ChatConfigLoaderTest、TeamRouterTest。重规划：TeamReplanningTest 与既有 TeamOrchestratorTest。文件证据：临时工作区测试。全量 ./mvnw test；构建 ./mvnw package -DskipTests；CLI --help/--version 及离线交互。

## 5. 交付与恢复

在 main 上按已确认范围提交。保留本会话此前文档归并，避免只同步删除而漏掉新正文。先 fetch 核对远程，必要时仅合并可理解的变化；不强推。同步后读取远程 SHA。失败时停在实际阻塞并保留工作，不宣称推送成功。

## 6. 里程碑

模式选择可用 → 失败换计划可用 → 权限/证据/上下文闭环 → 验证与远程同步。实际结果只记录在验收文档。
