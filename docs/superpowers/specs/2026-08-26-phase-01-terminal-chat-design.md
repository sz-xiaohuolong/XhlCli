# Phase 01 终端对话技术设计

> 日期：2026-08-26  
> 状态：已完成方案评审，等待实施批准  
> 对应需求：`docs/prd/phase-01-terminal-chat.md`

## 1. 目标与边界

Phase 01 交付一个只具备文本对话能力的 XhlCLI：用户配置 DeepSeek 后，可以在终端中进行流式多轮对话、查看非敏感配置、清空会话和安全退出。

本期不引入 ReAct、Tool Call、本地文件或命令工具、多 Provider 切换、会话持久化、Markdown TUI 和模型私有思维展示。模型输出不会获得任何本地工具权限。

## 2. 参考实现采用策略

选择性采用已授权 Paicli 实现，不复制最终源码树：

| 参考提交或文件 | 采用内容 | 主动差异 |
|---|---|---|
| `e2b8df4` `cli/Main` | 交互循环和退出/清理命令的基本流程 | 使用 XhlCLI 品牌、斜杠命令和可测试边界 |
| `530bb9c` 流式客户端 | SSE 按行读取、增量回调和 Token 汇总 | 补齐断流检测、错误分类和取消 |
| `f49d33c` `LlmClient`、`AbstractOpenAiCompatibleClient`、`DeepSeekClient` | OpenAI-compatible 消息序列化、DeepSeek Bearer 鉴权和模型适配 | 删除工具调用、多 Provider 和 reasoning 展示 |
| Paicli 当前 `DeepSeekClient` | HTTP/1.1 兼容处理、当前端点和模型默认值 | 超时改为实例配置，避免隐藏全局状态 |

迁移后的包名统一为 `com.xhlcli`，用户目录统一为 `~/.xhlcli`。不迁移参考仓库工作区现有未提交改动、Git 历史、运行数据和品牌资源。

## 3. 架构与依赖方向

```text
cli/Main + CliApplication
        │
        ├── config/ChatConfigLoader ──> ChatConfig
        │
        └── app/ChatLoop ──> ChatSession ──> llm/LlmClient
                  │                 │             │
                  └── render/PlainChatRenderer    └── DeepSeekClient
                                                    └── OkHttp + Jackson
```

- `config` 只负责加载、合并、校验和描述配置来源。
- `llm` 只负责统一消息、HTTP/SSE 协议、取消和 Provider 错误映射。
- `app` 只负责会话历史、命令语义和事件顺序，不直接访问环境变量或 HTTP。
- `cli` 负责参数、终端输入和生命周期装配。
- `render` 把聊天事件转换为 Plain 文本，核心逻辑不直接写 stdout/stderr。

## 4. 核心模型与接口

### 4.1 消息与响应

`ChatMessage` 是不可变值对象，角色只允许 `system`、`user`、`assistant`，内容不得为空白。Phase 01 不定义 tool role。

新会话和 `/clear` 后都只保留一条固定 system message：`You are XhlCLI, a helpful coding assistant. In Phase 01 you have no tools and must not claim to inspect or modify local files.`

`ChatResponse` 包含完整 assistant 文本和 `TokenUsage`。TokenUsage 分别表示输入、输出和是否已知；Provider 未返回 usage 时界面显示 `unknown`，不用零冒充未知。

### 4.2 流式客户端

`LlmClient.stream(messages, listener, cancellationToken)` 同步完成一次请求：

- `listener` 只接收用户可见文本增量；
- `cancellationToken` 注册 OkHttp `Call.cancel()` 回调；
- 成功时返回完整 `ChatResponse`；
- 失败时抛出带稳定分类的 `LlmException`；
- 不暴露 Provider JSON、Authorization 或模型私有 reasoning。

`LlmErrorType` 至少包含：`MISSING_CONFIGURATION`、`AUTHENTICATION`、`RATE_LIMIT`、`NETWORK`、`SERVER`、`INVALID_RESPONSE`、`TIMEOUT`、`CANCELLED` 和 `INVALID_CONFIGURATION`。

