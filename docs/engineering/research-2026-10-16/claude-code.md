# Claude Code 发行物逆向证据（2026-10-16）

> 持久证据目录：`/Users/daiyifei/.codex/research/xhlcli/2026-10-16`。完整 Evidence、切片和原始 npm 压缩包已保存，见 [证据清单](evidence-index.json)。以下仍出现的 `/tmp` 目录是首次分析时的提取路径，不作为长期存储保证；可由归档压缩包重建。静态解析完整仅针对选中输入，语义图仍保留动态依赖和可达性未知。

## 结论与边界

这次拿到了两类真实发行物证据：本机安装的 **Claude Code 2.1.301**，以及从官方 npm 下载的历史版本 **2.1.19**。当前版本的主程序是 Mach-O arm64 原生可执行文件，现有环境没有 Hopper/Ghidra/IDA，因而没有恢复当前主程序的控制流。当前可读的发行内容包括安装器、启动包装器和工具输入输出类型声明。

为了把 Agent 工作原理解释到代码层，另取官方历史 2.1.19 的 `cli.js`，做有版本标记的静态阅读。它能证明该历史版本包含哪些代码，**不能证明 2.1.301 内部实现完全相同**。本次没有运行 Claude 的登录、对话、工具执行或模型请求；静态路径不能当成已观测的运行行为。

本次识别的“源码”实际上是厂商公开分发的压缩 JavaScript 和类型声明，不是完整、可重建的开发源仓库。未获取服务端、模型权重、私有训练或未分发代码。

## 发行物身份

| 项目 | 本机当前版本 | 官方历史样本 |
|---|---|---|
| 包名 | `@anthropic-ai/claude-code` | 同左 |
| 版本 | 2.1.301 | 2.1.19 |
| npm 发布时间（UTC） | 2026-10-15 14:34:23.330 | 2026-01-23 21:14:31.264 |
| 发行入口 | `bin/claude.exe`，Mach-O arm64，250,541,840 字节 | `cli.js`，11,584,898 字节 |
| 入口 SHA-256 | `1f04c69e1c24e4d0a716a863af0aad8fc64d49a499334c29701f073700276b99` | `3d3870923b4d20ba50c866cc2b23ce2c75ec2b64161e06f06835d74a42dc85f0` |
| 包位置 | `/opt/homebrew/lib/node_modules/@anthropic-ai/claude-code` | `/tmp/claude-code-2.1.19/package` |

当前包 `package.json` 明确把 CLI 指向 `bin/claude.exe`。扩展名虽叫 `.exe`，但这里的文件格式是 macOS Mach-O，并非 Windows PE。`cli-wrapper.cjs` 的 `main()` 只做平台选择和 `spawnSync()` 启动原生程序，不能从这层推断 Agent 内部循环。

历史包来自 官方 npm 2.1.19 tarball（`https://registry.npmjs.org/@anthropic-ai/claude-code/-/claude-code-2.1.19.tgz`），通过 `npm pack --ignore-scripts` 获取并解压，没有执行包脚本。npm 返回的完整清单与 integrity 保存在 `/Users/daiyifei/.codex/research/xhlcli/2026-10-16/claude-2.1.19-npm-pack.json`；完整版本发布时间原始数据在 `/Users/daiyifei/.codex/research/xhlcli/2026-10-16/claude-npm-release-times.json`。tarball integrity 为 `sha512-/bUlQuX/6nKr1Zqfi/9Q6xf7WonUBk72ZfKKENU4WVrIFWqTv/0JJsoW/dHol9QBNHvyfKIeBbYu4avHNRAnuQ==`。

## REA Evidence 索引

