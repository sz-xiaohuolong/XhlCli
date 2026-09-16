# Phase 10：Multi-Agent 协作架构设计规范

## 1. 架构目标与背景

随着智能编码任务复杂度的提升，单一 Agent 在处理跨模块重构、架构升级或复杂特性开发时容易产生角色职责混淆（规划者同时也是执行者和质检者导致“自我论证”）、上下文急剧膨胀（单一步骤积累过多无关调试历史）以及多任务并发输出混乱的问题。

Phase 10 正式引入 **Multi-Agent 专职协作体系与编排架构**：
1. **1+2+1 专职角色分工体系**：Planner 纯逻辑规划无工具、Worker 池排他并发持完整工具、Reviewer 专职审查无写工具、TeamOrchestrator 顶层编排。
2. **最小化上下文交接包（HandoverPackage）**：规范 Agent 间任务传递标准，单步仅传递目标、前置依赖摘要、验收标准与修改文件，使单步上下文体积缩减 85%~95%。
3. **结构化质量审查与有限重试熔断（ReviewResult）**：审查者输出严格 JSON 结构，支持条目化打回 Worker 重试，单步骤限制最多 2 次重试，超出立即熔断保护。
4. **无依赖步骤受控并发与独立日志隔离**：批次内无依赖步骤受控并发借用 Worker，独立内存流缓冲流式日志，批次完成后保序 flush 到主控制台。
5. **CLI 交互原生打通**：提供 `/team` 与 `/team <任务描述>` 交互式命令，支持极简轻量任务降级保护提示。

---

## 2. 核心架构与领域模型

### 2.1 角色体系与专职提示词 (`TeamRole` & `TeamPrompts`)
- **`TeamRole` 枚举**：
  - `PLANNER`: 专注高层逻辑规划与步骤拆解，**强制清空工具定义列表**，杜绝规划期误调工具；
  - `WORKER`: 专注代码编写与单步实施，持有全部本地开发工具权限并支持多轮 ReAct 工具调用；
  - `REVIEWER`: 专注质量审查与缺陷挑错，**强制清空写工具定义**，确保审查客观纯粹。
- **`TeamPrompts` 专职约束**：针对不同角色注入专职 System Prompt，强制规范输出格式（Planner 输出结构化任务 DAG，Reviewer 输出结构化 JSON）。

### 2.2 最小化上下文交接包 (`HandoverPackage`)
- **协议定义**：包含 `stepId`, `stepDescription`, `acceptanceCriteria`, `dependencyOutputs`, `modifiedFiles` 等字段。
- **核心价值**：
  - 阻断 Agent 间全量聊天历史的盲目透传与累积；
  - Worker 执行完成后产出标准化摘要更新 `modifiedFiles` 与 `summary`，交接给 Reviewer 与下游步骤。

### 2.3 结构化审查结果与容错降级 (`ReviewResult`)
- **字段定义**：`approved` (boolean), `summary` (String), `issues` (List<String>), `suggestions` (List<String>)。
- **双重解析机制**：
  - 优先通过 `ObjectMapper` 解析 Reviewer 返回的严格 JSON；
  - 若模型输出包含额外前导/后置思考文本，采用条目化与关键词启发式安全保守降级（未发现明确 Approved 默认判定为未通过）。

### 2.4 专职独立子代理 (`SubAgent`)
- 封装独立会话生命周期与消息历史（`List<ChatMessage>`）。
- 工具权限拦截：若角色非 `WORKER`，构造时强制下发 `List.of()`。
- 支持 `clearHistory()` 进行步骤间的历史复位，彻底防止单步骤历史向其他步骤渗漏。

### 2.5 团队协同编排器 (`TeamOrchestrator`)
- 实现 `AgentRunner` 统一接口。
- **三阶段执行流水线**：
  1. **规划阶段 (Planning Phase)**：调度 Planner 拆解任务为 DAG 依赖任务批次；
  2. **分层执行与审查阶段 (Execution & Review Phase)**：
     - 使用 `ParallelBatchScheduler` 调度无依赖步骤；
     - 向 Worker 池借用 Worker 实例；
     - 独立内存流 `ByteArrayOutputStream` 缓冲流式日志；
     - Reviewer 专职审查，未通过则条目化打回 Worker 重试，单步重试限制 `MAX_RETRIES = 2`；
     - 批次完成后保序 flush 到主控制台并归还 Worker；
  3. **汇总看板阶段 (Summary Dashboard Phase)**：
     - 生成终端 ASCII 交付汇总看板，列明每个步骤的耗时、重试次数、修改文件与审查结论；
     - 自动将关键决策回写至 `MemoryManager` 长期记忆。

---

## 3. 评测与验收指标

1. **上下文缩减率**：交接包相比共享全量历史，单步上下文 Prompt 体积降低 **85% ~ 95%**。
2. **审查重试熔断**：单步骤重试满 2 次后强制熔断，抛出结构化错误，杜绝死循环。
3. **日志穿插隔离**：并发执行下日志输出保持步骤完整性，控制台无字符交错。
4. **测试覆盖**：新增 20 项 Multi-Agent 专项测试，全量 291 项自动化测试 100% 绿灯通过。
5. **评测物证**：产出评测报告 [`docs/engineering/multi-agent-evaluation.md`](../engineering/multi-agent-evaluation.md)。
