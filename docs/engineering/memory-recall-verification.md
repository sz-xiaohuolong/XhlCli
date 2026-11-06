# 长期记忆召回第一版验收与使用说明

日期：2026-11-06。需求：[REQ-M01～05 / AC-M01～08](../prd/memory-recall.md)；实现决策：[设计](../specs/2026-11-06-memory-recall.md)；任务：[计划](../plans/2026-11-06-memory-recall.md)。

## 1. 交付结论与边界

主前台 ReactAgent 已实现程序自动检索、相关性排序及有界注入，保留 JSONL，不新增依赖或模型调用。TASK-M01～05 已完成；604 项测试和真实 JAR 离线验收通过。实现提交 `730810de464733f30a4179ef1d99629a3a431ed7` 已推送 origin/main，远程 SHA 核对一致；GitHub CI `42198339753` 成功，覆盖 Verify 与 JAR 冒烟。当前状态见 [PROGRESS](../PROGRESS.md)。本次不打 Tag、不发布新版本，JAR 版本仍为 1.0.1。

Plan、Team、后台独立 Agent 未接入本次召回；自动路由到 Team 的请求不能据此宣称已覆盖。以下主对话验收使用 `--team-mode manual`。真实模型答案是否漏掉关键约束仍待人工验收，AC-M08 的离线部分完成，模型质量部分尚未验证。

## 2. 可复现证据

基线为 `fafea5135596cb81db3cc8a5f00f1fa18efa851d`。本地 Java 21.0.8，Maven Wrapper，Jackson 2.22.2，JUnit 5.10.2。测试使用临时文件、固定时钟和本地假模型，没有读取真实用户记忆或发送真实模型请求。

| 检查 | 实际结果 | 证明范围 |
|---|---|---|
| `./mvnw test -Dtest=MemoryRecallTest,MemoryRecallEvaluationTest,MemoryRecallIntegrationTest` | 19 项通过 | 排序修复、评测与主 Agent 行为 |
| `./mvnw verify` | 604 项，0 失败、0 错误、0 跳过，BUILD SUCCESS | 全量单元/集成与打包 |
| `java -jar target/xhlcli-1.0.1.jar --help` / `--version` | 均退出 0，版本 XhlCLI 1.0.1 | 成品启动和元信息 |
| `python3 scripts/memory_recall_acceptance.py` | 退出 0；4 轮恰好 4 次本地模型请求 | 真实 JAR、CLI、序列化请求和磁盘变化 |
| GitHub CI `42198339753` | success，HEAD 为 `730810d` | 远程 macOS / Java 21 全量 Verify 与 JAR 冒烟 |
| 针对性独立代码审查 | 发现的两项问题已修复并复核闭合 | 工具定义快照预算一致性、最近实际注入诊断 |

命令必须按表中先构建、后 JAR 验收的顺序执行；构建期间的中间 JAR 不可用于验收。脚本位于 [scripts/memory_recall_acceptance.py](../../scripts/memory_recall_acceptance.py)，自动创建并清理临时 home、A/B 工作区和本地 SSE 服务。原始构建日志在本轮 `/tmp/xhlcli-memory-verify.log`，不提交机器临时日志；可用上述命令重建。

### 问题修复证据

- MCP 异步更新可能使预算检查与实际发送使用不同工具定义。现在每次请求冻结同一快照；回归覆盖检查后工具变大，仍按检查过的快照发送。
- 后续请求因预算被拒绝时曾清空上一已发送的记忆诊断。现在仅实际发送前更新，预算拒绝保留上一快照。另补跨轮拒绝的失败回归，移除 run 开头的诊断清空，保证新一轮未发送时也保留最近实际结果；显式 `/clear` 仍清空。
- JAR 验收发现“Redis 再解释一次”被中文重叠片段稀释至零召回；“Java 版本要求是什么”同样受影响。先加入失败回归，再将中文片段合计权重归一，并对当前技术标识符设置最低匹配门槛。四个排序回归及完整 JAR 流程均通过；未修改金标来掩盖漏召回。

## 3. AC 证据矩阵

| 验收 | 实际证据 | 结果 |
|---|---|---|
| AC-M01 项目隔离 | MemoryRecallTest 的 A/B/全局及路径查询；JAR 中 B_SECRET 不入请求 | 通过 |
| AC-M02 相关性 | Redis/Java、中文/技术词、Kubernetes/OAuth 零召回回归；下方逐题结果 | 通过基线行为，保留同义局限 |
| AC-M03 生命周期/兼容 | LongTermMemoryTest、MemoryRecallTest；JAR 旧字段、过期 pinned、supersededBy | 通过 |
| AC-M04 一轮复用/历史 | MemoryRecallIntegrationTest 在工具执行中改磁盘，同轮不变、下轮刷新；捕获 history；JAR 请求只一份临时资料 | 通过 |
| AC-M05 真实预算 | MemoryContextBudgetTest 的系统/工具/图片/精确边界；Agent 工具增长缩减与基础请求超限；MCP 快照回归 | 通过项目估算边界 |
| AC-M06 错误降级 | 坏行/null/空内容跳过；目录代替文件引发读取失败；JAR 故障轮正常回答且无动态资料 | 通过，无全量回退 |
| AC-M07 去重/节选/固定 | MemoryRecallTest 验证去重、完整命中段落、包装开销、pinned 共用上限 | 通过 |
| AC-M08 评测/回答质量 | 30 题固定金标与指标；真实模型人工检查见第 5 节 | 离线完成，回答质量待人工验收 |