| 标识 | 原始结果 | 能说明什么 | 限制 |
|---|---|---|---|
| `ev_978c55a1fd05e45505e81ae5402c29a46bb7ae7dff6510c07b706d5a8c64ca9c` | `/Users/daiyifei/.codex/research/xhlcli/2026-10-16/claude-2.1.301-rea.json` | 当前安装树 5 个相关文本文件的静态分析，3 个 JS/TS 文件被解析，0 parse failure | 不解析 Mach-O 主程序；不要把 0 Electron IPC 当成主程序没有 IPC |
| `ev_ee4393bd970bc47648da7485db413e77bd815ec4e15dd9bf2c2e75adc59832a8` | `/Users/daiyifei/.codex/research/xhlcli/2026-10-16/claude-2.1.19-rea.json` | 历史发行树身份、文件 inventory | 对完整 `cli.js` 分析时 worker heap 耗尽，工具明确报告 graph incomplete，不能称为完整解析成功 |
| `ev_be2b37cbedd353252d2d5b2687b9885db25903b0ab478e7c9587c7a855e5d56e` | `/Users/daiyifei/.codex/research/xhlcli/2026-10-16/claude-2.1.19-selected-rea.json` | 从同一历史 `cli.js` 精确截取的 9 段完整函数，9 个文件解析、8,476 AST 节点、0 parse failure | 这是分析者选择的派生小样本，未包含所有依赖定义；不等于完整仓库覆盖 |
| `CLAUDE-CURRENT-BINARY-STRINGS`（人工证据标识，不冒充 REA ID） | `/Users/daiyifei/.codex/research/xhlcli/2026-10-16/claude-2.1.301-binary-strings.json` | 当前二进制包含若干特定字面量及其字节偏移 | 字符串存在不证明功能可用、控制流或运行行为 |

当前可读类型文件 SHA-256 为 `446a7642c53423d239768cdf0e8e427df0e629d43e848632fbcd57cdfc93dad7`；路径 `/opt/homebrew/lib/node_modules/@anthropic-ai/claude-code/sdk-tools.d.ts`。

补充来源校验：另通过 `npm pack --ignore-scripts @anthropic-ai/claude-code@2.1.301` 获取了 官方当前版本 tarball（`https://registry.npmjs.org/@anthropic-ai/claude-code/-/claude-code-2.1.301.tgz`），保存在 `/tmp/claude-code-2.1.301-official/`，下载清单在 `/Users/daiyifei/.codex/research/xhlcli/2026-10-16/claude-2.1.301-npm-pack.json`。官方包的 `sdk-tools.d.ts`、`cli-wrapper.cjs`、`install.cjs` 与本机安装文本的 SHA-256 分别完全一致。npm wrapper 包里的 `bin/claude.exe` 是文本占位，安装后被平台专用包中的原生文件替换；因此不能拿官方 wrapper tarball 的占位文件哈希去宣称已校验原生二进制。

### 历史样本精确定位

下面的范围是原始 `cli.js` **零基字节偏移、左闭右开**，不会因为压缩代码的大长行产生歧义。函数名是该发行物的混淆名字，只用于定位，不作为跨版本稳定身份。所有切片与原始字节逐一比对一致，清单含单独 SHA-256：`/Users/daiyifei/.codex/research/xhlcli/2026-10-16/claude-code-2.1.19-selected/selection-manifest.json`。

| 观察主题 | 原始函数锚点 | 原始字节范围 | 派生文件 |
|---|---|---|---|
| Agent 主循环 | `async function*Xv(` | 9057836–9066009 | `agent-loop.js` |
| 工具并发调度 | `async function*uR6(` | 9068199–9069950 | `concurrency.js` |
| 工具校验、权限与执行 | `async function A$2(` | 9071967–9080114 | `tool-permissions.js` |
| 执行前 Hook | `async function*Y$2(` | 9083228–9085890 | `pretool-hook.js` |
| 子代理独立上下文 | `async function*tC(` | 9089452–9093088 | `subagent.js` |
| 完整上下文压缩 | `async function _P1(` | 9379787–9382291 | `full-compact.js` |
| 旧工具输出微压缩 | `async function gp(` | 9400728–9403094 | `micro-compact.js` |
| 技能声明加载 | `function XQA(` | 9449661–9451124 | `skills.js` |
| 子代理 MCP 连接清理 | `async function H$2(` | 9088315–9089304 | `mcp-subagent.js` |

