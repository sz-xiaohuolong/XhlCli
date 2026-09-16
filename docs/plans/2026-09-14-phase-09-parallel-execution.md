# Phase 09：有界受控并发调度（Bounded Parallelism）实施路线

> **对应设计：** `docs/specs/2026-09-14-phase-09-parallel-execution-design.md`  
> **评测物证：** `docs/engineering/parallel-execution-benchmark.md`

## 1. 交付阶段概览

- 对应里程碑：M3 复杂任务与性能扩展
- 版本升级：`0.6.0-SNAPSHOT` $\rightarrow$ `0.7.0-SNAPSHOT`
- 交付 Git Tag：`v0.7.0`

## 2. 详细任务执行清单

- [x] **Task 1: 并发配置、资源模型与资格判定**
  - [x] 扩展 `AgentSettings`、`ConfigKey` 与 `ChatConfigLoader`，支持 `--max-concurrency` 与 `--tool-timeout` 参数解析与边界校验
  - [x] 实现 `com.xhlcli.parallel.ResourceAccess`（读写路径提取与规范化）
  - [x] 实现 `com.xhlcli.parallel.ParallelEligibilityDecider`（只读白名单、读读共享、读写互斥检测）
  - [x] 编写并通过 `ChatConfigLoaderTest`、`ParallelEligibilityDeciderTest`（8/8 绿灯）
- [x] **Task 2: 批次切分调度器与索引包装**
  - [x] 实现 `com.xhlcli.parallel.IndexedToolCall` 与 `ParallelBatch`
  - [x] 实现 `com.xhlcli.parallel.ParallelBatchScheduler`（顺序扫描、只读合并、独占操作切断）
  - [x] 编写并通过 `ParallelBatchSchedulerTest`（6/6 绿灯）
- [x] **Task 3: 有界并发执行器与生命周期控制**
  - [x] 实现 `com.xhlcli.parallel.BoundedParallelExecutor`
  - [x] 实现线程池有界管理（默认 4 并发，范围 1~16）
  - [x] 实现乱序执行与基于 `originalIndex` 的主线程保序归并
  - [x] 实现单工具超时故障隔离与 `CancellationToken` 协作式取消传播
  - [x] 编写并通过 `BoundedParallelExecutorTest`（6/6 绿灯）
- [x] **Task 4: Agent 运行时集成与 CLI 交互**
  - [x] 更新 `ReactAgent.java`：接入 `ParallelBatchScheduler` 与 `BoundedParallelExecutor`，重构执行循环
  - [x] 更新 `PlanExecuteAgent.java`：在 DAG Kahn 层内无依赖只读任务中接入并发加速
  - [x] 编写并通过 `ReactAgentParallelIntegrationTest`（并发读取与安全互斥验证）
- [x] **Task 5: 基准评测、全量回归与版本发布**
  - [x] 编写 Benchmark 测试用例，产出加速比评测报告 `parallel-execution-benchmark.md`（测得 2.65x ~ 3.76x 加速）
  - [x] 全量 271 项测试 100% 绿灯通过
  - [x] 升级 `pom.xml` 为 `0.7.0-SNAPSHOT`，更新 `CHANGELOG.md` 与 `AGENTS.md`
  - [x] Git Commit、打 Tag `v0.7.0` 并推送至 GitHub
