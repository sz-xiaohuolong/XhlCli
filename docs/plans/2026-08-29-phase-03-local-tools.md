# Phase 03 本地工具集 (Local Tools) 实施计划与记录

> **对应设计：** `docs/specs/2026-08-29-phase-03-local-tools-design.md`
> **对应需求：** `docs/prd/phase-03-local-tools.md`

## 1. 任务拆分与执行状态

- [x] **Task 1: 路径安全与基础文件读写工具**
  - 创建 `WorkspacePathResolver` 并增加路径穿越与越界单测 `WorkspacePathResolverTest`
  - 创建 `ListDirTool` 及单测 `ListDirToolTest`
  - 创建 `ReadFileTool` 及全量/分页行号读取单测 `ReadFileToolTest`
  - 创建 `WriteFileTool` 及 5MB 大小限制、目录自建单测 `WriteFileToolTest`

- [x] **Task 2: 代码精确补丁与 Git 差异工具**
  - 创建 `ApplyPatchTool`（唯一匹配替换）及 0 匹配/多匹配报错单测 `ApplyPatchToolTest`
  - 创建 `GitDiffTool` 及空差异/无仓库容错单测 `GitDiffToolTest`

- [x] **Task 3: 受控 Shell 命令执行工具**
  - 创建 `ExecuteCommandTool`（超时/截断/取消中断）及单测 `ExecuteCommandToolTest`

- [x] **Task 4: 文件查找与代码搜索体系**
  - 创建不可变搜索领域 Record (`CodeSearchRequest`, `CodeSearchResult`, `GrepMatch`, `ContextLine`, `CodeSearchEngine`)
  - 创建 `GlobFilesTool` 及单测 `GlobFilesToolTest`
  - 创建 `JavaCodeSearchEngine` 并迁移二进制跳过、上下文抓取等逻辑，创建 `JavaCodeSearchEngineTest`
  - 创建 `RipgrepCodeSearchEngine`（流式 JSON 解析与降级机制）
  - 创建 `GrepCodeTool`（预算控制与 suggested_reads 推荐）及 `GrepCodeToolTest`
  - 创建 `CodeSearchGoldenSetTest` 与 `src/test/resources/code-search/golden-set.json` 真实评测集

- [x] **Task 5: 真实 Agent 循环集成与 Prompt 更新**
  - 在 `ChatBootstrap` 中为真实会话装配 8 个本地工具与路径解析器
  - 更新 System Prompt 指引模型在代码任务中正确组合搜索、读取、修改与测试工具
  - 编写 `LocalToolsCodingLoopTest` 模拟真实 ReAct 编程工作流验证完整闭环

- [x] **Task 6: 工程文档与状态同步**
  - 编写 Phase 03 技术设计与实施记录文档
  - 更新 `CHANGELOG.md`、`AGENTS.md` 和 `source-adoption-map.md`