派生文件均位于 `/Users/daiyifei/.codex/research/xhlcli/2026-10-16/claude-code-2.1.19-selected/`，其 REA Evidence ID 为 `ev_be2b37cbedd353252d2d5b2687b9885db25903b0ab478e7c9587c7a855e5d56e`。

## 用普通话解释历史版本里看到的设计

### 1. Agent 就是一个会拿工具结果继续思考的循环

**静态观察：** `Xv` 在发请求前先做 microcompact 和 autocompact，然后把消息、系统提示、工具列表、AbortSignal、模型及权限上下文交给流式模型请求。模型返回的 assistant 消息被收集；其中的 `tool_use` 会送入工具执行器。执行器生成的 `tool_result` 连同 assistant 消息加入下一轮，`Xv` 再调用自己继续。没有工具调用时走结束/Stop Hook 路径。函数还处理模型 fallback、用户中断、输出超限续写和最大轮次。

**通俗解释：** 模型是“决定下一步的人”，工具是“真正动手的人”。程序反复做“问模型 → 检查它想做什么 → 执行工具 → 告诉模型结果”。它不会仅凭第一段回答判断任务完成。

**可参考：** 把回路拆为可观察状态机，确保每个 `tool_use.id` 有对应的结果。网络流式失败、模型切换、用户中断都应产生可解释事件，避免悬空工具调用和无限循环。

### 2. 工具不是模型说了就执行，中间有统一的闸门

**静态观察：** `A$2` 先 `inputSchema.safeParse`，再调用工具自身 `validateInput`，随后执行 PreToolUse Hook；Hook 可以带修改后的输入、额外上下文、停止指示和 allow/deny/ask 结果。接着走 `canUseTool` 等权限分支，允许才调用 `A.call`。调用后转换结果、运行 PostToolUse；异常则生成带相同工具 ID 的错误结果并运行失败 Hook。部分 Hook allow 分支在需要用户交互或子代理上下文下仍需要 `canUseTool`，不是统一无条件绕过。

**通俗解释：** 像快递取件：先核对单子格式，再核对地址，再看是否准许送，最后才发车。把这些检查集中到一处，才能防止某个新工具忘记检查。

**可参考：** 统一 `validate → prehook → authorize → execute → normalize → posthook/failurehook` 管线；Hook 变更输入后需要明确后续校验/授权策略。权限决策、操作系统沙箱是不同层，不能只用一段提示词替代。

### 3. 并行执行有条件，不是全部 Promise.all

**静态观察：** `rO2` 先用工具的 schema 解析输入，再调用 `isConcurrencySafe(input)`。连续的安全工具组成一组并发执行，不安全的工具按顺序执行；并发组产生的上下文修改按工具顺序合并。`lO2` 读取 `CLAUDE_CODE_MAX_TOOL_USE_CONCURRENCY`，该历史版本缺省值为 10。

**通俗解释：** 同时查几个文件可以省时间，同时改同一个文件可能互相覆盖。需要由工具和参数告诉调度器能不能并行。

**可参考：** 工具元数据增加读写属性、互斥资源键、并发安全检查和取消信号。先做安全的只读工具并发，再扩展复杂写操作。

### 4. 上下文管理像整理书桌，分成轻整理和大整理

**静态观察：** 主循环先 `gp` 再 `f8K`。`gp` 把较旧工具结果存到文件，用可重新读取的路径或清除标记替换内联内容，并保留较新的结果；这段实现有最近工具保护和阈值条件，不能简化成“永远删旧消息”。`_P1` 则执行 PreCompact Hook、生成摘要，清理并恢复文件读取状态及有关附件，运行 compact 类型的 SessionStart Hook，返回压缩边界、摘要消息、附件、Hook 结果和用量。`f8K` 还可先尝试会话记忆路径。

