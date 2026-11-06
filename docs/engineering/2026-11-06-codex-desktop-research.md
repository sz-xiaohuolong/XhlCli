# XhlCLI 对齐 Codex 桌面端：需求调研

调研日期：2026-11-06（America/Los_Angeles）。结论：现有 Java Agent 核心可以复用，但桌面端还需要会话生命周期、实时事件和界面审批适配，不能直接把现有 Runtime API 当作完整聊天后端。

用户已确认的产品范围与待决验收口径见 [桌面端需求草案](../prd/desktop-codex-ui.md)。本文只保存事实、证据和技术建议，不代表技术方案已批准或功能已经验证。

## 1. 调研范围与证据强度

- 核对 `xhlcli/` 当前工作树中的源码、项目规则和相关文档。读取时 HEAD 为 `fafea5135596cb81db3cc8a5f00f1fa18efa851d`，包含其他任务的未提交改动；结论针对读取时的工作树，不宣称对应纯提交快照。
- 查阅 OpenAI 官方功能、项目、worktree 和 app-server 文档。旧 Codex 文档链接现重定向至 ChatGPT Learn，共用文档中部分描述覆盖多个产品入口；不据此推断用户安装版的全部界面。
- 原生桌面工具禁止访问 Codex 自身窗口，未取得当前安装版截图、版本号、主题与尺寸。没有绕过该限制。
- 本次为静态调查，未执行模型请求、运行测试、构建桌面程序或验证任何桌面功能。历史测试结果不计为本次验证。

## 2. 官方参考及适用范围

