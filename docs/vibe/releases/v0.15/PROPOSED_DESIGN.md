# Release v0.15 Proposed Design

<!-- Source: specs/2026-10-07-phase-17-runtime-and-multimodal-design.md -->

# Phase 17 技术设计规约：Runtime 与多模态 (Runtime & Multimodal Design)

> **文档状态：** 设计完成（待用户审查与批准）  
> **设计日期：** 2026-10-07  
> **实现周期：** Phase 17（优先推进 17A：后台任务与 Runtime API；后续递进 17B：图片多模态上下文）  
> **对应 PRD：** [`docs/prd/phase-17-runtime-and-multimodal.md`](../prd/phase-17-runtime-and-multimodal.md)  
> **前置依赖：** Phase 09 (并发控制)、Phase 11 (多模型)、Phase 15 (终端交互治理)、Phase 16 (隔离快照)  
> **参考实现：** 本地授权仓库 `../paicli/src/main/java/com/paicli/runtime/` 及 `com/paicli/image/`

---

## 1. 架构目标与设计原则

Phase 17 旨在扩展 XhlCLI 的**任务入口**（从纯前台同步交互扩展到后台持久队列与本地 API）以及**上下文感知类型**（从纯文本代码扩展到视觉与图片）。系统遵循以下设计原则：

1. **统一模型与权限沙箱共享**：后台任务与 API 触发的运行共享现有的 `ReactAgent`、`ToolRegistry`、`PathGuard`、`ApprovalPolicy` 和安全审计，不建立第二套脱节的 Agent 运行时；
2. **本地优先与绝对回环隔离**：Runtime API 基于 JDK 原生 `HttpServer` 构建，严格默认绑定 `127.0.0.1` 环回地址，强制实施 API Key 鉴权，严禁任何形式的公网监听；
3. **状态持久化与崩溃自愈**：后台任务基于 SQLite (`tasks.db`) 持久化状态机，进程崩溃重启后通过租约扫描将卡死在 `RUNNING` 的孤儿任务安全重置，杜绝僵尸死锁；
4. **事件流一致性与断线重连**：API 事件采用单调自增游标（`after={cursor}`）与 SSE (`text/event-stream`) 协议，支持客户端断线后平滑恢复消费；
5. **分阶段可独立验收交付**：
   - **Phase 17A：后台任务与 Runtime API**（本阶段交付重点）；
   - **Phase 17B：图片上下文与多模态预处理**（在 17A 稳定后递进开发与交付）。

---

## 2. 总体架构拓扑图

```
                           +-------------------------------------+
                           |            终端用户 (CLI)           |
                           +------------------+------------------+
                                              |
                   +--------------------------+--------------------------+
                   |                                                     |
        [同步交互前台 /sendTurn]                                   [/task add/list/log/cancel]
                   |                                                     |
                   v                                                     v
          +-----------------+                                 +---------------------+
          |    ChatLoop     |                                 | DurableTaskManager  |
          +--------+--------+                                 +----------+----------+
                   |                                                     |
                   |                                                     | (SQLite: tasks.db)
                   |                                                     v
                   |                                          +---------------------+
                   |                                          | 有界 Worker Pool    |
                   |                                          | (xhlcli-task-worker)|
                   |                                          +----------+----------+
                   |                                                     |
                   +--------------------------+--------------------------+
                                              |
                                              v
                              +-------------------------------+
                              |           TaskRunner          |
                              |  (ReactAgent / ToolPipeline)  |
                              +---------------+---------------+
                                              |
                                              v
                              +-------------------------------+
                              |    PathGuard / AuditLog       |
                              |    ApprovalPolicy (非交互防线)|
                              +-------------------------------+
                                              ^
                                              | (Turn/Events)
                                              |
+----------------------+              +-------+-------+               +----------------------+
| 外部客户端 / 编辑器  | --- HTTP --> | RuntimeApiSvr | --- SSE ----> | 实时消费客户端       |
| (Authorization Key)  |              | (127.0.0.1)   |               | (Cursor Events Stream|
+----------------------+              +-------+-------+               +----------------------+
                                              |
                                              v
                                      (SQLite: runtime.db)
```