## 4. 30 题离线评测

数据来源：人工编写的项目代表性场景，非真实用户日志；预期 ID 独立标注，供人工复核。

固定配置：5 条 / 1500 估算 Token / 最低相关性 0.18。计量包含资料包装，不包含其他系统提示词、工具和对话。

| 方法 | 命中/应召回 | 召回率 | 无关条目总数 | 注入条目总数 | 平均记忆 Token |
|---|---|---|---|---|---|
| 原全量注入 | 30/30 | 100% | 570 | 600 | 386 |
| 文本按需召回 | 28/30 | 93.33% | 2 | 30 | 62.0 |

词法基线不会自动理解同义表达。下表保留漏召回与误召回；真实模型答案是否遗漏关键约束仍需人工验收。

| 问题 | 预期 ID | 实际 ID | 漏召回 | 无关条数 | 召回/全量 Token |
|---|---|---|---|---|---|
| Q01 为什么当初没有采用 Redis？ | [redis-decision, redis-proposal] | [redis-decision, redis-proposal] | [] | 0 | 100/386 |
| Q02 Redis 部署复杂度是什么？ | [redis-decision] | [redis-decision, redis-proposal] | [] | 1 | 100/386 |
| Q03 Redis 缓存讨论进展 | [redis-proposal] | [redis-decision, redis-proposal] | [] | 1 | 100/386 |
| Q04 Java 版本要求是什么？ | [java] | [java] | [] | 0 | 62/386 |
| Q05 Java 17 能运行吗？ | [java] | [java] | [] | 0 | 62/386 |
| Q06 如何执行 ./mvnw verify？ | [build] | [build] | [] | 0 | 71/386 |
| Q07 target/xhlcli-1.0.1.jar 在哪里？ | [build] | [build] | [] | 0 | 71/386 |
| Q08 长期记忆为什么保留 JSONL？ | [jsonl] | [jsonl] | [] | 0 | 65/386 |
| Q09 SQLite 保存了什么？ | [sqlite] | [sqlite] | [] | 0 | 62/386 |
| Q10 MCP 启动超时怎么办？ | [mcp] | [mcp] | [] | 0 | 61/386 |
| Q11 Side-Git 快照会污染 Git 历史吗？ | [snapshot] | [snapshot] | [] | 0 | 66/386 |
| Q12 后台允许 execute_command 吗？ | [background] | [background] | [] | 0 | 71/386 |
| Q13 后台能调用 write_file 吗？ | [background] | [background] | [] | 0 | 71/386 |
| Q14 global 和 project 记忆分别来自哪里？ | [scope] | [scope] | [] | 0 | 66/386 |
| Q15 如何使用 /git-feature-workflow？ | [skill] | [skill] | [] | 0 | 72/386 |
| Q16 load_skill 做了什么？ | [skill] | [skill] | [] | 0 | 72/386 |
| Q17 同步 origin/main 后需要打 Tag 吗？ | [release] | [release] | [] | 0 | 64/386 |
| Q18 ~/.xhlcli/config.json 保存什么？ | [config] | [config] | [] | 0 | 70/386 |
| Q19 API Key 的环境变量优先吗？ | [config] | [config] | [] | 0 | 70/386 |
| Q20 上下文预算要算工具定义吗？ | [budget] | [budget] | [] | 0 | 67/386 |
| Q21 ERR-404 如何排查？ | [error] | [error] | [] | 0 | 62/386 |
| Q22 WorkspacePathResolver 如何处理 ../？ | [path] | [path] | [] | 0 | 68/386 |
| Q23 用户喝咖啡有什么偏好？ | [coffee] | [coffee] | [] | 0 | 57/386 |
| Q24 JUnit 和 MockWebServer 用在哪里？ | [tests] | [tests] | [] | 0 | 66/386 |
| Q25 取消任务要等执行确认吗？ | [cancel] | [cancel] | [] | 0 | 64/386 |
| Q26 刚才那个为什么没有采用？ | [redis-decision, redis-proposal] | [redis-decision, redis-proposal] | [] | 0 | 100/386 |
| Q27 如何部署 Kubernetes？ | [] | [] | [] | 0 | 0/386 |
| Q28 如何配置 OAuth 回调？ | [] | [] | [] | 0 | 0/386 |
| Q29 响应慢该怎么解决？ | [latency] | [] | [latency] | 0 | 0/386 |
| Q30 重启后怎样找回作业进度？ | [sqlite] | [] | [sqlite] | 0 | 0/386 |

