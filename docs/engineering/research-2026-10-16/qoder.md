# Qoder 发行制品静态分析：可复核证据与可借鉴设计

> 持久证据目录：`/Users/daiyifei/.codex/research/xhlcli/2026-10-16`。完整 Evidence、切片和原始 npm 压缩包已保存，见 [证据清单](evidence-index.json)。以下仍出现的 `/tmp` 目录是首次分析时的提取路径，不作为长期存储保证；可由归档压缩包重建。静态解析完整仅针对选中输入，语义图仍保留动态依赖和可达性未知。

分析日期：2026年10月16日，Asia/Shanghai。目标为官方 npm 发行包 `@qoder-ai/qodercli@1.1.72`，2026年10月15日发布。本分析取得的是发行 JavaScript 及随包资源，不是 Qoder 私有开发仓库，也未取得 Qoder IDE / 新 Qoder 桌面应用的核心代码。

## 一、先给结论

Qoder CLI 的值得借鉴之处，是把“与模型聊天”做成了可承载多个入口的执行运行时：宿主初始化声明、能力快照、会话控制、工具权限、记忆、插件资源都有明确代码边界。最容易为自研 CLI 转化成价值的方向是：记忆分层与索引、权限受限的后台整理、统一 SDK / JSONL 运行时、插件包结构、能力发现协议。

**时效修正**：官方通知确认，Qoder IDE 的旧 Quest 窗口于 **2026年10月15日23:59（UTC+8）退役**。截至本分析日，不能把旧 Quest 窗口当作当前产品主线。Editor 窗口的 AI chat、Agent、Expert 不在这次退役范围。迁移方向是新的 Qoder 产品。此项是官方产品状态证据，不是 npm 制品逆向结果。

## 二、究竟拿到了什么，没拿到什么

- 官方安装页把 npm 安装目标指向 `@qoder-ai/qodercli`；页面目前推荐独立原生可执行程序，并把 npm 方式保留为兼容路径。因此，本报告的代码结论限于 npm 1.1.72，不能直接推广到所有原生版本。
- 用 `npm pack --ignore-scripts` 下载、解包，未安装目标依赖，未执行 postinstall，未启动 Qoder、登录或读取用户配置 / 凭据。
- 发行包含 `bundle/qodercli.js`（约33.3 MB）、`bundle/qoder-worker-runtime.mjs`（约33.5 MB）、命令分流器、内置 Skills、chat.proto、厂商插件资源。
- CLI 与 IDE 的边界可从分流器直接读到；Quest 窗口、Repo Wiki 生成引擎、服务端检索算法没有因此变得可见。包中出现 `repoWiki*` 字段只足以说明统计结构引用了这个概念，不能证明 Wiki 生成实现。

制品身份：

| 项目 | 值 |
|---|---|
| 包版本 | `@qoder-ai/qodercli@1.1.72` |
| tarball SHA-256 | `a2ac901ed3794c48c9ed2091b7bf3bccf2154dd541cf404b40031d1505666fbb` |
| qodercli.js SHA-256 | `bd9e7dbf61c67046e494319dd055cb2d84d1ab08a1a86db03e1af3437362c30e` |
| worker-runtime SHA-256 | `3b6fd78b115e52c6c76363c28815fa86b95acba891e47326b659966be370456c` |
| REA 全目录摘要 | `5d7e1f0c08f1cf360d9102d8fbc8bac28f01501ed76a556ab9e3d0da8502d332` |
| npm metadata | `/Users/daiyifei/.codex/research/xhlcli/2026-10-16/qoder-npm-1.1.72.json` |
| 原始解包 | `/tmp/qoder-artifact-1.1.72/package/` |

## 三、REA 的真实覆盖范围

原始目录 REA Evidence：`ev_a4a1f7da4bddae55faf4312b0ad70545fdc7a0d66337e6e83c03ee3805e62c52`，原始结果 `/Users/daiyifei/.codex/research/xhlcli/2026-10-16/qoder-rea-1.1.72.json`。它成功建立发行目录证据，但两个大 bundle 都发生 worker 堆耗尽，因此核心代码不能声称已全量解析。实际完整解析 JS 文件为2份。结果中的0个Electron窗口或0条IPC也不能被理解成该产品没有这些能力：本目标是CLI，且大文件覆盖不完整。