### 4.3 聊天事件

应用层产生 `ChatEvent`：等待开始、文本增量、响应完成、失败和取消。Renderer 消费事件并串行输出。Phase 02 可以继续消费相同的文本事件，并在其上增加模型/工具事件。

## 5. DeepSeek HTTP 与 SSE

- 默认 Base URL：`https://api.deepseek.com`。
- 实际地址：规范化 Base URL 后追加 `/chat/completions`；已经包含该路径时不重复追加。
- 默认模型：`deepseek-v4-flash`。
- 请求使用 `Authorization: Bearer <key>`、`Content-Type: application/json`、`stream: true`。
- 消息按当前顺序发送，不包含工具定义和 reasoning 历史。
- SSE 只处理 `data:` 字段；空行和未知字段安全忽略。
- 每个 `choices[0].delta.content` 立即产生文本增量。
- 最后一个 chunk 的 usage 用于 TokenUsage；缺失时标记未知。
- 收到 `data: [DONE]` 才视为完整结束；连接提前关闭标记为不完整响应。
- Provider 返回 reasoning 字段时解析器忽略，不输出也不保存。

DeepSeek 使用 OpenAI-compatible API；官方当前提供 `deepseek-v4-flash`，因此该名称作为 2026-08-26 的阶段默认值。用户可通过配置覆盖模型和 Base URL。

## 6. 配置与秘密管理

配置优先级从高到低为：

1. 命令行中的非敏感参数；
2. 进程环境变量；
3. 项目根目录 `.env`；
4. `~/.xhlcli/config.json`；
5. 内置默认值。

支持字段：

| 字段 | 环境变量 | 默认值 |
|---|---|---|
| API Key | `DEEPSEEK_API_KEY` | 无，缺失时禁止请求 |
| 模型 | `DEEPSEEK_MODEL` | `deepseek-v4-flash` |
| Base URL | `DEEPSEEK_BASE_URL` | `https://api.deepseek.com` |
| 连接超时 | `XHLCLI_CONNECT_TIMEOUT_SECONDS` | 30 秒 |
| 读取超时 | `XHLCLI_READ_TIMEOUT_SECONDS` | 300 秒 |
| 整体超时 | `XHLCLI_REQUEST_TIMEOUT_SECONDS` | 600 秒 |
| 日志级别 | `XHLCLI_LOG_LEVEL` | `WARN` |

命令行提供 `--model`、`--base-url`、`--connect-timeout`、`--read-timeout`、`--request-timeout` 和 `--log-level`。API Key 不提供命令行参数，避免出现在 shell 历史和进程列表中。

`.env.example` 只包含占位值和获取方式；真实 `.env` 已被 Git 忽略。`/config` 显示 Provider、模型、Base URL、超时和来源，只把 Key 显示为 `configured (source)` 或 `missing`。任何异常、日志和测试失败信息都经过已知 Key、Bearer Token 和常见凭据键脱敏。

`~/.xhlcli/config.json` 只保存模型、Base URL、超时和日志级别等非敏感字段，不接受或写回 API Key。API Key 的实际优先级固定为进程环境变量 > 项目 `.env` > 缺失，避免秘密长期落入普通 JSON 配置。

Base URL 在创建客户端前校验 URI、scheme 和 host。生产默认使用 HTTPS；为 MockWebServer 和用户显式的本地兼容服务允许 HTTP。

## 7. 终端交互与命令

无参数启动进入聊天模式；`--help` 和 `--version` 保持 Phase 00 契约。启动聊天前展示 Provider 与模型，不展示 Key。

基础命令：

- `/help`：显示本期命令；
- `/config`：显示脱敏配置和来源；
- `/clear`：清空 user/assistant 历史并恢复初始 system message；
- `/exit`：关闭当前客户端和终端后退出。

空白输入不发送请求。普通用户输入和成功的 assistant 回答成对加入历史；请求失败、超时、取消或断流时不提交本轮历史，避免产生不完整消息序列。

应用使用轻量 JLine Terminal 处理 Ctrl+C：

