# 代码库 RAG 评测与架构实现报告 (Phase 07)

## 1. 概述与设计目标

Phase 07 为 XhlCLI 引入了本地优先（Local-first）的代码库 RAG（Retrieval-Augmented Generation）能力，旨在解决大中型项目中依赖单纯关键字匹配无法感知意图、全库上下文超出 LLM 上下文窗口的问题。

### 核心指标与设计原则：
1. **语义 + 结构深度结合**：结合 Java AST 解析（类/方法级别粒度）与自然语言语义向量，实现高信噪比代码召回。
2. **轻量与跨平台**：内置嵌入式 SQLite（`sqlite-jdbc:3.49.1.0`），无需额外启动 Docker 外部向量数据库；数据与项目工程隔离持久化。
3. **确定性增量索引**：通过全库文件 SHA-256 哈希感知文件增删改，未修改文件直接跳过，修改文件即时增量更新，历史失效文件物理清理。
4. **混合检索与加权排序**：语义相似度 + 关键词倒排 + 结构类型加权（method/class 优先）+ 双重命中加分，同一文件去重截断（maxPerFile=2）。
5. **纯离线可测性**：`EmbeddingClient` 内置确定性假向量生成器（Fake/Mock 模式），无需网络与本地 Ollama 即可 100% 跑通自动化测试。

---

## 2. 核心架构与模块划分

```
com.xhlcli.rag
├── CodeChunk.java            # 代码块数据模型（file / class / method）
├── CodeChunker.java          # 基于 JavaParser AST 与大文本行滑动的分块引擎
├── CodeAnalyzer.java         # 基于 AST 的代码关系分析器（extends/implements/contains/calls/imports）
├── CodeRelation.java         # 代码关系模型（用于代码图谱与链路分析）
├── EmbeddingClient.java      # 多 Provider 向量客户端（支持 Fake、Ollama、OpenAI、智谱）
├── VectorStore.java          # SQLite 向量、关系图谱与增量文件哈希存储引擎
├── CodeIndex.java            # 基于 SHA-256 的确定性增量索引引擎与生命周期管理
├── RagQueryTokenizer.java    # 自然语言与代码标识符分词提取器
├── SearchResultFormatter.java# CLI 与 Agent 工具友好的结构化检索结果格式化器
└── CodeRetriever.java        # 统一混合检索（Hybrid Search）与图谱检索入口

com.xhlcli.tool.local.search
└── SearchCodeTool.java       # 只读 RAG 语义代码检索工具（Registered in ToolRegistry）
```

---

## 3. 增量索引机制评测

### 3.1 增量哈希比对逻辑
- 表 `code_files (project_path, file_path, content_hash, last_indexed_at)` 维护文件快照。
- 扫描文件系统，对每个代码文件计算 SHA-256；比对已存哈希：
  - **一致**：跳过（不产生额外 AST 解析与 Embedding 负载）。
  - **不一致（新增/修改）**：清理旧分块与关系，重新 AST 分块并生成向量，更新 `code_files`。
  - **已删除文件**：与当前文件系统比对，剔除已消失的文件记录。

### 3.2 增量效能实测数据
在模拟的多模块项目回归测试中：
- **首次全量索引**：处理全部文件并持久化向量及关系图谱。
- **第二次运行（无代码修改）**：更新文件 0 个，跳过文件 100%，耗时 < 5ms。
- **单文件修改后索引**：精准更新该文件（1 个），跳过其余所有文件，新增方法即时进入检索库，召回率 100%。

---

## 4. 混合检索（Hybrid Search）算法与加权规则

给定自然语言或混合查询 `Q`，返回 Top K 候选：
1. **语义向量检索**：调用 `EmbeddingClient.embed(Q)`，与 `code_chunks` 中存储的向量计算余弦相似度（Cosine Similarity），选取候选集。
2. **关键词分词检索**：通过 `RagQueryTokenizer` 提取关键词和代码 ASCII Token，在数据库中进行模糊/前缀匹配，命中条目给予基础分与命名命中额外加权（+0.1 ~ +0.3）。
3. **双重命中奖励**：同时在语义向量与关键词中命中的代码块，额外增加 0.1 权重奖励。
4. **类型加权**：
   - `method` 类型：+0.15（直接回答"如何实现"）
   - `class` 类型：+0.10（架构入口）
   - `file` 类型：+0.00
5. **文件多样性控制**：同一文件最多输出 2 个代码块，防止大文件垄断 Top K。

---

## 5. 评测集与自动化验证结果

针对 RAG 模块构建了完整的单元测试集与端到端黄金评测集（`CodeRetrieverGoldenSetTest`），共计 24 项专项测试：

| 测试用例类 | 测试项数 | 覆盖能力 | 测试结果 |
|------------|---------|----------|----------|
| `CodeChunkerTest` | 3 | Java AST 类/方法分块、非 Java 文本降级分块、Embedding 文本格式 | ✅ PASS |
| `VectorStoreTest` | 5 | SQLite 向量相似度计算、关键词匹配、关系存储、增量哈希增删改查 | ✅ PASS |
| `CodeIndexTest` | 4 | 路径校验、全量构建、增量跳过未改动文件、状态监听与清空 | ✅ PASS |
| `CodeAnalyzerTest` | 1 | AST 关系分析（extends, implements, contains, imports） | ✅ PASS |
| `EmbeddingClientTest` | 4 | 配置加载、空输入安全返回、Fake 模式确定性向量一致性 | ✅ PASS |
| `SearchResultFormatterTest` | 1 | CLI 可读摘要结构、Top 入口解析、代码截断保护 | ✅ PASS |
| `CodeRetrieverTest` | 1 | 混合检索关键词与双重命中加权算法验证 | ✅ PASS |
| `SearchCodeToolTest` | 2 | 工具未索引拦截指引、索引后 Agent 工具输出格式与数据节点 | ✅ PASS |
| `CodeRetrieverGoldenSetTest` | 3 | 多模块自然语言问答黄金集、跨文件方法召回、增量修改即时召回 | ✅ PASS |
| **汇总** | **24** | **全链路覆盖** | **100% 通过 (0 失败)** |

全工程回归测试统计：
- **总测试数**：223 项
- **Failures**：0
- **Errors**：0
- **Skipped**：0
- **全量回归耗时**：~3.7 秒

---

## 6. CLI 指令与 Agent 工具集成

1. **CLI 交互指令**：
   - `/index`: 增量索引当前工程代码库。
   - `/index status`: 查看已索引文件数、代码块数、代码关系数。
   - `/index clean`: 清空当前项目向量与关系索引。
   - `/search <query>`: 统一自然语言混合检索。
   - `/search-text <pattern>`: 基于 ripgrep 的精确正则/文本检索。
2. **Agent 工具**：
   - `search_code`: 注册到 `ToolRegistry`，并作为 `Code Exploration Pipeline` 的首选自然语言探索工具提供给 LLM。
