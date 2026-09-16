# Phase 08：智能规划（Plan-and-Execute）实施路线

## 1. 交付阶段概览

- 对应里程碑：M2 复杂任务智能
- 版本升级：`0.5.0-SNAPSHOT` $\rightarrow$ `0.6.0-SNAPSHOT`
- 交付 Git Tag：`v0.6.0`

## 2. 详细任务执行清单

- [x] **Task 1: 计划核心领域模型与 DAG 算法引擎**
  - [x] 实现 `com.xhlcli.plan.Task`
  - [x] 实现 `com.xhlcli.plan.ExecutionPlan`（包含三色标记拓扑排序、有向环检测、Kahn 分层批次、ASCII 可视化与折叠摘要）
  - [x] 编写并通过 `com.xhlcli.plan.ExecutionPlanTest`（8/8 绿灯）
- [x] **Task 2: 规划器 Planner 与审阅指令解析**
  - [x] 实现 `com.xhlcli.plan.Planner`（支持简单意图识别短路、两遍建图处理前向引用、自适应重规划）
  - [x] 实现 `com.xhlcli.cli.PlanReviewInputParser`（执行、取消、补充约束指令解析）
  - [x] 编写并通过 `com.xhlcli.plan.PlannerTest` 与 `PlanReviewInputParserTest`（9/9 绿灯）
- [x] **Task 3: PlanExecuteAgent 分层并发执行引擎**
  - [x] 实现 `com.xhlcli.agent.PlanExecuteAgent`，实现 `AgentRunner` 接口
  - [x] 实现审阅交互循环（支持插件化 `PlanReviewHandler`）
  - [x] 实现 DAG 分层并发调度（单任务主线程、多任务受控并发与独立内存日志缓冲）
  - [x] 接入受限单步 ReAct 工具调用与短期记忆回灌，加入 `MAX_REPLAN_ATTEMPTS` 熔断机制
  - [x] 编写并通过 `com.xhlcli.agent.PlanExecuteAgentTest`（5/5 绿灯）
- [x] **Task 4: CLI 命令与交互打通**
  - [x] 更新 `ChatCommand.java` 与 `ChatCommandParser.java` 支持 `/plan` 指令
  - [x] 更新 `ChatLoop.java` 挂接 `PlanExecuteAgent` 与交互式 `handlePlan`
  - [x] 更新 `ChatBootstrap.java` 绑定终端审阅处理器与系统提示
  - [x] 更新并通过 `ChatCommandParserTest` 与 `ChatLoopTest`（9/9 绿灯）
- [x] **Task 5: 全量验证、文档、升级与 Tag 发布**
  - [x] 运行全量测试，246 项测试 100% 绿灯通过
  - [x] 升级 `pom.xml` 为 `0.6.0-SNAPSHOT`
  - [x] 编写技术设计与实施报告
  - [x] 更新 `CHANGELOG.md` 与 `AGENTS.md`
  - [x] Git Commit、Tag `v0.6.0` 与 Push