大输出处理另见原始包 `async function jM1(`（代码点偏移 9387999）及 `async function ID2(`：超出工具输出大小门槛时保存全文，给模型一个较小的引用结果。当前 2.1.301 类型契约的 `BashOutput.persistedOutputPath/persistedOutputSize`（3338 行附近）也声明了大输出持久化字段，但不据此推断当前算法完全相同。

**通俗解释：** 轻整理把厚资料放到柜子里，桌上留下“资料在哪里”；大整理把已经做过的事写成摘要，同时把接下来还要用的任务、文件和规则重新摆回桌上。

**可参考：** 先做“大输出落盘 + 摘要/路径”，再做“旧工具结果压缩”，最后做“整段对话摘要”。压缩后应保留用户目标、未完成任务、工作目录、文件读写状态、权限及工具调用配对，且能恢复原始证据。

### 5. 子代理不只是给主消息数组再加一段提示

**静态观察：** `tC` 给子代理建立消息数组、agent ID、模型选择、解析后的工具集；根据 fork 与否处理读取状态；后台任务可有独立 AbortController；权限在父上下文基础上调整；配置的 Skills 会预加载，SubagentStart Hook 添加上下文；逐条记录 sidechain transcript；结束时清理该代理的 MCP 连接和 Hook。`Xv` 被子代理复用，说明主代理与子代理共享基础循环，差别主要在上下文及能力配置。

**通俗解释：** 找一个同事做事，应该给他自己的任务单和工具箱，让他交回结果；不能让所有人随意往同一个聊天记录里塞东西。

**可参考：** 主代理负责分派和汇总，子代理拥有明确输入、独立消息/结果记录、工具白名单、预算、取消及清理。消息隔离不自动等于文件系统隔离；同时写仓库时还需要 worktree 或资源锁。

### 6. Skills 是带元数据的按需说明书，Hook 是程序执行的回调

**静态观察：** `XQA` 从 frontmatter 提取 description、allowed-tools、argument-hint、model、disable-model-invocation 等，生成 prompt 类技能对象，真正调用 `getPromptForCommand` 时才展开正文、参数和路径信息。PreToolUse Hook 则在工具管线实际执行，结构化返回可以影响输入和权限。两者在代码中不是同一机制。

**通俗解释：** Skill 类似“菜谱”，告诉模型该怎么做；Hook 类似“门锁/自动检测器”，在特定时刻由程序调用。写了“请不要删除文件”的菜谱，不等于真的装了防删除门锁。

**可参考：** 技能目录只先提供名称与说明，需要时加载正文；Hook 要有明确事件、超时、JSON 校验和失败策略。不要把 Skill 文案当成强制权限策略。

### 7. MCP 有连接生命周期，也有按需工具发现

**静态观察：** `H$2` 为子代理配置中的 MCP server 解析已有配置或动态配置，连接后读工具，返回 clients/tools/cleanup；新建的动态连接会在清理函数里逐个关闭。历史 `function AH1(`（代码点偏移 5965837）为 deferred tools 生成发现说明，要求先经 ToolSearch 加载后调用。当前 2.1.301 类型文件 996–1007 行也声明 ListMcpResources、RefreshMcpTools，但类型存在不证明任意账号都能用。

**通俗解释：** 插件不是把几千个工具一次全塞给模型，而是先有一本目录，要用时查目录、连接服务、加载工具，用完再收拾连接。

**可参考：** 统一 MCP 健康状态、工具发现缓存、重连、超时、按代理能力授权和清理。大工具目录优先做延迟发现，减小每次请求的工具说明开销。

## 当前 2.1.301 类型契约透露的拓展方向

本节都是**发行包类型声明观察**，不是已验证的运行功能，也不把可能受功能开关控制的能力写成默认可用。

