# Phase 07: 代码库 RAG (Codebase RAG) 技术设计

> 版本：v1.0  
> 日期：2026-09-10  
> 对应 PRD: `docs/prd/phase-07-codebase-rag.md`  
> 参考基准：`paicli` commit `3ff4ebc`

---

## 1. 架构定位与原则

1. **分工定位**：
   - 实时精确搜索（`glob_files -> grep_code -> read_file`）依然是 Agent 探索和修改代码的**默认首选路径**。
   - 代码库 RAG 作为**语义补充**：仅在自然语言意图模糊、关键词未知、跨模块业务概念（如“订单取消逻辑”、“审批放行逻辑”）或精确搜索未果时使用。
2. **轻量本地化**：
   - 使用嵌入式 SQLite（`sqlite-jdbc`）作为向量与元数据存储，持久化于 `<projectRoot>/.xhlcli/rag/codebase.db`。
   - 代码解析采用 AST（`javaparser-core`）实现类和方法级别的精确切分；非 Java 文件或解析失败时使用行窗口滑动降级。
3. **哈希驱动的确定性增量索引**：
   - 基于文件内容 SHA-256 哈希判断变动：未变文件不请求 Embedding；修改文件原地替换代码块；删除文件同步清除代码块。
4. **离线优先与优雅降级**：
   - Embedding Client 抽象接口，支持 OpenAI 兼容 API、Ollama 本地接口以及测试专用的确定性 Fake 实现。
   - 当 Embedding 服务不可用或索引尚未建立时，Agent 自动透明回退至本地精确搜索，不发生硬中断。

---

## 2. 核心模块设计

### 2.1 数据模型与切分 (`com.xhlcli.rag`)
* `CodeChunk`:
  - `filePath`: 项目相对路径
  - `chunkType`: `class` | `method` | `file`
  - `name`: 符号名（如类名或 `ClassName.methodName(params)`）
  - `content`: 代码段原文
  - `startLine`, `endLine`: 代码起止行号
  - `contentHash`: 块内容 SHA-256
* `CodeChunker`:
  - Java 文件：通过 `JavaParser` 提取 Class 头部和 Method 级别代码段。
  - 窗口回退：大文本或非 Java 文件按行窗口（约 2000 字符）切分。

### 2.2 存储与增量引擎 (`VectorStore` & `CodeIndex`)
* 数据库表设计：
  - `rag_meta`: 记录模型名、向量维度、版本信息。
  - `code_files`: 记录 `file_path`、`file_hash`、`updated_at`。
  - `code_chunks`: 记录 `file_path`、`chunk_type`、`name`、`content`、`start_line`、`end_line`、`embedding_json`。
* 增量流程：
  - 扫描项目所有有效代码文件（遵循 `.gitignore` 并忽略 `.git`, `target`, `.xhlcli`, `node_modules` 等构建/依赖目录）。
  - 增量对比：找出新增文件、修改文件、删除文件、无变化文件。
  - 仅对增量文件调用 `EmbeddingClient` 生成向量，通过事务批量落盘。

### 2.3 检索器与重排序 (`CodeRetriever` & `SearchResultFormatter`)
* `hybridSearch(query, topK)`:
  - 语义向量余弦相似度检索。
  - 简单关键词加权（类名、方法名精准匹配 bonus）。
  - 类型加权（method/class 优先于 file）。
  - 单文件 Top-N 截断控制（防止单一大文件占满 Top-K）。
* 结果格式化：输出包含文件相对路径、行范围、相似度及 `suggested_reads` 偏移量建议。

### 2.4 Agent 工具与 CLI 命令集成
* 新增 Tool: `search_code`:
  - 参数：`query` (string), `top_k` (integer, 默认 5)。
  - 风险级别：`LOW` (只读操作)。
  - 若未建索引，给出友好提示引导使用 `/index`。
* CLI 指令：
  - `/index`：执行增量/全量索引。
  - `/index status`：查看代码块数、文件数、模型与存储占用。
  - `/index clean`：清空当前项目索引。
  - `/search <query>`：人工调试语义检索。

---

## 3. 依赖项配置 (`pom.xml`)
- `org.xerial:sqlite-jdbc:3.49.1.0`
- `com.github.javaparser:javaparser-core:3.28.0`