随后人工选择9份完整函数、类或类方法，保存来源偏移、原文件哈希、片段哈希、选择理由及新增语法外壳，送交 REA 静态解析。此过程没有执行目标 JavaScript。

局部 REA Evidence：`ev_50345f14da83912e8cb3e4c6ab3c542fa8b44e3478ac1c886bf8f9742f214500`，结果 `/Users/daiyifei/.codex/research/xhlcli/2026-10-16/qoder-rea-selected-1.1.72.json`。9个JS解析成功，4344个AST节点，0个解析失败。它属于**人工选择的局部证据**；外部依赖、动态成员调用与被省略函数仍有未知，不能视作全程序可达性证明。REA的Electron专项findings为0，不是说没有下面这些代码；以下语义由可复核切片人工阅读给出。

切片目录：`/Users/daiyifei/.codex/research/xhlcli/2026-10-16/qoder-selected-1.1.72/`；完整清单 `selection-manifest.json`。下表行号属于原始 `qoder-worker-runtime.mjs`，字节偏移为起点包含、终点不包含：

| 切片 | 原始行号 | 原始字节区间 | 片段SHA-256前缀 |
|---|---:|---|---|
| `memory-format.js` | 1390 | 22456854–22465516 | `122a5449faa8da97…` |
| `memory-context.js` | 2583 | 23404083–23404849 | `7a0faf57a60026c0…` |
| `dream-permission.js` | 3361 | 24589975–24590441 | `c48844c2312020bd…` |
| `dream-helpers.js` | 3423 | 24597706–24599016 | `f4ca7b9535817f7b…` |
| `dream-manager.js` | 3423 | 24599219–24603620 | `2da5e370316b0df6…` |
| `plugin-loader.js` | 1210 | 18818577–18820592 | `86655009d72cc2c2…` |
| `skill-methods.js` | 6088 | 25565684–25566369 | `b8d8829d7fee9b66…` |
| `session-initialize.js` | 9071 | 32287887–32290055 | `536e9fbc9dd7a44b…` |
| `session-capabilities.js` | 9071 | 32291607–32292947 | `8defa1c5fae53e37…` |

## 四、用五条调用链讲明白它怎么工作

### 1. CLI 和 IDE 是两个执行入口，“qoder”先当交通警察

**观察**：`qoder-npm-dispatcher.cjs:17` 读取参数；末尾约 `:300` 的分支决定去向：空参数、普通CLI命令或以选项开头的参数走CLI bundle；`ide`、`chat`、`serve-web`、`tunnel` 或现存路径走IDE启动器。macOS的IDE探测还核验 `com.qoder.ide`，远程IDE启动器根据IPC环境与remote-cli目录优先选择。`package.json:8` 同时声明 `qodercli` 与 `qoder` 两个命令入口。

**通俗讲法**：“qoder”像接待台，先问你要终端助手还是编辑器，再把参数送到对应程序。它不是靠一个模型指令去猜该开哪个界面。

**可借鉴**：自研项目将来若加入Web、IDE、守护进程，可以保留单一业务运行时，每种入口各做参数适配、事件展示。现在无需先造全套IDE。

**边界**：这是分流器的静态分支，不是IDE内部架构或实际启动成功证据。[原目录REA＋原始分流器]

### 2. 记忆是“目录卡片＋详细笔记”，分用户与项目保存

**观察链**：记忆格式生成 `Kki / Q3c / izA` → 生成用户作用域和项目作用域规则 → 每条记忆一个Markdown文件，`MEMORY.md`为索引 → `dv`从 `getMemoryContextManager().getUserContext()` 取上下文 → 可按SDK声明剔除本机autoMemory并合入SDK记忆 → `jal / vgA`把结果包入system-reminder消息。源码常量把索引上限设置为200行；这属于可见配置常量，未验证每个环境最终启用行为。

**通俗讲法**：不要把整本笔记本每次塞给模型。先给“有哪些笔记”的目录卡片，需要时再读那张详细笔记。用户偏好放个人抽屉，项目规则放项目抽屉，避免A项目的特殊规则污染B项目。

