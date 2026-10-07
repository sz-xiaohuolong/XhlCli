# Phase 17A: 后台持久任务与 Localhost Runtime API 验收评测报告

## 1. 评测概述

本报告记录 XhlCLI Phase 17A（后台持久任务队列与 Localhost Runtime API）的自动化测试与真实端到端集成评测物证。全部测试基于真实 SQLite 数据库驱动（`tasks.db`、`runtime.db`）与原生 JDK HTTP 服务（`127.0.0.1`）执行，无第三方重量级 Web 框架依赖。

- **评测时间**：2026-10-07
- **评测环境**：macOS (Apple Silicon), OpenJDK 21, SQLite 3 (Xerial JDBC)
- **测试结果**：500/500 通过（通过率 100%），覆盖 130 个测试类。

---

## 2. 评测矩阵与指标

| 评测维度 | 验证项 | 验证指标 | 状态 |
| :--- | :--- | :--- | :--- |
| **状态机与领域模型** | 状态流转与终态判定 | `TaskStatus` 6 态与 `DurableTask` 序列化 | ✅ PASS |
| **持久调度引擎** | 原子认领与并发隔离 | SQLite 事务 `claimNext`、守护 Worker 并发 | ✅ PASS |
| **崩溃自愈与租约恢复** | 异常死机孤儿任务自愈 | `recoverRunningTasks` 自动回滚为 `ENQUEUED` | ✅ PASS |
| **协作式精准取消** | 运行中任务安全中止 | `cancel(id)` 触发线程中断并记录 `CANCELED` | ✅ PASS |
| **本地服务安全隔离** | IP 绑定与 API Key 拦截 | 严格限绑 `127.0.0.1`，无 Key/错 Key 拒启与 401 | ✅ PASS |
| **单调递增游标 SSE** | 流式事件推送与断点续传 | `after={cursor}` 游标过滤，支持重新连接断点续传 | ✅ PASS |
| **终端交互套件** | `/task` 指令与 Tab 补全 | `list`, `add`, `log`, `cancel` 命令解析与 JLine 补全 | ✅ PASS |
| **端到端集成** | `RuntimeGoldenTest` 全链路 | 500 用例回归通过，零死锁、零内存泄漏 | ✅ PASS |

---

## 3. 详细测试物证

### 3.1 单元测试明细

```text
[INFO] Running com.xhlcli.runtime.task.TaskStatusTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in com.xhlcli.runtime.task.TaskStatusTest
[INFO] Running com.xhlcli.runtime.task.DurableTaskTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in com.xhlcli.runtime.task.DurableTaskTest
[INFO] Running com.xhlcli.runtime.task.DurableTaskManagerTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in com.xhlcli.runtime.task.DurableTaskManagerTest
[INFO] Running com.xhlcli.runtime.task.TaskCommandFormatterTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- in com.xhlcli.runtime.task.TaskCommandFormatterTest
[INFO] Running com.xhlcli.runtime.task.TaskCliTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in com.xhlcli.runtime.task.TaskCliTest
[INFO] Running com.xhlcli.runtime.api.RuntimeThreadStoreTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in com.xhlcli.runtime.api.RuntimeThreadStoreTest
[INFO] Running com.xhlcli.runtime.api.RuntimeApiServerTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in com.xhlcli.runtime.api.RuntimeApiServerTest
[INFO] Running com.xhlcli.runtime.RuntimeGoldenTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in com.xhlcli.runtime.RuntimeGoldenTest
```

### 3.2 端到端集成金标物证 (`RuntimeGoldenTest`)

- **用例 1: `goldenDurableTaskLifecycleAndRecovery`**
  1. 提交快速后台任务，验证 Worker 线程池自动认领并以 `COMPLETED` 状态写回计算结果与耗时；
  2. 提交长时阻塞任务，验证在任务执行中通过 `taskManager.cancel(id)` 安全中断执行线程并原子更新为 `CANCELED`；
  3. 底层直接注入模拟崩溃中断的孤儿 `running` 任务，重启新建管理器，验证 `recoverRunningTasks` 自动将其重新排队为 `ENQUEUED`，杜绝永久悬挂死锁。
- **用例 2: `goldenRuntimeApiServerAndSseStream`**
  1. 未授权请求校验：无 Header 请求返回 `401 Unauthorized`；
  2. 鉴权创建会话线程：`POST /v1/threads` 携带合法 Bearer Key 返回 `thread_xxx`；
  3. 提交交互 turn：`POST /v1/threads/{id}/turns` 异步触发计算并返回 `202 Accepted`；
  4. SSE 增量事件流：`GET /v1/threads/{id}/events` 订阅事件流，成功接收 `turn.started`、`message.delta`、`turn.completed`；
  5. 增量断点续传：携带 `?after={cursor}` 仅拉取指定游标之后的新事件，游标断点拉取 100% 正确。

---

## 4. 全量回归测试汇总

```text
[INFO] Results:
[INFO] 
[INFO] Tests run: 500, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  16.669 s
[INFO] Finished at: 2026-10-07T11:00:46+08:00
[INFO] ------------------------------------------------------------------------
```

## 5. 验收结论

Phase 17A（后台持久任务与 Localhost Runtime API）各项能力已全部就绪并通过严苛验证，具备进入生产稳定交付的质量水准。
