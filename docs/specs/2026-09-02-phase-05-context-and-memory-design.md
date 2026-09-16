# Phase 05: 上下文与记忆 技术设计

> 版本：v1.0
> 日期：2026-09-02

## 1. 架构目标
为 Agent 建立可预算、可压缩、可审计的上下文与记忆体系。核心在于确保大语言模型在长会话中不会因 Token 耗尽而崩溃，同时通过分层管理区分“短期状态”、“项目规则”和“长期记忆”。

## 2. 核心组件与职责

### 2.1 ContextAssembler
- **职责**：作为 `RunService` 或 `AgentRunner` 与 `LlmClient` 之间的桥梁，负责组装每次请求的 `List<ChatMessage>`。
- **层级顺序**：
  1. 基础系统规则 (base)
  2. 工具定义与安全策略 (tool/safety policy)
  3. Agent 模式 (agent mode)
  4. 运行上下文 (runtime context)
  5. 项目规则 (project rules)
  6. 检索的记忆 (retrieved memory)
  7. 压缩的会话 (compacted conversation)
  8. 当前会话交互 (current input)

### 2.2 TokenBudget & BudgetManager
- **职责**：维护当前上下文的总 Token（或字符数）预算。
- **功能**：计算各层级的空间占用，在请求前预留模型最大输出 Token 数、安全缓冲（Buffer）以及下一轮可能的大型工具结果。

### 2.3 MemoryManager
- **职责**：管理长期记忆与项目规则。
- **作用域**：支持项目级 (Project) 和全局级 (Global) 记忆。
- **持久化**：长期记忆持久化为 `JSONL` 格式，位于 `~/.xhlcli/memory/` 或 `<workspace>/.xhlcli/memory/`。

### 2.4 ConversationCompactor
- **职责**：当会话长度达到预算阈值时，自动触发历史会话的总结与压缩。
- **裁剪规则**：不可拆散 `tool_call` 和对应的 `tool_result`。压缩后的摘要必须以结构化文本形式保留“用户目标、已完成事项、修改文件、关键决策、未完成事项、约束”等。

## 3. 命令与用户控制
- `/context`：打印当前窗口预算估算、各层级占用率、压缩状态。
- `/compact`：手动强制压缩当前会话历史。
- `/clear`：清空当前会话（但保留长期记忆与项目规则）。
- `/save [--global]`：持久化一段重要信息到长期记忆。
- `/memory list|search|delete|clear`：管理长期记忆。

## 4. 与之前 Phase 的集成点
- 工具结果将被 `ToolResultBudget` 监控。对于 `read_file`、`grep_code` 等返回超大文本的工具，如果超过分配的上下文预算，需要进行截断，并在末尾附加“结果已截断，请使用 offset 等参数继续读取”的提示。
- `ChatLoop` 和 `ReactAgent` 将被重构以接入 `ContextAssembler`。

## 5. 迁移与参考基线
参考 `paicli` 中的提交 `d16c54e`、`72a7e90`、`96bc8b2` 的部分实现（尤其是 `TokenBudget`、`MemoryManager`、`ConversationHistoryCompactor`）。我们将提取其最小可行实现并以 `com.xhlcli` 规范重构。