---

## 3. 子能力 A：后台持久任务与 Runtime API (Phase 17A)

### 3.1 领域模型与状态机 (`com.xhlcli.runtime.task`)

#### 状态转移矩阵 (`TaskStatus`)
- `ENQUEUED` ("enqueued")：任务已入库，等待后台线程分配；
- `RUNNING` ("running")：后台 Worker 正在执行任务；
- `WAITING_FOR_APPROVAL` ("waiting_for_approval")：遇到高危操作且当前为非交互模式，挂起等待授权；
- `COMPLETED` ("completed")：终态，成功返回结果；
- `FAILED` ("failed")：终态，抛出不可恢复异常；
- `CANCELED` ("canceled")：终态，用户或外部客户端主动中止。

#### 核心任务记录 (`DurableTask`)
```java
public record DurableTask(
        String id,              // 唯一任务编号，如 task_9a7b1c3d4e5f
        TaskStatus status,      // 当前状态
        String prompt,          // 目标提示词
        String workspace,       // 绑定的工作区绝对路径
        String result,          // 最终结果输出
        String error,           // 错误信息（若失败）
        Instant createdAt,      // 创建时间
        Instant startedAt,      // 开始执行时间
        Instant finishedAt,     // 结束时间
        long durationMs         // 执行总耗时（毫秒）
) {
    public boolean terminal() {
        return status == TaskStatus.COMPLETED 
            || status == TaskStatus.FAILED 
            || status == TaskStatus.CANCELED;
    }
}
```

### 3.2 持久化引擎与调度器 (`DurableTaskManager`)

1. **SQLite 存储路径**：
   - 默认存放在 `~/.xhlcli/tasks/tasks.db`；
   - 支持环境变量 `XHLCLI_TASK_DIR` 或系统属性 `-Dxhlcli.task.dir` 动态重定向；
2. **表结构定义**：
   ```sql
   CREATE TABLE IF NOT EXISTS runtime_tasks (
       id TEXT PRIMARY KEY,
       status TEXT NOT NULL,
       prompt TEXT NOT NULL,
       workspace TEXT,
       result TEXT,
       error TEXT,
       created_at TEXT NOT NULL,
       started_at TEXT,
       finished_at TEXT,
       updated_at TEXT,
       duration_ms INTEGER DEFAULT 0
   );
   CREATE INDEX IF NOT EXISTS idx_runtime_tasks_status ON runtime_tasks(status);
   CREATE INDEX IF NOT EXISTS idx_runtime_tasks_created ON runtime_tasks(created_at);
   ```
3. **租约恢复与崩溃自愈 (`recoverRunningTasks`)**：
   - 当 CLI 重启时，检测所有 `status = 'running'` 的任务；
   - 自动回滚为 `ENQUEUED` 重新排队执行，或标记为 `FAILED`（依据配置），防止永久阻塞在 `running`；
4. **事务认领 (`claimNext`)**：
   - 使用 SQLite 串行事务将最早入队的 `ENQUEUED` 任务原子化更新为 `RUNNING` 并打上 `started_at` 时间戳；
5. **协作式取消 (`cancel`)**：
   - 记录当前正在执行的线程 `runningTasks`；
   - 当调用 `cancel(id)` 时，触发对应线程中断 `thread.interrupt()`，并更新数据库状态为 `CANCELED`。

### 3.3 本地安全 Runtime API 服务 (`com.xhlcli.runtime.api`)

1. **HttpServer 架构**：
   - 采用 JDK 自带纯 Java `com.sun.net.httpserver.HttpServer`，零额外外部框架负担；
   - 仅绑定回环网络 `127.0.0.1`，端口可配置（默认随机或指定可用端口）；
2. **强制鉴权 (`Authentication`)**：
   - 强制读取配置的 `XHLCLI_RUNTIME_API_KEY` 或系统属性 `-Dxhlcli.runtime.api.key`；
   - 启动时校验：若 API Key 为空，直接抛出异常拒绝启动服务；
   - 请求头校验：检查 `Authorization: Bearer <key>` 或 `X-XhlCLI-API-Key: <key>`，无效直接响应 `401 Unauthorized`；
