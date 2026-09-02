# Phase 05: 上下文与记忆 实施计划

> 日期：2026-09-02
> 对应设计：2026-09-02-phase-05-context-and-memory-design.md

## 1. 任务拆解

### Task 1: 预算计算与层级管理 (com.xhlcli.context)
- 实现 `TokenBudget`，建立基于字符数（或简易 Token 估算）的预算算法。
- 实现 `ContextAssembler`，定义 8 个层级的装配逻辑，在请求 `LlmClient` 前调用。
- 创建对应的单元测试。

### Task 2: 长期记忆与项目规则管理 (com.xhlcli.memory)
- 实现 `MemoryManager` 和 `LongTermMemory`，支持 project 和 global scope。
- 持久化：将记忆存储到 JSONL，项目级的存放在 `<workspace>/.xhlcli/memory/`，全局级的存放在 `~/.xhlcli/memory/`。
- 实现 `MemoryEntry` 模型，加入时间、作用域、可见性检查等属性。
- 增加对应单测 `MemoryManagerTest`、`LongTermMemoryTest`。

### Task 3: 会话历史自动压缩 (com.xhlcli.memory)
- 实现 `ConversationHistoryCompactor`，监听预算阈值，当快超载时调用 `LlmClient` 生成系统 prompt 总结最早的历史。
- 保证 `tool_call` 与 `tool_result` 配对不被拆散。
- 实现 `/compact` 手动触发机制。
- 增加对应的 `ConversationHistoryCompactorTest`。

### Task 4: CLI 交互与新命令集成 (com.xhlcli.cli)
- 增强 `ChatCommandParser` 和 `ChatLoop` 支持新的命令：
  - `/context` 显示当前预算。
  - `/clear` 重构，保留长期记忆和项目规则，仅清理会话和检索上下文。
  - `/save [--global] <text>` 保存记忆。
  - `/memory list`, `/memory search <q>`, `/memory delete <id>`, `/memory clear` 记忆管理。
- 集成 `ContextAssembler` 到当前的 `ReactAgent` 或 `ChatLoop` 流程。

### Task 5: 验证与文档更新
- 更新 `AGENTS.md` 和 `CHANGELOG.md`，添加 Phase 05 已交付内容。
- 在 `source-adoption-map.md` 中记录 Phase 05 的参考提交和 XhlCLI 主动差异。
- 运行 `./mvnw test` 确保全量测试通过。

## 2. 检查点 (Human Decision Gate)
- 需要用户批准该设计与计划，以确保架构设计与预算估算（Archtectural Decision）方案没有偏离目标。