- 请求进行中：取消对应 OkHttp Call，显示取消提示并回到输入；
- 空闲输入中：第一次 Ctrl+C 清空当前输入并继续；
- `/exit` 和 EOF：走同一关闭路径。

本期只使用 Plain 输出，不引入全屏 TUI、状态栏、Markdown 渲染和补全。

## 8. 错误、重试与部分输出

- 缺少 Key：请求前失败并提示在 `.env` 设置 `DEEPSEEK_API_KEY`。
- 401：鉴权失败，提示检查或重新签发 Key。
- 429：限流，提示稍后重试。
- 500/503 等 5xx：服务端错误，提示稍后重试。
- 连接/DNS 失败：网络错误，提示检查网络和 Base URL。
- 读取或整体超时：超时错误，提示调整网络或超时配置。
- JSON/SSE 不合法、空响应或缺少 `[DONE]`：响应格式错误。
- 用户取消：取消类型，不显示异常堆栈。

只在尚未产生任何文本增量时，对网络错误、429 和 5xx 自动重试一次；固定等待 250 毫秒。401、参数错误、格式错误、超时、取消和已经产生部分文本后的失败不重试。

响应中途失败时，已输出文本保留在终端，并追加“响应未完整结束”标记；该轮不进入会话历史。

## 9. 测试策略

自动测试不读取真实用户主目录、不依赖网络或真实 API Key：

- 配置单元测试：优先级、`.env` 解析、默认值、非法 URI/超时和来源；
- 消息测试：合法角色、空内容拒绝和历史顺序；
- SSE 集成测试：正常多 chunk、usage、未知事件、空内容、非法 JSON 和断流；
- HTTP 错误测试：401、429、5xx、网络失败和一次重试边界；
- 取消/超时测试：延迟 MockWebServer、Call 取消和可继续下一轮；
- ChatSession 测试：多轮顺序、`/clear`、失败不提交历史；
- CLI 测试：命令、空输入、脱敏输出、`--help`、`--version` 和未知参数。
- 跨平台 CI：Ubuntu、macOS 和 Windows 均使用 Java 21 运行测试与 JAR 冒烟；JLine 取消逻辑通过可调用的信号处理边界做确定性测试。

阶段门禁：

1. `./mvnw clean verify` 全部通过；
2. 可执行 fat JAR 的帮助、版本、缺 Key 和 Mock/真实聊天路径通过；
3. Java 字节码版本为 65；
4. 日志和仓库敏感信息扫描通过；
5. 用户在本地 `.env` 填写 Key 后，真实 DeepSeek 完成五轮短对话；
6. 真实请求期间 Ctrl+C 能取消并继续输入；
7. 使用本地 Mock 流录制不包含真实 Key、用户路径和网络依赖的终端演示 GIF；
8. GitHub Actions 的 Ubuntu、macOS 和 Windows Java 21 矩阵通过后才创建 `v0.2.0` 标签。

真实验证不打印、回显、提交或上传 Key。测试完成后 `.env` 继续只保留在用户本机。

## 10. 文件落点

预计新增或修改：

```text
pom.xml
.env.example
src/main/java/com/xhlcli/
  app/
  cli/
  config/
  llm/
  model/
  render/
src/test/java/com/xhlcli/
  app/
  cli/
  config/
  llm/
README.md
ROADMAP.md
CHANGELOG.md
AGENTS.md
docs/prd/phase-01-terminal-chat.md
docs/plans/2026-08-26-phase-01-terminal-chat.md
docs/assets/xhlcli-phase-01-demo.gif
```

只在实际实现需要时创建文件，不建立 Phase 02+ 空包或占位接口。

## 11. 发布与完成定义

Phase 01 完成时版本提升为 `0.2.0`，README 明确“已支持终端文本对话，但不具备 Agent 或本地工具权限”。更新 CHANGELOG、ROADMAP、AGENTS 和 Phase 01 PRD 状态。

离线测试通过后暂停，由用户填写 `.env`。真实五轮对话、取消验证、敏感信息检查、本地最终构建和远程 CI 全部通过后，推送 `main` 并创建 `v0.2.0` 标签。
