# Phase 08：智能规划（Plan-and-Execute）技术设计规范

## 1. 架构目标与背景

在传统 ReAct 循环中，面对跨文件、多步骤以及高风险任务时，Agent 容易陷入“边走边看”、“局部试错”或在缺乏全局视角的情况下引入不可逆修改的问题。
Phase 08 引入显式 **Plan-and-Execute** 架构：
1. **显式规划前置**：在对代码进行破坏性操作前，Agent 先生成包含依赖关系（DAG）的结构化执行计划。
2. **人机协同审阅（HITL Review）**：提供交互式预览；用户可直接回车确认执行、输入补充约束要求模型自适应重排、或取消执行（零副作用）。
3. **拓扑分层调度**：执行器严格基于 DAG 偏序分层驱动任务推进，支持层内多任务受控并发与独立内存缓冲流隔离，杜绝控制台并发乱序。
4. **有限重规划与状态闭环**：任务失败且总体进度小于 50% 时自动触发自愈重规划，最终产出结构化对照验收总结。

---

## 2. 领域模型与核心算法

### 2.1 任务单元 (`com.xhlcli.plan.Task`)
- **`TaskType`**: `PLANNING`, `FILE_READ`, `FILE_WRITE`, `COMMAND`, `ANALYSIS`, `VERIFICATION`
- **`TaskStatus`**: `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, `SKIPPED`
- **属性**: `id`, `description`, `type`, `status`, `result`, `error`, `dependencies`, `dependents`, `startTime`, `endTime`
- **就绪判定**: `isExecutable(allTasks)` 确保仅当所有依赖均为 `COMPLETED` 且自身为 `PENDING` 时可调度。

### 2.2 执行计划聚合根 (`com.xhlcli.plan.ExecutionPlan`)
- **`PlanStatus`**: `CREATED`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED`
- **DFS 三色标记拓扑排序与环检测 (`computeExecutionOrder`)**:
  - 白色集合：未访问节点
  - 灰色集合 (`visiting`)：当前递归调用链上的节点
  - 黑色集合 (`visited`)：已完成排序入列的节点
  - 若在递归依赖时命中灰色节点，判定存在有向环并拒绝执行。
- **Kahn 分层批次算法 (`getExecutionBatches`)**:
  - 逐轮提取入度为 0 的任务层，计算并行批次与收敛结构。
- **展示与格式化**:
  - `visualize()`: 全量 ASCII 边框拓扑图
  - `summarize()`: 终端紧凑三行折叠摘要

### 2.3 规划器 (`com.xhlcli.plan.Planner`)
- **简单任务短路 (`isSimpleGoal`)**:
  - 对无多步骤连词且长度短的命令，自动短路生成 1 步最小计划，避免非必要 LLM 调用开销。
- **两遍建图解析**:
  - 第一遍创建所有 Task 并生成标椎映射 ID。
  - 第二遍绑定 dependencies 与 dependents。
- **自适应重规划 (`replan`)**:
  - 携带原目标、失败原因与已完成步骤的先验结果重新规划。

### 2.4 执行引擎 (`com.xhlcli.agent.PlanExecuteAgent`)
- 实现 `AgentRunner` 接口。
- **审阅循环**:
  - 支持 `PlanReviewHandler` 交互，捕获用户回车/run、cancel/esc、输入补充约束。
- **DAG 分层并发调度**:
  - 单任务主线程直接执行。
  - 多任务受控并发（最多 4 线程），每任务绑定独立 `ByteArrayOutputStream` 缓冲，执行完毕后按序输出。
  - 单步任务运行受限 ReAct 工具调用循环（最多 5 轮）。
  - 重规划熔断：设置 `MAX_REPLAN_ATTEMPTS = 2` 防止极端失败下的死循环。
  - 最终结果沉淀至短期记忆。

---

## 3. CLI 集成

- `ChatCommand.PLAN`
- `ChatCommandParser` 支持 `/plan` 与 `/plan <任务>`
- `ChatLoop` 挂接交互审阅处理器与 PlanExecuteAgent。
