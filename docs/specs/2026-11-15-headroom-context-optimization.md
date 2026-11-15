# Headroom 工具正文压缩方案

Status：`IMPLEMENTED`（实现及真实本机服务联调已核验；真实模型与桌面 GUI 尚待人工验收，详见验收记录）。关联：[需求](../prd/headroom-context-optimization.md) · [实施计划](../plans/2026-11-15-headroom-context-optimization.md) · [验收](../engineering/headroom-context-optimization-verification.md)。日期：2026-11-15。

## 1. 背景与设计目标

当前 `ReactAgent` 先检查 `workingHistory` 的预算，再调用 `LlmClient.stream`；前台开启记忆召回后，超限直接停止。`ContextAssembler.withRecall` 在请求副本中加入记忆；`ToolResultBudget` 已限制观察结果长度。目标是在预算检查前增加可关闭的优化步骤，保留上述职责和原始历史。

采用 Headroom `/v1/compress` 的 `config.mode=lossless`。不让代理接管 Provider 请求，因此现有 SSE 流式输出、推理字段、凭据、模型路由和取消机制继续由 Java 客户端负责。参考源码固定为上游 `5023c84e527e28281dd73d1ba04184fe738aac0f`，项目版本字段 `0.44.0`；真实安装包的兼容性由验收记录核实，不能仅凭字段宣称运行通过。

## 2. 技术栈与工程约定

| 位置 | 技术与依据 | 本次处理 |
|---|---|---|
| Agent | Java 21，`pom.xml` release=21 | 复用，不增加 Java 依赖 |
| HTTP / JSON | OkHttp 5.5.0、Jackson 2.22.2，来自 pom | 复用既有取消和 JSON 模式 |
| 检查 | Maven Wrapper、JUnit 5.10.2、MockWebServer | 先失败用例，后最小实现 |
| 可选外部进程 | Headroom，Python 3.11+ | 用户独立启动，本项目不管理进程 |

实现放 `context/`，只依赖消息、取消令牌与已有 HTTP/JSON 库。CLI、桌面只装配配置和读取报告；不把终端、数据库或 Provider SDK 引入压缩器。不修改用户既有 AGENTS、研究和 Phase 20 草稿。

## 3. 架构与执行顺序

1. CLI 启动或桌面创建本轮 Agent 时读取配置；默认构造不联网的关闭实现。
2. 每次模型请求冻结工具定义，复制历史，定位有对应调用的 TOOL 消息。
3. `HeadroomContextOptimizer` 选取可压缩正文，生成独立的 assistant/tool 配对消息。调用 ID 为合成 ID，参数为 `{}`；原会话的规则、用户内容、推理、图片、参数均不发送。
4. 单次 POST `/v1/compress`，强制 `mode=lossless`、`protect_recent=0`，不提供 Provider Key、工具 schema、session 或其他启用功能。
5. 检查响应数量、顺序、角色、合成 ID、assistant 调用和文本类型；拒绝回取标记。只采用更短正文，回填原消息副本；观察对象只替换 `summary`，其他字段原样保留。
6. ReactAgent 在压缩结束后检查取消与运行超时，再用该副本进行基础预算检查、记忆组装和最终预算复核，随后调用原模型客户端。压缩结果不进入 committed history。
7. 报告记录最近一次优化尝试，不表示模型已经收到请求。CLI `/context` 读取；桌面将无正文的报告保存到聊天元数据，供后续 `/context` 读取。

受保护工具包括 `read_file`、`write_file`、`apply_patch`、`git_diff`、`load_skill`、`revert_turn`、`headroom_retrieve`，也匹配同名 MCP 后缀。其结果不发送。正文不足 256 字符时跳过；这是减少不必要 HTTP 往返的内部基线，不是收益保证。结构化观察只接受含文本 `summary`、`status` 和 `data` 的对象；数组、标量、未知对象及看似 JSON 却整体非法的观察跳过。输入和响应均完整解析，拒绝尾随内容。

结构化观察的 `data` 不压缩；局部 JSON 读取器使用 `BigDecimal` 解析小数，回填 `summary` 时保留其他字段及高精度数值含义，不更改共用 mapper 配置。重新序列化可能改变 JSON 空白或数字字面格式，字段值不变。

## 4. 配置、数据与接口

| 配置 | 默认值 | 校验 |
|---|---|---|
| `XHLCLI_HEADROOM_ENABLED` | `false` | 仅 true/false，忽略大小写和首尾空白 |
| `XHLCLI_HEADROOM_URL` | `http://127.0.0.1:8787` | http/https、127.0.0.1 / ::1 / localhost；仅根路径，无用户信息、query、fragment |
| `XHLCLI_HEADROOM_TIMEOUT_MS` | `3000` | 整次调用 100～30000 毫秒 |

关闭时忽略服务地址和超时，避免未启用配置影响原路径。启用后的配置错误不静默回退。localhost 固定解析为回环；禁用 HTTP 代理和重定向，不能通过跳转或 DNS 把工具正文发送到外部。

请求和响应均限制 4 MiB；超限跳过或回退，不整段记录异常或正文。每次调用绑定运行的 CancellationToken，并在完成后注销回调。禁用连接失败重试，同时将请求体标为只能发送一次，阻止 503 `Retry-After: 0` 等状态触发 HTTP 自动重发。API 响应至少包含 `messages`；token 指标由本地 `TokenBudget` 重估，不直接信任上游统计。

内部 `ContextOptimizer` 接收历史副本、当前 model、取消令牌，返回不可变消息列表和报告。报告包含固定状态、固定原因、本地估算前后值、变更条数、耗时。无数据库迁移；桌面旧会话缺少报告时显示尚无记录。清空 CLI 历史时清空报告。

## 5. 关键选择

| 方案 | 收益 | 限制 | 选择 |
|---|---|---|---|
| 压缩接口，预算前调用 | 可复核最终请求，不改变模型流式协议 | 需适配正文和报告 | 本次采用 |
| 改 Provider 地址为全量代理 | 初次配置少 | 超预算请求到不了代理，CCR 和流式需额外适配 | 不作为正式集成 |
| MCP 按需压缩 | 可补充回取工具 | 无法自动覆盖请求，新增模型工具往返 | 不纳入第一版 |

## 6. 安全、失败与观察

- 服务失败或候选不合法：使用原副本，固定原因写报告，再检查预算；错误正文不得进入历史、模型或诊断。
- 取消或运行超时：取消 HTTP，之后检查运行状态，不请求模型、不改写历史。
- 无节省：保留原字符串，报告无变化；不为了采用压缩而增加请求长度。
- 系统规则、用户输入、推理、图片及观察状态不由上游决定；仅接受候选工具正文。
- 第一版不做跨轮缓存，避免恢复历史与文件变化造成失效判断；Headroom 接口仅用 lossless，不创建 CCR 回取依赖。
- `/context` 说明结果是消息 token 估算；模型费用、缓存命中和质量需要单独 A/B，不把估算写成节省账单。

## 7. 验证与恢复

自动化覆盖 REQ-H01～H04 的配置、HTTP、响应破坏、预算前后、内循环、取消和入口装配；真实服务检查覆盖 AC-H10，不依赖真实 Provider Key。源码、配置与环境没有新变化时复用有效结果，不重复全量检查。

关闭 `XHLCLI_HEADROOM_ENABLED`，重启 CLI 或发起桌面下一轮即可回到原请求路径；没有压缩历史或数据库结构需要恢复。实际版本、命令、结果和未验证项统一写入验收记录；验证成立后再同步 TECH_DESIGN。