**可借鉴**：为现有记忆增加scope、来源、更新时间、索引预算、遗忘与纠错路径。引用记忆中的代码事实时仍回查当前源码；包中另有过期提醒函数，明确记忆只是过去观察，不保证当前事实。

**边界**：选择了格式生成与注入代码，未完整追踪记忆检索器；这不能证明它采用了向量数据库或某个RAG算法。[局部REA：memory-format.js、memory-context.js；原始worker :1390、:2583]

### 3. 后台“做梦”其实是有限权限的旧会话整理任务

**观察链**：`avn.onTurnComplete` → `tryRunDream`检查自动记忆开关、触发设置、距上次整理时间、会话数及扫描节流 → 对各scope取锁 → `runDreamForRoot`启动一个有上限的fork任务 → `tyl`克隆工具注册表并注入专用toolCheckerMap → `syl`仅从有成功tool_result的Write/Edit提取本scope的文件结果 → 回调刷新记忆，失败时回滚未完成scope的锁时间。

附近 `Qye` / `tvn` 的可见默认值是：整理间隔24小时、至少5个会话、扫描节流10分钟；整理fork上限50轮。**默认模板中的enabled为false，实际还受交互模式、远端feature gate、环境与设置影响，不能写成所有用户默认运行。**

**权限证据**：`xat`构造工具检查器；`tyl`指定只读Shell与记忆目录写权限。原始worker约`:3361`的 `VKi / WDl` 明确返回allow/deny，写入目录由path.resolve/normalize/relative检查。这比只在prompt里写“不要改其他文件”多了一道可执行检查。该小范围路径逻辑未证明全系统的符号链接、硬链接等隔离完备性，不能声称有操作系统级沙箱。

**通俗讲法**：把一位“资料管理员”叫来整理过去的笔记；给他只读档案室钥匙和只写记忆抽屉的钥匙，再限制工时。整理成功才登记哪些笔记变了。

**可借鉴**：先实现显式 `/memory consolidate`，有了可回放测试、成本上限、取消和可审查diff之后，再考虑自动触发。整理日志应带来源会话、版本、成功写入结果和耗时，不能只记录模型说“整理好了”。[局部REA：dream-manager.js、dream-helpers.js、dream-permission.js；原始worker :3423、:3361]

### 4. 插件是一箱资源，不仅仅是一段提示词

**观察链**：`Yh`读取并校验manifest → 并行检查commands、agents、skills、output-styles、workflows、bin等资源 → 合并hooks配置和MCP服务器声明 → 返回带路径、来源、启用状态、可选sha及warnings的插件对象。技能管理方法另有主会话allowlist、大小写无关匹配、按会话保存激活状态。

随包 `vendor/qoder-security/.qoder-plugin/plugin.json` 声明security-scan插件；`qoder-hooks.json`实际声明SessionStart、Edit/Write等后的PostToolUse及git push相关条件。**该文件的Stop数组为空**，不能根据manifest描述中的“Stop review”就说该版本已经注册并执行了Stop扫描。也没有运行下载器或原生qodersec。

**通俗讲法**：一个插件像工具箱，可以同时带说明书、专职助手、外部工具接口与检查流程；宿主统一登记它们，不能只复制一段长prompt就当作插件系统。

**可借鉴**：统一manifest、资源发现与诊断；插件tools allowlist、来源和版本可见；区分“已声明”“已加载”“已连接”“已执行”。[局部REA：plugin-loader.js、skill-methods.js；原始worker :1210、:6088；随包插件JSON]

### 5. SDK是给运行时发结构化指令，界面只负责展示

**观察链**：`mMs`初始化 → 拒绝重复初始化 → 应用permissionMode / planMode及宿主声明 → 等待推理身份与模型目录就绪 → `hMs`收集工具、MCP连接状态、命令、skills、agents、plugins等能力快照 → `IMs`组装含protocol_version的init消息 → 发初始化事件并设置ready → 响应发送后恢复产物、启动广播与SessionStart hooks。主会话对象也有interrupt、rewindFiles、backgroundTasks、getContextUsage及MCP重连等独立控制方法。

