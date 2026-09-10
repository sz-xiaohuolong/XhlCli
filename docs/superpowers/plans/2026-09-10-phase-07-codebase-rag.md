# Phase 07: 代码库 RAG 实施计划

> 日期：2026-09-10  
> 对应设计：`docs/superpowers/specs/2026-09-10-phase-07-codebase-rag-design.md`

## 1. 任务拆解

### Task 1: 引入依赖与数据结构模型
- 更新 `pom.xml`，引入 `sqlite-jdbc` 与 `javaparser-core`。
- 实现 `CodeChunk` 与 `CodeChunker`（支持 Java AST 解析与通用行滑动降级）。
- 编写 `CodeChunkerTest` 验证类与方法切分逻辑。

### Task 2: 实现 VectorStore 与增量索引引擎 CodeIndex
- 实现 `VectorStore`（SQLite 事务管理、代码块与文件哈希持久化、余弦相似度计算）。
- 实现 `EmbeddingClient`（支持可配置 HTTP 接口，及单元测试用确定性向量生成器）。
- 实现 `CodeIndex` 增量扫描与更新逻辑（比对文件 SHA-256，仅处理变更文件）。
- 编写 `VectorStoreTest` 和 `CodeIndexTest` 验证增量添加、修改与删除。

### Task 3: 检索器 CodeRetriever 与 SearchCodeTool
- 实现 `CodeRetriever`（混合重排序、关键词命中加权、单文件截断）与 `SearchResultFormatter`。
- 实现 `SearchCodeTool` 并注册至 `ToolRegistry`，添加只读安全策略配置。
- 编写 `CodeRetrieverTest` 验证检索召回。

### Task 4: CLI 指令集成 (`/index`, `/search`) 与系统提示强化
- 扩展 `ChatCommandParser` 与 `ChatCommand`，支持 `/index [status|clean]` 与 `/search <query>`。
- 在 `ChatLoop` 中对接索引与搜索命令，输出直观的统计与检索结果。
- 更新 `ChatBootstrap` 中的系统提示词，明确说明何时使用精确搜索、何时使用 `search_code`。

### Task 5: RAG Golden Set 评测、交付文档与 Tag 发布
- 建立 RAG Golden Set 自然语言语义查询评测集，验证跨模块召回率达标。
- 编写交付物基准文档 `docs/engineering/codebase-rag-evaluation.md`。
- 升级版本至 `0.5.0-SNAPSHOT`，更新 `CHANGELOG.md` 与 `AGENTS.md`。
- 全量自动化测试验证通过，提交代码，推送 Git Tag `v0.5.0`。
