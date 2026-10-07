# Phase 17 实施计划：Runtime 与多模态 (Phase 17A: 后台持久任务与 Runtime API)

> **实施目标：** 按照 PRD 与技术设计规约，优先实施并交付 **Phase 17A：后台任务与 Runtime API**；构建 SQLite 持久化任务队列、有界 Worker 调度器、崩溃租约自愈、JDK 原生回环安全 API 服务、SSE 事件游标流及 `/task` 终端交互套件。  
> **前置门禁：** Human Approval Gate（技术设计与任务边界获用户批准）  
> **目标发布：** `v0.15.0` (分支开发与版本递进)

---

## 1. 任务分解清单 (WBS)

### Task 1: 任务领域模型与状态机 (`com.xhlcli.runtime.task`)
- [ ] 创建状态枚举 `TaskStatus` (`ENQUEUED`, `RUNNING`, `WAITING_FOR_APPROVAL`, `COMPLETED`, `FAILED`, `CANCELED`)，含规范化字符串值与解析方法；
- [ ] 创建记录 `DurableTask` (id, status, prompt, workspace, result, error, createdAt, startedAt, finishedAt, durationMs)，含 `terminal()` 与 `shortPrompt()` 辅助方法；
- [ ] 创建函数式接口 `TaskRunner` (`String run(String prompt) throws Exception`);
- [ ] 编写测试 `TaskStatusTest` 与 `DurableTaskTest`。

### Task 2: SQLite 任务持久化与调度引擎 (`DurableTaskManager`)
- [ ] 实现 `DurableTaskManager`：
  - 基于 SQLite (`tasks.db`)，创建 `runtime_tasks` 核心数据表与状态/时间戳索引；
  - 实现 `enqueue(prompt)`：生成 `task_<uuid12>` 并原子写入数据库；
  - 实现 `recoverRunningTasks()`：启动时扫描并将卡在 `RUNNING` 的孤儿任务重置为 `ENQUEUED`，杜绝死锁；
  - 实现 Worker 循环与 `claimNext()`：事务原子认领 `ENQUEUED` 任务转为 `RUNNING`；
  - 实现 `cancel(id)`：根据任务 ID 精准中断 Worker 线程并更新为 `CANCELED`；
  - 实现 `list(limit)` 与 `find(id)` 查询接口；
- [ ] 编写测试 `DurableTaskManagerTest`：覆盖任务入队、并发认领执行、取消中断、崩溃重启恢复。

### Task 3: 本地持久事件存储与游标读取 (`RuntimeThreadStore`)
- [ ] 创建记录 `RuntimeEvent` (id, threadId, type, data, createdAt)；
- [ ] 实现 `RuntimeThreadStore`：
  - 基于 SQLite (`runtime.db`)，创建 `runtime_threads` 与 `runtime_events` 表（自增 ID 主键）；
  - 实现 `createThread()` 与 `exists(threadId)`；
  - 实现 `appendEvent(threadId, type, data)`：自动生成自增序号并记录时间戳；
  - 实现 `events(threadId, afterId)`：根据增量游标有序拉取后续事件；
- [ ] 编写测试 `RuntimeThreadStoreTest`。

### Task 4: 本地安全 Runtime API 服务 (`RuntimeApiServer`)
- [ ] 实现 `RuntimeApiServer`：
  - 基于 JDK 内置 `com.sun.net.httpserver.HttpServer`，严格绑定 `127.0.0.1` 环回接口；
  - 强制 API Key 鉴权：未配置 Key 拒绝启动服务；无效 Key 返回 `401 Unauthorized`；
  - 实现 `POST /v1/threads`：创建线程；
  - 实现 `POST /v1/threads/{id}/turns`：异步提交交互轮次，触发 `TaskRunner` 并在后台派发事件；
  - 实现 `GET /v1/threads/{id}/events`：支持 `after` 参数，以 `text/event-stream` (SSE) 协议实时推送；
  - 实现优雅停机与资源回收（`close()`）；
- [ ] 编写测试 `RuntimeApiServerTest`：覆盖鉴权校验、端口监听、Turn 异步执行与 SSE 游标消费。

### Task 5: 终端指令、命令格式化与 Tab 智能补全
- [ ] 创建 `TaskCommandFormatter`：处理 `/task`、`/task list`、`/task add`、`/task log`、`/task cancel` 输出格式化；
- [ ] 扩展 `ChatCommand` (`TASK`) 并在 `ChatCommandParser` 注册解析；
- [ ] 在 `ChatLoop` 中挂载 `case TASK -> handleTask(input);`；
- [ ] 在 `TerminalCompleter` 增加 `/task` 二级子命令（`list`, `add`, `log`, `cancel`）及动态任务 ID 自动补全；
- [ ] 编写测试 `TaskCommandFormatterTest` 与 `TaskCliTest`。

### Task 6: 端到端集成验收、Living Docs 与发布
- [ ] 编写端到端验收套件 `RuntimeGoldenTest`：模拟全链路后台任务提交、并发执行、取消以及 API 线程与事件流游标断点拉取；
- [ ] 运行全量回归测试 `mvn clean test` 保持 100% 绿灯（495+ 项测试）；
- [ ] 产出工程评测报告 `docs/engineering/runtime-and-task-evaluation.md`；
- [ ] 更新 Living Docs (`PROJECT.md`, `ROADMAP.md`, `TECH_DESIGN.md`, `CHANGELOG.md`, `AGENTS.md`, `README.md`)；
- [ ] 升级版本为 `0.15.0-SNAPSHOT`，执行 Git 提交、打 Tag `v0.15.0` 并 Push。

---

## 2. 交付准则 (Definition of Done)
1. **纯 Java 零外部网络依赖**：Runtime API 完全依赖 JDK `HttpServer`，零额外三方 Web 容器；
2. **绝对安全隔离**：默认严格绑定 `127.0.0.1`，必须具备 API Key 鉴权，杜绝未授权访问；
3. **状态自愈与断线重连**：进程崩溃后孤儿任务可恢复，API 客户端断线后通过游标无缝继续消费事件；
4. **全量测试通过**：所有既有 475 项测试与新增测试 100% 绿灯。