数据与复现：[recall-golden.json](../../src/test/resources/memory/recall-golden.json)、[评测程序](../../src/test/java/com/xhlcli/memory/MemoryRecallEvaluationTest.java)。表格来自本次全量验证生成的 `target/memory-recall-evaluation.md`。共 20 条记忆、30 题；“无关条数”按该题预期 ID 之外的条目计，即使同属 Redis 主题也计入，避免掩盖误召回。

新基线平均 Token 下降约 84%，漏掉 2/30 个应召回记录，无关注入为 2 条。Q29“响应慢”与“延迟优化”、Q30“作业进度”与“SQLite 任务状态”缺乏足够词项重合；不以调低阈值或凑满条数掩盖。当前有技术标识符时必须至少命中一个，也可能漏掉别名或纯中文说明。这些结果只适用于该人工样例集，不等同于线上真实问题效果。

## 5. 人工验收方案

### 5.1 隔离环境与启动

在仓库根目录完成 `./mvnw verify` 后，准备临时工作区。通过现有环境变量提供有效的模型凭据；隔离 home 不会继承真实用户配置文件。下列变量不覆盖 HOME 或项目文件：

```bash
memory_jar="$PWD/target/xhlcli-1.0.1.jar"
memory_demo="$(mktemp -d)"
mkdir -p "$memory_demo/home" "$memory_demo/A" "$memory_demo/B"
(cd "$memory_demo/A" && java -Duser.home="$memory_demo/home" -Dxhlcli.task.dir="$memory_demo/tasks" -jar "$memory_jar" --team-mode manual)
```

验收同一数据集时记录模型、配置、问题、预期记忆 ID、预览/实际召回 ID、实际记忆 Token、是否漏掉答案关键约束。记忆文件分别位于临时 home 下 `.xhlcli/memory/long_term.jsonl` 和工作区 `.xhlcli/memory/long_term.jsonl`。

### 5.2 主路径操作与预期

1. 在 A 输入 `/save 由于部署复杂，最终没有采用 Redis。`、`/save 项目使用 Java 21。`、`/save --global Redis 讨论应说明部署成本。`。执行 `/memory recall 为什么没有使用 Redis？`，应显示两个 Redis 记录的来源、分数与命中项，不出现 Java，不触发模型回答。
2. 输入“为什么没有采用 Redis？请依据历史资料回答，不执行工具。”，随后 `/context`。应看到最近实际注入的 Redis 资料和估算 Token；人工核对回答包含部署成本且未把历史参考当作当前授权。预览只按配置预算、不补近期消息，实际请求还受上下文空间影响，以 `/context` 为准。
3. 再 `/save Redis 后续决定先做本地缓存实验。`，下一轮问“Redis 后续决定是什么？”，`/context` 应看到新增记忆；连续提问不应导致资料块累计。程序层的同轮多调用复用及 history 不污染由集成测试直接证明，不能仅凭模型回复判断。
4. `/clear` 后执行 `/memory recall OAuth 如何配置？`，本样例应零召回；再正常问同一问题，应正常对话。`/clear` 不删磁盘记忆，只清历史和最近召回诊断。
5. 退出后用同一个临时 home 从 B 启动并保存“Redis 的 B 项目专用决定”。B 不应看到 A 的两条项目记忆；重新从 A 启动不应看到 B 记录；两边均可见全局 Redis 记录。

### 5.3 生命周期与预算

在退出程序后，仅编辑临时 JSONL：一条 Redis 记录设置 `expiresAt` 为过去日期；另一条设置 `supersededBy` 为新记录 ID；另加一条有效的 `pinned:true` 记录。重新预览时前两条必须排除，固定记忆优先但仍受总条数/Token 预算。字段示例：`"expiresAt":"2020-01-01T00:00:00Z"`、`"supersededBy":"新记录ID"`、`"pinned":true`。不要让模型推断或自动填写失效时间。

在启动前设置 `XHLCLI_MEMORY_MAX_ENTRIES=1`、`XHLCLI_MEMORY_TOKEN_BUDGET=100`，重复查询，结果必须至多一条且包装估算不超过 100；完全容纳不了时可以零条。换成 0 应关闭注入。追加一行坏 JSON 应显示跳过诊断而保留有效项；读取失败用离线脚本复现，不必破坏真实数据。基础上下文超限与工具结果增长见自动化测试，不用实际付费模型制造超长请求。

### 5.4 真实问题集复核

用户已确认没有现成真实问题数据，授权自行设计评测；当前 30 题为人工编写的代表性场景，不冒称真实日志，也不以补充数据作为本次交付前提。人工可直接复核该集并逐题让真实模型回答；后续获得脱敏真实问题时，再独立标注预期 ID，沿用相同格式评测。重点核查：漏召回是否损害关键约束、误召回是否误导当前结论、来源与时间是否被正确理解、同义漏召回是否需要后续语义检索。此项结果应另记实际证据，不能将本轮离线通过升级成真实模型质量通过。

## 6. 恢复与后续边界

本版不迁移或重写既有 JSONL；回滚前备份含新增可选字段的数据，旧程序不保证识别这些字段。配置条数或预算为 0 可停用主路径动态注入。后续 search_memory 工具、语义检索、Plan/Team/后台接入需各自实现和验收，本次没有自动安排后台交付。