| 参考 | 核实内容 | 本项目如何使用 |
|---|---|---|
| [功能索引](https://learn.chatgpt.com/docs/features) | 官方展示项目、聊天和插件等功能入口 | 建立候选页面清单；不作为当前安装版的像素依据 |
| [项目与聊天](https://learn.chatgpt.com/docs/projects) | 项目组织聊天；支持重命名、置顶、归档等组织操作 | 提醒需求区分项目、聊天和执行状态；具体首版操作见 PRD |
| [Git worktrees](https://learn.chatgpt.com/docs/environments/git-worktrees) | 独立 worktree 用于同项目多聊天并行工作 | 用户已选择首版同目录串行，因此该能力留后续 |
| [Codex App Server](https://learn.chatgpt.com/docs/app-server) | 富客户端接口覆盖会话历史、审批和事件；有 thread/turn 生命周期 | 参考职责划分，不要求兼容 Codex 协议，也不替换 XhlCLI 引擎 |

上述资料只能证明公开描述的能力，不能证明桌面 UI 源码可直接复用。此次未取得或分析 Codex 桌面实现源码。

## 3. XhlCLI 能力与缺口

源码路径均相对仓库根目录；行号为本次读取时的定位。

| 能力 | 已核实事实 | 对桌面端的影响 | 证据 |
|---|---|---|---|
| Agent 运行 | 前台 Agent 支持本地工具、模型调用与取消；工具及依赖在 CLI 启动流程装配 | 可复用核心，需将装配与 JLine、终端渲染解耦 | `src/main/java/com/xhlcli/cli/ChatBootstrap.java:131`；`src/main/java/com/xhlcli/agent/ReactAgent.java:208` |
| 结构化事件 | 有文本增量、工具开始/结束、完成、失败、取消、用量及运行序号 | 是桌面时间线接入点；当前工具结束事件主要携带摘要，不等于完整输出存储 | `src/main/java/com/xhlcli/model/RunEvent.java:8` |
| Runtime 启动 | 有 HTTP 服务类；检索生产源码未发现它的实例化入口 | 需要可由桌面宿主管理的运行入口；类存在不等于安装后可连接 | `src/main/java/com/xhlcli/runtime/api/RuntimeApiServer.java:28`；`src/main/java/com/xhlcli/cli/Main.java:9` |
| Runtime 消息 | 调用 `TaskRunner.run(input)`，等待完整字符串后写一次 `message.delta` | 当前不是逐片段聊天输出；不能靠 UI 定时打印模拟真实流式 | `src/main/java/com/xhlcli/runtime/api/RuntimeApiServer.java:112` |
| SSE | GET 读取已有事件，发送固定长度响应后关闭；支持 `after` 游标 | 是有限事件拉取，需要新增真正的订阅及恢复契约 | `src/main/java/com/xhlcli/runtime/api/RuntimeApiServer.java:125` |
| 会话持久化 | SQLite 保存 thread ID、创建时间和事件；Agent 历史主要在内存 | 事件落盘不等于上下文可恢复；还缺项目、模型、消息和工具调用关系 | `src/main/java/com/xhlcli/runtime/api/RuntimeThreadStore.java:131`；`src/main/java/com/xhlcli/agent/ReactAgent.java:54` |
| 会话隔离 | Runtime 所有 thread 使用同一 TaskRunner，调用参数无 threadId | 必须建立按会话隔离的运行与历史绑定；单个 Agent 的 synchronized 不等于目录级互斥 | `src/main/java/com/xhlcli/runtime/api/RuntimeApiServer.java:20`、`:104`、`:112` |
| 审批 | `HitlHandler` 是可替换接口，现有实现读取终端输入 | 需建立审批请求、界面响应、失效与取消关联；Runtime 尚无审批端点 | `src/main/java/com/xhlcli/hitl/HitlHandler.java:14`；`src/main/java/com/xhlcli/hitl/TerminalHitlHandler.java:72` |
| 后台任务 | 有持久只读队列、工作区隔离、取消确认和失联处理 | 可参考生命周期；不能直接用作可写编程会话或常驻服务 | `src/main/java/com/xhlcli/runtime/task/BackgroundTaskRunner.java:48`；`src/main/java/com/xhlcli/runtime/task/DurableTaskManager.java:133` |
| 项目管理 | 路径保护、项目规则和工具装配围绕项目根目录；CLI 从 cwd 启动 | 需补桌面项目列表和会话到工作目录的绑定 | `src/main/java/com/xhlcli/cli/Main.java:11`；`src/main/java/com/xhlcli/cli/ChatBootstrap.java:107` |
| 差异查看 | `GitDiffTool` 执行普通 `git diff`，展示未暂存差异 | 不覆盖完整 staged/untracked 状态，也不能证明改动归属本轮 | `src/main/java/com/xhlcli/tool/local/GitDiffTool.java:51` |
| 快照恢复 | 轮次前后创建 Side-History 快照，恢复时将目标树写回工作目录 | 不是独立 worktree；直接恢复旧树不能满足保护后来用户修改的撤销要求 | `src/main/java/com/xhlcli/snapshot/SnapshotService.java:33`、`:73`；`src/main/java/com/xhlcli/snapshot/SideGitManager.java:103` |
| 模型设置 | 现有配置读取与 CLI 模型切换可复用；启动流程仍先检查 DeepSeek Key | 桌面初始化必须允许配置其他 Provider，不能无关地被 DeepSeek Key 阻断 | `src/main/java/com/xhlcli/cli/ChatBootstrap.java:90`；`src/main/java/com/xhlcli/cli/ChatLoop.java:413` |
| 本地认证 | API 绑定 127.0.0.1，校验 Bearer/API-Key；未见 Origin/Host 检查和 CORS/OPTIONS 处理 | 若沿用 HTTP，需要明确桌面来源与凭据管理，不能假定 WebView 已可直连 | `src/main/java/com/xhlcli/runtime/api/RuntimeApiServer.java:36`、`:140` |

现有 `docs/TECH_DESIGN.md` 部分事件描述范围大于实际接线：不能将审批、计划、压缩、快照全部视作已经通过 Runtime 对外提供。本次不改写其他任务维护的架构事实源，桌面设计阶段须按上述源码重新核对契约。

## 4. 需求带来的工作量

1. **完整续聊**：保存可展示的记录还不够，需要恢复模型可使用的消息与工具调用关系，并将会话关联到正确工作目录和模型配置。
2. **真实实时反馈**：将 Agent 事件传给桌面，补齐关联标识、顺序、重连去重及终态；不能把工具执行失败误报为整个任务成功。
3. **图形审批与取消**：审批从终端交互变为可等待的界面请求，拒绝、过期和取消必须到达实际执行端。
4. **安全撤销**：区分轮次前状态、Agent 改动及之后的用户改动；存在重叠时需要停止自动恢复，给出冲突信息。
5. **桌面产品能力**：项目/会话管理、设置、文件与图片输入、安装启动以及运行进程管理均需要新的入口。

## 5. 技术建议与下一阶段边界

建议继续使用 XhlCLI 的 Java Agent，在终端和桌面之间建立共享的会话运行能力；桌面消费结构化消息及事件，不通过解析终端文本驱动业务状态。这是基于上述接缝的建议，尚未形成批准的技术设计。

需求阶段不锁定 Electron/Tauri，不编造工期。二者的选择应在视觉基准明确后，按窗口还原、Java 进程打包与管理、文件拖放、图片剪贴板、系统凭据存储和 macOS 分发要求做小范围验证。首版无需内置交互终端，不能以终端库生态作为决定性前提。

正式实现前优先验证一条完整链路：选择项目 → 独立会话 → 流式工具执行 → 界面审批 → 完成或取消 → 退出后恢复续聊 → 查看本轮变更。安全撤销需另覆盖用户在同一文件继续编辑的冲突场景。

## 6. 尚缺的直接证据

视觉基准需要用户当前安装版的主聊天窗口、新会话、工具执行/审批、改动面板、设置/模型选择，以及版本信息。记录窗口尺寸、缩放、语言和主题后再建立逐状态对照；没有这些证据，不应声明达到“一模一样”。

本轮产物为调研和需求草案。未初始化桌面工程、修改业务代码、安装依赖、提交或推送。
