# 长期记忆召回第一版设计

状态：IMPLEMENTED（用户已授权的设计已实现；验证范围见验收与进度）。范围以[需求](../prd/memory-recall.md)为准。现状依据：MemoryManager、LongTermMemory、ContextAssembler、ReactAgent 与 ChatBootstrap；后台 BackgroundTaskRunner 使用旧快照入口。

## 技术基线与边界

Java 21（pom 与本地 21.0.8），Maven Wrapper，Jackson 2.22.2，JUnit 5.10.2；无新增依赖。规则见根 AGENTS.md；目录限 memory/context、主 Agent 接入、必要 CLI 诊断、测试与文档。原生 `./mvnw verify` 与 JAR 冒烟沿用 CI。不修改 AGENTS，不引入额外规则文件。

## 数据与错误

MemoryEntry 增加 boolean pinned、可空 String expiresAt（ISO-8601 Instant）、可空 String supersededBy。保留五参数构造函数；旧记录缺少字段时采用 false/null/null。新读取方法返回有效条目及行号诊断、读取失败标记；读取失败丢弃该次部分结果。空白行允许；JSON null、缺字段、空内容、非法日期跳过。存储来源由读取文件确定并核验 scope；query 没有路径参数。仅读取配置绑定的两个文件，不扫描其他目录。

任何非空 supersededBy 都代表显式撤销旧记录，不要求目标仍在可见候选中；否则可能意外复活旧事实。不存在自动新旧覆盖逻辑。去重按内容去多余空白、忽略英文大小写；同分稳定按 scope/id 排序，不使用时间作为事实正确性依据。

## 核心接口与排序

- MemoryQuery：当前问题、最多两条近期用户消息、maxEntries、tokenBudget、minScore；工作区不在查询内。
- MemoryManager.recall(query)：安全读取两个范围；任一读取失败返回空动态记忆及诊断；委托 MemoryRetriever。
- MemoryRetriever：过滤 → 词项集合 → IDF 查询覆盖率 → pinned 优先排序 → 内容去重 → 包装预算选择。
- MemoryRecallResult：选择列表（原记录、实际节选、分数、命中词、是否节选）、包装估算 Token、读取失败/预算不足和安全诊断；不可变。

词项来自英文/数字/技术符号完整串与中文二/三字片段。一个技术词权重为 1；一个连续中文片段的所有独特二/三字词项合计权重为 1，避免重叠片段稀释技术词。重复词取最大权重，不因出现次数无限累加。当前输入归一为总权重 1；仅遇到明确指代词时加入最近两条用户消息，每条最多 512 字符，去掉当前输入已有词后，补充词合计归一为 0.35（当前无有效词时为 1）。不读取 assistant/tool 输出。

在上述查询权重上乘 IDF `1 + log((N + 1) / (df + 1))`，得分为命中权重之和除以全部查询权重之和。当前输入有非停用的英文/数字/技术标识符时，普通候选必须至少命中其中一个，避免只因“部署”把 Redis 召回给 Kubernetes；pinned 不受相关性门槛影响。该规则也可能漏掉技术词的别名或只有中文的相关记录，是词法基线的明确取舍。默认最低分 0.18，未声称参数最优。

默认总条数 5、总预算 1500，可用环境变量/项目 .env `XHLCLI_MEMORY_MAX_ENTRIES`、`XHLCLI_MEMORY_TOKEN_BUDGET`、`XHLCLI_MEMORY_MIN_SCORE` 调整（环境变量优先）。参数非法时报告配置错误，不静默退回全量模式。可以设条数/预算为 0 关闭注入。

## 一轮生命周期与预算

主 ChatBootstrap 改为绑定 MemoryManager 和召回配置。ReactAgent 在 run 开始、当前用户消息已构造后，读取一次；候选及选择结果只存本轮局部变量。每次模型请求由 ContextAssembler 复制原消息，插入独立 USER 角色的历史资料块（来源、时间、引用正文与参考提示），不提升记忆权限，不修改 workingHistory。

可用于输入的上限 = contextWindow − reservedForResponse − 实际工具定义估算；消息估算已含实际系统、对话和图片。记忆预算取配置上限与剩余空间的较小值，包含整个资料块和消息开销。每次请求后续工具结果增多时缩减已选记忆，优先保留 pinned，再保留高分项；最终再次检查完整消息与工具总量。每次调用冻结同一份工具定义快照用于预算和实际发送，防止 MCP 异步更新造成检查与发送不一致。

新主路径不为召回触发 LLM 摘要；基础请求超限返回 CONTEXT_BUDGET，保留当前问题、规则和已提交历史。旧 setMemorySupplier 与 assemble 路径保留供后台和旧调用方使用；本次不改变其全量语义。`/clear` 清理最近召回诊断，不删除磁盘记忆。

## 诊断与验证

`/memory recall` 是独立只读预览，不启动模型、不改变上一轮注入结果。`/context` 输出主 Agent 最近一次实际发送的结果；预算拒绝发送时保留上一份实际快照。预览不补充近期对话，也不扣除当前请求的其余上下文，因此应以实际请求诊断为准。读取错误只记录范围/行号/类别，不输出原始 JSON 或异常正文。结果为无相关/预算不足/读取失败时明确标示。

验证见[计划](../plans/2026-11-06-memory-recall.md)。用户引用的 Contextual Retrieval 文章强调候选选择与去重，但本版不采用其 embedding、LLM 文本增强或重排，效果不继承其研究结果。参考源：`https://www.anthropic.com/engineering/contextual-retrieval`；Java 文件读取契约：`https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html`。

## 恢复

不自动改写现有 JSONL。回滚新代码前备份带可选字段的记忆；旧程序可能不识别新增字段，不能宣称直接向后降级兼容。正常前向升级无需迁移。读取失败只禁用本轮动态记忆，下一轮重新读取。