3. **RESTful 接口规约**：
   - `POST /v1/threads`：创建新的会话线程，返回 `{ "id": "thread_xxx", "object": "thread" }`；
   - `POST /v1/threads/{id}/turns`：向会话提交新的交互 turn，返回 `{ "id": "turn_xxx", "status": "running" }`；
   - `GET /v1/threads/{id}/events?after={cursor}`：基于 SSE 输出增量事件：
     ```text
     id: 101
     event: message.delta
     data: {"turn_id":"turn_xxx","content":"正在分析代码..."}

     id: 102
     event: turn.completed
     data: {"turn_id":"turn_xxx","status":"completed"}
     ```
4. **事件持久化与单调游标 (`RuntimeThreadStore`)**：
   - 基于 SQLite `runtime.db`，表结构：
     - `runtime_threads`: `id`, `created_at`
     - `runtime_events`: `id INTEGER PRIMARY KEY AUTOINCREMENT`, `thread_id`, `type`, `data`, `created_at`
   - 查询 `events(threadId, afterId)` 时基于自增主键 `id > afterId` 进行有序分页拉取。

### 3.4 终端指令交互与治理 (`TaskCommandFormatter`)

- 指令分发：
  - `/task` 或 `/task list [N]`：表格展示最近 N 个任务的 ID、状态、耗时与提示词前缀；
  - `/task add <任务内容>`：提交后台任务并返回追踪指令（`/task log <id>`）；
  - `/task log <id>`：格式化呈现任务完整详情、执行耗时、错误日志与返回结果；
  - `/task cancel <id>`：安全取消排队中或运行中的任务；
- Tab 智能补全：在 `TerminalCompleter` 中动态补全二级子命令。

---

## 4. 子能力 B：图片多模态上下文预处理 (Phase 17B，待 17A 稳定后推进)

1. **输入语法与引文解析 (`ImageReferenceParser`)**：
   - 支持 `@image:<path>`、`@image:path` 以及 `@clipboard` 语法；
   - 提取图片引用并过滤全角 CJK 标点，剥离纯文本 Prompt；
2. **多模态图片处理器 (`ImageProcessor`)**：
   - 支持 PNG、JPEG、GIF、WEBP 格式识别；
   - 大小限制：源文件上限 50MB，处理后 Base64 目标上限 5MB；
   - 尺寸缩放：长宽最大限制 2000x2000，按比例平滑重采样；
   - 透明度处理：透明 PNG 自动合成白色底色，防止视觉模型底色穿透；
3. **Provider 视觉能力判断与降级策略**：
   - 检查 `ModelCapabilities.supportsVision()`；
   - 支持视觉时组装多模态 ContentPart 发送；
   - 不支持视觉时，优雅降级为纯文本提示（例如 `[图片未传递: 当前模型不支持视觉输入，建议使用 /model use 切换为多模态模型]`），绝不把图片 Base64 错发到文本接口导致 400 报错。

---

## 5. 测试与验证策略

1. **单元测试矩阵**：
   - `TaskStatusTest`：枚举值与解析边界；
   - `DurableTaskManagerTest`：任务入队、Worker 认领并发执行、多任务取消、租约自愈与数据库隔离；
   - `RuntimeThreadStoreTest`：线程创建、自增事件写入、游标断点拉取；
   - `RuntimeApiServerTest`：API 端口探测、Key 鉴权拦截 (401)、Thread/Turn 创建、SSE 事件流拉取；
   - `TaskCliTest`：`/task` 系列命令解析与 Tab 补全验证；
2. **集成验收套件 (`RuntimeGoldenTest`)**：
   - 全链路模拟后台任务入队 -> 异步执行 -> 断线重连与查询 -> 取消与状态一致性验证；
3. **回归与质量门禁**：
   - 确保全量 475+ 项已有测试 100% 绿灯，新功能独立测试全覆盖。
