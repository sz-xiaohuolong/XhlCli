# 长期记忆召回实施计划

范围依据：[PRD](../prd/memory-recall.md)；接口和取舍：[设计](../specs/2026-11-06-memory-recall.md)；执行状态：[PROGRESS](../PROGRESS.md)；结果：[验收](../engineering/memory-recall-verification.md)。

## 1. 前置与命令

已获主 ReactAgent 实现、兼容可选字段、测试后同步 origin/main 的授权。技术基线已核对 Java 21 / Maven / Jackson / JUnit，无新依赖。初始稳定点 fafea51；保留其他会话远程更新，不强推。测试使用临时目录和假模型，禁止访问真实记忆、密钥或发送外部消息。

计划运行：各切片 `./mvnw test -Dtest=...`；整体验收 `./mvnw verify`；JAR `--help` / `--version`；本地假服务端终端验收（脚本见证据）。

## 2. 文件映射

| 位置 | 修改职责 |
|---|---|
| memory/MemoryEntry、LongTermMemory、MemoryManager | 可选字段、兼容读取、诊断、召回入口 |
| memory/MemoryQuery、MemoryRecallResult、MemoryRetriever、MemoryRecallSettings | 新增不可变契约、排序/节选/预算、配置 |
| context/TokenBudget、ContextAssembler | 真实请求预算、临时资料注入 |
| agent/ReactAgent、cli/ChatBootstrap、ChatLoop、帮助文本 | 主 Agent 一轮生命周期、配置、诊断 |
| src/test/java 下对应测试，src/test/resources/memory | 行为回归、标注评测数据 |
| scripts/memory_recall_acceptance.py | 真实 JAR + 本地假模型验收 |
| README、TECH_DESIGN、PROJECT、PROGRESS、engineering | 使用、边界、导航、进度和证据 |

## 3. 串行任务

| 任务 | 交付与步骤 | 依赖 | 验收 |
|---|---|---|---|
| TASK-M01 | 先写旧格式/损坏/生命周期失败回归，扩展 record 和安全读取；无迁移 | 基线已核对 | AC-M01、03、06 |
| TASK-M02 | 建立检索失败样例，实现匹配、去重、节选与可解释结果，验证配置参数 | M01 | AC-M02、07 |
| TASK-M03 | 先用捕获请求验证一轮复用、历史隔离和预算，再接入主 Agent/CLI | M02 | AC-M04、05、06 |
| TASK-M04 | 编写约 30 题人工可复核金标，比较全量/召回；完成针对性代码审查与 JAR 验收 | M03 | AC-M08 及所有行为 |
| TASK-M05 | 同步事实文档，verify 与证据检查后 feat 提交，核对远程 SHA/CI | M04 | 推送与说明 |

初始就绪任务 M01；每个依赖切片验证后才能继续。任务共享核心契约，串行实施。

## 4. 测试接缝

存储测试使用旧 JSONL 与坏行、目录替代文件模拟读取故障。检索测试固定 Clock，防止过期边界随时间变化。Agent 测试在首个工具执行中变更磁盘记忆，观察同轮二次请求及下一轮；捕获发给 LlmClient 的消息与 history 对比。预算覆盖系统/工具 schema/图片/工具结果增长和无法容纳任何记忆。评测集与程序分别维护预期 ID 与算法，不用算法生成预期结果。

## 5. 交付与恢复

先审查受影响路径再做整体验证；失败只修复相关问题并重验失效证据。提交仅本任务文件，不打 Tag、不发版本。远程领先则合并后评估测试证据是否需刷新，不能覆盖其他提交。无 JSONL 迁移；回滚与字段兼容限制见设计。

## 6. 里程碑

兼容读取 → 可解释召回 → 主路径预算闭环 → 评测与人工验收脚本 → 验证后推送。实际结果只记在 PROGRESS/验收，不在此复制状态。
