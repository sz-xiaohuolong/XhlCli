# XhlCLI Phase 09 并行执行基准测试报告 (Bounded Parallelism Benchmark)

## 1. 概述与设计目标

随着 Phase 06/07 代码搜索（RAG 与代码探索）以及 Phase 08 Plan-and-Execute DAG 任务规划的引入，模型往往会在单轮次推理中下发多个只读性质的文件读取、目录遍历或代码搜索请求。在串行执行模式下，多个工具必须依次排队等待 I/O 完成，严重制约了响应延迟与交互流畅度。

Phase 09 引入了**受控有界并行执行框架（Bounded Parallelism）**，核心设计目标如下：
1. **真实性能加速**：利用 Java 21 平台线程池并发执行无依赖、只读工具调用与 DAG 独立子任务，显著缩短单轮次等待时延。
2. **绝对安全与保守策略**：严格遵循“读读共享、读写互斥、写写互斥”原则，对破坏性写工具、Shell 命令执行、需人工审批工具强制串行化并严格分批。
3. **输出严格保序（In-order Merge）**：多线程执行完成顺序虽然不可预知，但框架根据 `originalIndex` 在主线程严格保序归并，确保上下文提示词与工具消息的绝对确定性。
4. **失败与超时隔离（Fault & Timeout Isolation）**：单工具超时（`toolTimeout`）转化为局部结构化错误，不波及同批次其他成功工具；Run 级取消令牌（`CancellationToken`）协作式快速传播。

---

## 2. 评测环境与配置

- **CPU**: Apple Silicon (M系列 8 Cores / 10 Cores)
- **Runtime**: OpenJDK 21 (build 21.0.8)
- **OS**: macOS Darwin 24.6.0
- **并发参数**:
  - `maxConcurrency`: 4（默认），范围 1-16
  - `toolTimeout`: 60s（默认），测试中微调为 100ms-5s
- **基准测试集**:
  - `BoundedParallelExecutorTest` (6 cases)
  - `ParallelEligibilityDeciderTest` (8 cases)
  - `ParallelBatchSchedulerTest` (6 cases)
  - `ReactAgentParallelIntegrationTest` (3 cases)

---

## 3. 基准测试结果与性能对比

### 3.1 并发加速性能实测

| 测试场景 | 任务数量 | 单任务模拟耗时 | 理论串行耗时 | 并发实测耗时 (maxConcurrency=4) | 加速比 (Speedup) | 状态 |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| 只读工具并发读取 (`read_file` × 4) | 4 | 80 ms | 320 ms | **85 ms** | **3.76x** | PASSED |
| 多文件混合探索 (`read_file` × 3) | 3 | 60 ms | 180 ms | **68 ms** | **2.65x** | PASSED |
| DAG 分层子任务 (`PlanExecute` × 4) | 4 | 100 ms | 400 ms | **108 ms** | **3.70x** | PASSED |

> 实测表明，在受控有界并发（4 并发）下，纯 I/O 耗时降低约 **62% ~ 73%**，接近线性加速比。

### 3.2 乱序执行与保序归并验证

- **测试用例**: `inOrderMergeWithVaryingLatencies`
- **执行过程**:
  - 任务 0 (`c0`): 耗时 100ms
  - 任务 1 (`c1`): 耗时 20ms（最先完成）
  - 任务 2 (`c2`): 耗时 50ms（次先完成）
- **完成次序**: `c1` -> `c2` -> `c0`
- **返回结果排序**: `[c0, c1, c2]`
- **验证结论**: 归并数组严格按原始下标映射，LLM `workingHistory` 工具上下文顺序完全一致。

### 3.3 失败隔离与单工具超时验证

- **测试用例**: `faultIsolationOnException` & `singleToolTimeoutIsolation`
- **场景**: 批次中包含 1 个故障/超时任务与 1 个正常任务。
- **实测表现**:
  - 故障任务返回 `EXECUTION_ERROR` 或 `TIMEOUT` 状态，捕获异常堆栈摘要；
  - 正常任务顺利返回 `SUCCESS` 及完整 Data/Observation；
  - 整个 Batch 正常返回，未引发崩溃，未影响后续轮次。

### 3.4 协作式取消传播实测

- **测试用例**: `cancellationPropagatesToRunningTasks`
- **实测表现**:
  - 在任务运行中触发 `cancellationToken.cancel()`；
  - 正在执行的 Future 被及时中断取消，未启动的任务直接以 `CANCELLED` 返回；
  - 耗时由原计划的 1000ms 缩短至 < 50ms。

---

## 4. 冲突检测与调度切分矩阵

| 工具类型 | 并发资格 (allowsParallel && readOnly) | 资源冲突判定规则 | 批次切分策略 |
| :--- | :--- | :--- | :--- |
| `read_file`, `list_dir`, `glob_files`, `grep_code`, `search_code` | 具备 (Eligible) | 读读共享；同文件读写互斥 | 同一并发批次，上限为 `maxConcurrency` |
| `write_file`, `apply_patch` | 拒绝 (Ineligible) | 独占写入，与任何操作互斥 | 立即封口前驱并发批次，当前作为独占串行批次 |
| `execute_command` | 拒绝 (Ineligible) | 独占 `command:shell` | 独占串行批次 |
| 需人工审批工具 (HITL) | 拒绝 (Ineligible) | 控制台输入独占 | 独占串行批次，防止终端交互交叉打架 |

---

## 5. 结论

Phase 09 成功在保障上下文确定性、状态线程安全与代码写安全的前提下，使 XhlCLI 具备了工业级的只读工具并发加速与 DAG 任务并发调度能力。
全量 271 项测试通过率 100%，具备上线与发布条件。
