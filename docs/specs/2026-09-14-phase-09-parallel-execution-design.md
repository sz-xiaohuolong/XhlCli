# Phase 09：有界受控并发调度（Bounded Parallelism）技术设计规范

## 1. 架构目标与背景

在传统串行 ReAct 循环与 Plan-and-Execute 推进中，当模型在单轮响应中同时下发多个只读文件查看、代码探索或分层无依赖子任务时，传统的串行执行方式必须依次等待每个 I/O 完成，产生显著的时延累积。

Phase 09 引入**受控有界并行执行框架（Bounded Parallelism）**，在保障绝对执行安全与提示词确定性的前提下，实现高效的 I/O 并发加速：
1. **真实性能加速**：利用 Java 21 线程池并发执行只读无依赖的工具调用，将单轮次多工具 I/O 耗时缩短 60%~75%。
2. **保守的安全互斥策略**：严格遵循“读读共享、读写互斥、写写互斥”原则，对写操作、Shell 命令、人工审批（HITL）工具强制执行单任务独占批次。
3. **输出严格保序（In-order Merge）**：无论多线程实际完成时间与次序如何，结果均严格按模型下发的原始索引（`originalIndex`）在主线程保序组装，保证 LLM 上下文与观测记录的确定性。
4. **故障与超时隔离（Fault & Timeout Isolation）**：单工具超时（`toolTimeout`）或异常转化为局部结构化错误，不波及同批次中其他正常任务；Run 级 `CancellationToken` 协作式快速传播。

---

## 2. 核心架构与领域模型

### 2.1 资格判定与资源互斥 (`com.xhlcli.parallel.ParallelEligibilityDecider`)
- **只读白名单**：默认将 `read_file`, `list_dir`, `glob_files`, `grep_code`, `search_code` 判定为只读工具，允许并行候选。
- **互斥资源提取 (`ResourceAccess`)**：解析工具参数中的目标路径并做规范化，区分读路径与写路径。
- **批次相容性判定**：
  - 存在写操作、命令执行（`execute_command`）或高风险需审批工具时，立即切断当前批次；
  - 多个读操作若访问同一文件，允许读共享；若存在路径重叠且涉及写操作，则判定冲突并拆分批次。

### 2.2 批次切分调度器 (`com.xhlcli.parallel.ParallelBatchScheduler`)
- 将模型单轮返回的 `List<ToolCall>` 顺序扫描切分为 `List<ParallelBatch>`。
- 每个 `ParallelBatch` 携带：
  - `isParallel`: 该批次是否可并发执行（仅当批次内全部为只读且无冲突时为 `true`）；
  - `calls`: 带有 `originalIndex` 标识的 `IndexedToolCall` 列表。
- 保证任意写工具或独占工具独立构成 `isParallel = false` 的单元素批次。

### 2.3 有界并发执行器 (`com.xhlcli.parallel.BoundedParallelExecutor`)
- **线程池控制**：内部维护受控有界线程池，最大并发度受 `maxConcurrency`（默认 4，配置范围 1~16）严格限制，杜绝无界线程耗尽系统句柄。
- **乱序保序归并**：
  - 各线程异步提交 `Callable<IndexedToolResult>`；
  - 采用 `CompletableFuture.allOf` 等待批次完成，按 `originalIndex` 排序装配为输出列表；
- **超时与取消**：
  - 支持 `toolTimeout`（默认 60s），单个任务超时不阻断其他任务收集；
  - 集成 `CancellationToken`，外部取消信号触发时立即对尚未启动的任务进行短路，已启动任务协作式终止。

### 2.4 Agent 与 CLI 深度集成
- **`ReactAgent`**：在执行模型返回的 `tool_calls` 时，通过 `ParallelBatchScheduler` 动态分批，串行批次走标准单步调度，并行批次委托 `BoundedParallelExecutor`。
- **`PlanExecuteAgent`**：在 DAG Kahn 分层执行时，同一层内无依赖的只读任务（如多文件的分析读取）支持并发加速。
- **CLI 参数**：新增 `--max-concurrency <n>` 与 `--tool-timeout <seconds>`，由 `AgentSettings` 与 `ChatConfigLoader` 统一加载与校验。

---

## 3. 评测与验收指标

1. **加速比 (Speedup)**：在 4 并发配置下，多文件读取探索场景端到端耗时降低 $\ge 60\%$，实现接近线性加速。
2. **保序率**：100% 保证模型提示词中的 `tool_call_id` 观测结果按原始下标精准对应。
3. **测试覆盖**：新增 23 项专项单元与集成测试（`BoundedParallelExecutorTest`, `ParallelBatchSchedulerTest`, `ParallelEligibilityDeciderTest`, `ReactAgentParallelIntegrationTest`）。
4. **评测物证**：产出基准测试报告 [`docs/engineering/parallel-execution-benchmark.md`](../engineering/parallel-execution-benchmark.md)。