随包 `builtin/sdk/SKILL.md:18`说明官方TypeScript SDK以子进程与双向JSONL连接CLI；这是随包说明。上面的代码进一步证实存在结构化宿主初始化与能力快照，但本次未实际运行SDK通信，不能声称验证过端到端协议互通。

**通俗讲法**：CLI是发动机；终端界面、Web页面和机器人是不同驾驶舱。驾驶舱先问发动机“你有哪些能力”，然后发送有格式的命令并监听事件。

**可借鉴**：自研CLI先稳定runId、toolUseId、sequence、event type、final result等事件契约，明确重放、取消与权限请求相关性；之后再做SDK、IDE适配或CI。权限相关回包不能靠字符串顺序猜测对应哪一次工具调用。[局部REA：session-initialize.js、session-capabilities.js；原始worker :9071；随包SDK说明]

## 五、必须保留的未知与迁移建议

| 主题 | 本次证据能说什么 | 不能说什么 |
|---|---|---|
| Quest / 自主任务 | 官方已退役旧IDE Quest；CLI有独立会话与任务控制代码 | 已逆向当前Qoder桌面自主任务调度器 |
| Repo Wiki | 可见AI改动统计结构中的repoWiki字段 | 已还原Wiki生成、更新依赖图或检索算法 |
| 上下文检索 | 可见记忆索引、注入、上下文使用量、压缩相关接口或元数据 | 已证明embedding模型、向量库、混合检索或重排器 |
| 权限 | 特定fork有可执行allow/deny检查及受限工具映射 | 全系统沙箱安全性已验证 |
| Skills / MCP | 插件manifest汇总、技能白名单、MCP状态快照 | 所有第三方服务器和技能在真实环境都可用 |
| 工作流扩展 | 包中可见工作流资源和相关入口 | 脚本执行器、断点续跑、成本隔离已被端到端验证 |

面向xhlcli的建议需要与本地审查结论合并，不应在未查本地实现前宣称“你们没有”：

1. **先补运行可靠性**：一次工具调用一个稳定ID；流式事件与最终结果可关联；工具失败和取消不能伪装成功；网络与工具重试必须有边界。
2. **再补权限与上下文预算**：明确只读/编辑/执行能力，危险动作在执行层判断；记忆索引预算、工具输出裁剪与压缩都保留可追踪来源。
3. **然后补可扩展性**：稳定JSONL/SDK协议、能力快照、插件manifest与独立权限；让CI、Web、IDE复用运行时。
4. **产品实验最后做**：跨会话记忆整理、仓库知识卡片 / Wiki、任务断点续跑与报告工件。旧Quest界面不宜复刻，其任务运行时思想可以独立借鉴。

## 六、来源与复核材料

官方身份与时效来源（正文均为概括）：

```text
https://docs.qoder.com/cli/installation
https://docs.qoder.com/release-notes/quest-retirement-notice.md
https://registry.npmjs.org/@qoder-ai/qodercli/-/qodercli-1.1.72.tgz
```

本地复核材料：

- `/tmp/qoder-installation-jina-20261016.md`
- `/tmp/xhlcli-qoder-retirement-20261016.md`
- `/Users/daiyifei/.codex/research/xhlcli/2026-10-16/qoder-npm-1.1.72.json`
- `/Users/daiyifei/.codex/research/xhlcli/2026-10-16/qoder-pack-1.1.72.json`
- `/tmp/qoder-artifact-1.1.72/qoder-ai-qodercli-1.1.72.tgz`
- `/Users/daiyifei/.codex/research/xhlcli/2026-10-16/qoder-rea-1.1.72.json`
- `/Users/daiyifei/.codex/research/xhlcli/2026-10-16/qoder-rea-selected-1.1.72.json`
- `/Users/daiyifei/.codex/research/xhlcli/2026-10-16/qoder-selected-1.1.72/selection-manifest.json`

联网路线：agent-reach的Exa返回免费额度耗尽，转为Jina官方页面阅读和gh官方组织仓库枚举；npm用于取得官方安装文档指认的确切发行包。web.run尝试搜索和打开官方安装页均未返回可用结果，因此此子报告不伪造web引用。未请求或改写REA MCP配置，未安装原生引擎。