| 声明位置 | 观察内容 | 可研究的产品方向 |
|---|---|---|
| `AgentInput`，772–813 行 | 后台运行、name、模型与 effort、worktree/remote 隔离；注释说明权限继承 | 任务可后台化，长任务不卡住主交互；并行写代码时用独立 worktree |
| `AgentOutput`，102–207 行 | completed/async_launched/remote_launched 区分，token/time/tool stats、输出文件、worktree 信息 | 统一任务状态、进度、成本和来源证据，不能只返回一句“已完成” |
| `WorkflowInput`，2869–2900 行 | agent/parallel/pipeline/phase 脚本；resumeFromRunId；相同输入的已完成 agent 调用可复用结果，说明为 same-session | 可恢复工作流与重试复用，但首版不必先造通用工作流语言 |
| `TaskUpdateInput`，2828 行起 | owner、阻塞依赖、metadata | 最小任务 DAG，让“任务 B 等 A 完成”成为结构化状态 |
| `CronCreateInput`，2901–2918 行 | 周期/单次任务、durable 持久化与会话内任务区分 | 定时巡检、提醒、长任务自动续跑 |
| `MonitorInput`，2977–2997 行 | shell stdout 行事件或 WebSocket 帧事件；超时上限 | CI/构建事件触发，减少模型轮询成本 |
| `BashOutput`，3282–3387 行 | 持久化输出路径、超时/后台状态、旧读取状态提示、结构化 gitOperation | CLI 输出同时服务模型和 UI，界面无需再用正则解析每条终端文字 |
| `FileEditOutput`，3415–3466 行 | 结构化 patch、userModified、staged 字段 | 可审阅变更、用户修改后的反馈、延迟落盘模式 |
| `EnterWorktreeOutput/ExitWorktreeOutput`，3990–4004 行 | worktree 路径/分支和 keep/remove 生命周期 | 任务隔离与保留/清理策略 |

## 针对 xhlcli 可转化的设计建议

这些是从证据提出的设计建议；是否是项目缺口须由 xhlcli 的实际代码审查核对，不在本子任务冒称完成对比。

1. **先完善现有单代理闭环。** 明确轮次预算、取消、工具调用配对、可重放事件与失败恢复。比先加更多工具更容易获得稳定收益。
2. **工具执行器形成唯一闸门。** 参数 schema、路径/命令策略、授权、Hook、超时、审计日志在所有工具上统一，特别包括 MCP 代理工具。
3. **上下文先去掉大块冗余。** 工具全文落盘、模型只收预览/引用，提供分页/搜索取回；再做微压缩和摘要恢复测试。
4. **安全并发以工具资源为单位。** Read/Grep 等可并发，写同一路径互斥。不要把某个产品的历史并发数 10 当作项目最佳值。
5. **子代理先做到独立记录和可取消。** 与文件隔离分开设计，长期再引入 worktree 和任务依赖。
6. **先做一个有明确收益的拓展场景。** 例如“后台运行测试，结果发生变化时通知”可同时验证任务状态、事件流、恢复和成本控制。通用 Workflow DSL、远程云代理、自动技能生成适合后续探索，不应因竞品存在声明就全部立刻实现。

## 检查清单回看

| 原始问题 | 结果 |
|---|---|
| Claude Code 当前发行物身份 | 已回答：2.1.301，确切路径、架构、哈希、发布时间 |
| 当前主程序控制流 | 未回答：原生引擎缺失，只有声明/包装器/字面量证据 |
| 历史 Agent 工具循环 | 已回答为静态观察，精确函数与字节范围 |
| 历史权限、Hook、并发 | 已回答为静态观察，未执行安全绕过测试 |
| 历史上下文压缩 | 已回答主要静态路径，未跑模型/实际会话验收 |
| 历史子代理与 MCP 生命周期 | 已回答主要静态路径，动态依赖与实际可达性未知 |
| 当前新增产品能力 | 部分回答：从 2.1.301 类型契约识别，账号/开关可用性未知 |
| xhlcli 实际缺口 | 由主任务代码审查对照；本报告仅提供来源证据与可参考设计 |

所有结论保留观察、推断与未知边界。完整历史文件解析的 heap 失败已保留，后续小样本解析成功不抹除该覆盖缺口。
