# Changelog

本项目的重要变更记录在此文件中，格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [1.0.1] - 2026-10-07

### Added
- **用户全局配置支持 API Key（User Config API Key Support）**:
  - 解除 `~/.xhlcli/config.json` 对敏感凭据字段的拒绝异常拦截，全面对齐 Claude Code 等主流 CLI 的全局配置体验；
  - 兼容顶层 `apiKey`（默认作为主模型 Provider / DeepSeek 的 API Key）及 `deepseekApiKey`、`openaiApiKey`、`anthropicApiKey`；
  - 支持 `providers` 嵌套映射格式（如 `providers.deepseek.apiKey`、`providers.openai.apiKey` 等）；
  - 确立清晰确定的优先级解析阶梯：`进程环境变量 (ENVIRONMENT) > 项目 .env (DOT_ENV) > 全局配置 ~/.xhlcli/config.json (USER_CONFIG) > 默认值`；
  - 在 `ChatBootstrap` 中自动将 `userEnv` 映射注入 `LlmProviderRegistry`，实现全局配置一次即可在任意目录使用多 Provider 模型；
  - 增强 `install.sh` 快速安装引导，提示用户可直接通过 `echo '{"apiKey":"..."}' > ~/.xhlcli/config.json` 一键就绪；
  - 新增/更新单元测试与集成测试，覆盖多层优先级级联与 Provider 解析，全量 525 项测试 100% 绿灯。

## [1.0.0] - 2026-10-07

### Added
- **开源发布治理与现代化静态官网（Open Source Release & Impeccable Website）** (Phase 18):
  - **现代化静态官网 (`website/`)**：
    - 严格遵循 `/impeccable` 设计规约（Persuade 模式、Craft Floor 零容忍禁忌：无 kicker、无渐变字、无劣质卡片堆砌、无系统 Emoji、真实代码切片、浏览器表面主题化）；
    - 深色工程师高保真调色板，包含深沉底色、龙焰橘（#f97316）品牌色与极简细滚动条/Selection 定制；
    - 异构 Bento Grid 架构矩阵，覆盖 Multi-Agent 协作管线、SideGit 物理隔离快照还原、后台持久任务与 Runtime API、多模态视觉防御护栏、四层 Token 预算与零延迟极速 JVM。
  - **交互式终端模拟器 (Terminal Simulator)**：
    - 高保真复刻 JLine 4 动态终端界面、macOS 标题栏与实时动态状态栏；
    - 预置 4 大真实场景：`/team`（Kahn 拓扑排序与并发 Worker 审查）、`/restore`（SideGit 误删秒级还原）、`@image`（Alpha 白底平铺与纯文本模型防御降级）、`/task`（后台 SQLite 队列与 SSE 游标流）；
    - 支持逐行拟真打字动效、一键「重播」与终端交互式场景切换。
  - **交互式指令速查表 (Interactive Cheat Sheet)**：
    - 涵盖全部 18 个演进阶段的 20 项生产级指令；
    - 实时关键词模糊检索与类别筛选（全部、多智能体、快照与回滚、后台与Runtime、多模态、会话与模型）；
    - 指令行内一键快速复制并联动底部平滑 Toast 提示。
  - **Vercel 零配置秒级部署**：
    - 提供 `website/vercel.json` 与根目录 `vercel.json`；
    - 纯原生 HTML5/CSS3/Vanilla JS，零 npm 依赖与零服务端负担，一键直连 Vercel 静态托管或本地双击即用。
  - **开源跨平台一键安装脚本 (`install.sh`)**：
    - 自动系统探测（macOS/Linux 及 x86_64/arm64 架构）；
    - Java 21+ 运行时就绪校验与安装指导；
    - 自动包装并生成 `~/.xhlcli/bin/xhlcli` 启动器与 PATH 环境变量提示。
  - **全量质量回归与工程评测**：
    - 产出 Phase 18 评测报告 `docs/engineering/release-and-website-evaluation.md`；
    - 522 项自动化单测与集成测试 100% 绿灯通过；
    - 正式发布 `v1.0.0`。

## [0.15.1] - 2026-10-07

### Added
- **图片多模态上下文预处理与视觉防御护栏子系统（Multimodal Context Preprocessing & Vision Guardrails Subsystem）** (Phase 17B):
  - **多模态消息模型扩展 (`ContentPart` + `ChatMessage`)**：
    - 创建 `ContentPart` record 支持 `text`, `imageBase64`, `imageUrl`, `mimeType`；
    - 扩展 `ChatMessage` 增加 `contentParts` 列表及 `hasImages()`, `imagePartCount()`, `withoutImageContent()` 辅助方法，保持既有 500+ 单测 100% 向后兼容。
  - **严密图像预处理与防穿透压缩引擎 (`ImageProcessor`)**：
    - 支持 PNG, JPEG, GIF, WEBP 文件识别与头信息校验，限制源文件不超过 50MB；
    - **Alpha Flatten（白底平铺）**：检测透明 Alpha 通道，采用纯白背景合成平铺，杜绝不同 Provider 底色反转与噪点穿透问题；
    - **等比双三次插值缩放（Bicubic Resampling）**：长宽严格限制在 `2000x2000` 像素内，等比缩放并保持边缘清晰度；
    - **API 字节阈值兜底压缩**：处理后 Base64 严格受限在 5MB API 阈值内，超限依次尝试无损 PNG -> 0.85 -> 0.70 -> 0.55 -> 0.40 -> 0.25 JPEG 多档质量压缩；
    - **坐标映射元信息注入**：自动计算原始图片与显示图片比例，注入元数据引导模型精准换算坐标：`[Image: source: ..., original WxH, displayed at WxH, Multiply coordinates by X to map to original image]`。
  - **系统剪贴板原生抓图与平台优化 (`ClipboardImage`)**：
    - macOS 深度优化：优先通过原生 `/usr/bin/osascript` 提取剪贴板 `«class PNGf»` 或 `«class TIFF»`，配合 `/usr/bin/sips` 快速无损转储至本地缓存文件（`~/.xhlcli/cache/clip-*.png`）；
    - 跨平台 Java AWT 剪贴板兜底，Headless 环境安全捕获降级并提供清晰提示。
  - **终端引用宏与指令解析器 (`ImageReferenceParser`)**：
    - 正则提取匹配 `@image:<path>`（兼容空格路径）、`@image:path`（隔离全角 CJK 标点）、`file://` URI（宽容度 UTF-8 percent-decode）及 `@clipboard`；
    - 剥离纯文本指令并注入本轮图片观察指示语与防覆盖约束；自动组装多模态 `ChatMessage`。
  - **Provider 请求体序列化与零 400 视觉防御护栏 (`AbstractOpenAiCompatibleClient` + `AnthropicClaudeClient`)**：
    - OpenAI 视觉模型：将消息序列化为标准 content array，包含 `type: text` 与 `type: image_url`（`data:{mime};base64,{data}`）；
    - Anthropic 视觉模型：序列化为 Claude 标准 content array，包含 `type: text` 与 `type: image`（`source: {type: base64, media_type: ..., data: ...}`）；
    - **零 400 视觉防御护栏**：对于不支持视觉输入的纯文本模型（如 DeepSeek 等，`supportsVision == false`），客户端自动拦截 Base64 数据并降级为纯文本提示（`[当前 provider/model 不支持图片附件，已省略 1 张...]`），请求体输出为标量字符串 `"content": "..."`，绝对杜绝由于发送 `image_url` 引发 Provider 400 报错。
  - **长会话 Token 与内存防护 (`pruneHistoricalImagePayloads` + `TokenBudget`)**：
    - 在新一轮 ReAct 执行前，自动遍历并修剪历史消息中的大图 Payload（`withoutImageContent`），移除巨大 Base64 但保留 Image source 提示；
    - `TokenBudget` 支持图片单图 1000 tokens 估算，精准控制滑动上下文窗口。
  - **端到端 Golden Test 验收物证与全量回归**：
    - `MultimodalGoldenTest` 全链路覆盖：输入引用解析 -> Alpha Flatten 白底合成 -> 等比缩放 -> OpenAI 视觉模型调用 -> DeepSeek 纯文本防御降级 -> 历史大图修剪；
    - 全量自动化测试套件扩充至 522 项（100% 绿灯）。

## [0.15.0] - 2026-10-07

### Added
- **后台持久任务队列与 Localhost Runtime API 子系统（Durable Tasks & Runtime API Subsystem）** (Phase 17A):
  - **任务状态机与持久化领域模型 (`TaskStatus` + `DurableTask`)**：
    - 规范化 6 态状态转移矩阵：`ENQUEUED`, `RUNNING`, `WAITING_FOR_APPROVAL`, `COMPLETED`, `FAILED`, `CANCELED`；
    - 记录任务编号、状态、提示词、工作区绑定、耗时、执行结果、异常日志及开始/完成时间戳；
  - **SQLite 持久调度引擎与崩溃租约自愈 (`DurableTaskManager`)**：
    - 基于 SQLite 事务机制 (`tasks.db`) 实现并发安全的原子认领 (`claimNext`)，杜绝多 Worker 认领同一任务的脑裂竞争；
    - 进程重启时自动触发租约恢复 (`recoverRunningTasks`)，将孤儿 `running` 任务平滑自愈回滚为 `ENQUEUED` 重新排队，防止悬挂死锁；
    - 采用守护线程池 Worker 并发异步处理，配合 `cancel(id)` 精准中断当前执行线程并更新为终态 `CANCELED`；
  - **本地安全 Runtime API 服务 (`RuntimeApiServer` + `RuntimeThreadStore`)**：
    - 纯 Java 原生实现（基于 JDK `HttpServer`），零外部重量级 Web 框架依赖；
    - 严格限绑 `127.0.0.1` 环回接口，坚决杜绝暴露公网；
    - 强制 API Key 鉴权拦截（`Authorization: Bearer` 或 `X-XhlCLI-API-Key`），未配置 Key 拒启服务，非法请求返回 401；
    - 提供 RESTful 接口体系：`POST /v1/threads`、`POST /v1/threads/{id}/turns`（返回 202 异步调度）；
    - 支持 SSE 流式长连接 (`GET /v1/threads/{id}/events`) 与单调递增游标断点拉取 (`?after={cursor}`)，保障客户端重连无缝续传；
  - **终端交互指令套件与自动补全 (`TaskCommandFormatter` + `ChatCommand` + `TerminalCompleter`)**：
    - 新增 `/task`（或 `/task list [N]`）、`/task add <任务内容>`、`/task log <id>`、`/task cancel <id>`；
    - 在 JLine `TerminalCompleter` 中支持 `/task` 及全部二级子命令的 Tab 智能补全；
    - 在双模渲染器的帮助菜单中补充 `/task` 使用说明；
  - **端到端集成 Golden Test 与全量质量回归**：
    - `RuntimeGoldenTest` 全链路覆盖：异步提交 -> 并发 Worker 执行 -> 运行中协作取消 -> 重启孤儿租约自愈 -> Runtime API 鉴权创建会话 -> 提交交互 turn -> SSE 增量事件流与游标断点续传；
    - 全量自动化测试套件扩充至 500 项（100% 绿灯）。

## [0.14.0] - 2026-10-07

### Added
- **Side-History 隔离快照与版本恢复子系统（Side-History Snapshot & Recovery Subsystem）** (Phase 16):
  - **纯 Java 嵌入式与绝对物理隔离架构 (`SideGitManager` + `SnapshotConfig`)**：
    - 引入 `org.eclipse.jgit` (7.6.0) 纯 Java 依赖，零外部 `git` CLI 依赖，跨平台开箱即用；
    - 基于项目绝对路径与规范化工作区双重 SHA-256 哈希计算隔离存储目录（`~/.xhlcli/snapshots/<projectHash>/<worktreeHash>/.git`），workTree 独立单向绑定；
    - 自动配置并维护 `info/exclude` 过滤规则（排除 `.git/`、`target/`、`.xhlcli/` 等目录），严格保证用户项目本身的 `.git`、HEAD 提交树与暂存区 100% 绝对不受快照读写污染。
  - **异步双阶段调度与生命周期包装 (`SnapshotService` + `ChatLoop`)**：
    - 无缝切面织入 ReAct、Plan-and-Execute 以及 Multi-Agent 团队协作的每一轮 turn 生命周期；
    - `pre-turn` 同步毫秒级建档（8~18ms），执行前锁定基线；
    - `post-turn` 由单线程守护线程池 `xhlcli-snapshot-writer` 异步串行化提交，主交互循环零卡顿；
    - `awaitIdle()` 栅栏协同，在执行快照查询或版本回滚前安全排空队列；
    - 快照写入异常静默降级并输出友好提示，绝不阻断正常 Agent 对话流程。
  - **工作区精准对齐与保护快照恢复机制 (`SideGitManager.restorePreTurn`)**：
    - 支持按轮次倒序（offset=1, 2, ...）精确定位历史 `pre-turn` 快照；
    - 执行任何回滚操作前强制自动打底生成 `pre-restore` 保护快照，杜绝二次损坏并支持撤销回滚；
    - 恢复时精准写回已修改文件与被误删文件，并深度递归清理未被快照收录的新增孤儿垃圾文件与空目录。
  - **高危自愈工具与终端命令治理 (`RevertTurnTool` + `ChatCommand` + `TerminalCompleter`)**：
    - 提供本地工具 `revert_turn`，声明 `RiskLevel.HIGH` 并在 `ApprovalPolicy` 中强制接入人机交互审批 (HITL)；
    - 新增 `/snapshot`（或 `/snapshot list`）、`/snapshot status`、`/snapshot clean` 以及 `/restore <N>` 终端指令；
    - 在 `TerminalCompleter` 中提供多级子命令与参数 Tab 智能补全；
    - 帮助菜单中收拢展示快照与恢复指令说明。
  - **端到端 Golden Test 验收物证**：
    - `SnapshotGoldenTest` 验证“真实项目 Git 仓库 -> Turn 1 正常迭代 -> Turn 2 误删与语法破坏 -> 工具自愈恢复 -> 磁盘 100% 还原 -> 宿主 Git 零污染零修改断言 -> 二次回滚”全链路测试通过；
    - 全量自动化测试套件扩充至 475 项（100% 绿灯）。

## [0.13.0] - 2026-10-06

### Added
- **终端产品化与交互治理子系统（Terminal Productization & Interactive Governance Subsystem）** (Phase 15):
  - **环境感知与双模终端渲染契约 (`TerminalRenderer`)**：
    - 统一抽象 `TerminalRenderer` 接口规范，收拢事件分发、系统消息打印、首屏 Banner 呈现、底部状态栏更新及生命周期销毁；
    - `TerminalEnvironment` 自动探测 `NO_COLOR`、`--plain`、`TERM=dumb` 以及标准输出管道重定向，自适应路由渲染实现；
    - `PlainRunRenderer`：面向 CI、脚本管道与免交互重定向，严格实施 ANSI Escape 字符完全过滤（0 ANSI 逃逸），并深度集成 `SecretRedactor` 脱敏敏感凭据；
    - `InlineTerminalRenderer`：基于 JLine 4 `LineReader.printAbove()` 保证动态刷新不破坏用户输入行，内部通过同步锁保障多线程原子化串行输出。
  - **动态底部状态栏与真实显示宽度计算 (`TerminalStatusBar` + `TerminalWidthCalculator`)**：
    - 集成 JLine 4 `org.jline.utils.Status` 浮动状态行，支持 ReAct/Plan/Team 运行模式、Phase 阶段、模型标识、Token 消耗、MCP/Skill 扩展概览及当前工作区展示；
    - `TerminalWidthCalculator` 基于 Unicode `WCWidth` 算法精确计算半角、全角 CJK 与 Emoji 显示列宽；
    - 针对 80/120/160 列终端尺寸建立 P0~P3 优先级动态自适应裁剪策略，绝对保证单行呈现、零自动换行且无空悬乱码。
  - **视觉排版与代码格式化引擎 (`MarkdownRenderer` + `DiffRenderer` + `ToolFoldingManager`)**：
    - `MarkdownRenderer`：实现瑞士风代码块外框（`┌── lang ──`）、多级标题着色、列表渲染以及基于真实字符宽度的多列表格动态列宽对齐；
    - `DiffRenderer`：支持 Unified Diff 语义着色（新增绿/删除红/元信息青），并提供统计行 `(+N, -M)` 汇总；
    - `ToolFoldingManager`：超长工具调用或文件读取输出（默认 >15 行）智能折叠，保留首尾关键行并提取结构化摘要。
  - **交互输入治理三件套 (`SafeHistory` + `TerminalCompleter` + `TerminalInputHighlighter`)**：
    - `SafeHistory`：拦截并脱敏常见 API Key（DeepSeek/OpenAI/Anthropic）、过滤空白与连续重复行、拦截 >4000 字符超长防御攻击，文件异常时平滑降级为内存历史；
    - `TerminalCompleter`：三级斜杠命令补全、动态参数补全（`/model`, `/mcp`, `/skill`, `/history` 等）以及 `@path` 本地工作区文件路径感知补全；
    - `TerminalInputHighlighter`：斜杠命令与本地文件路径实时语法高亮。
  - **HITL 人机审批无冲突恢复与系统输出收拢 (`TerminalHitlHandler` + `ChatLoop`)**：
    - 抽象 `HitlInputReader` 接口，解耦野生 `System.in`，委托 `JLineTerminalSession.readLine` 与 `renderer.printMessage` 完成交互，彻底解决流冲突与终端死锁；
    - 扩展 `/history [list|clear]` 终端命令并在 `ChatCommandParser` 注册；
    - 全量收拢 `ChatLoop` 控制台输出至 `TerminalRenderer`，实时联动更新状态栏生命周期。
  - **基准测试与 Golden Test 验收**：
    - `TerminalGoldenTest` 固化 80/120/160 列状态栏渲染基准、Markdown 表格 Swiss 边框基准与 Plain 模式 0 ANSI 纯度断言；
    - 全量自动化测试套件扩充至 451 项（100% 绿灯）。

## [0.12.0] - 2026-10-05

### Added
- **Skill 与 Prompt 分层治理子系统（Layered Prompts & On-Demand Skills Subsystem）** (Phase 14):
  - **Prompt 确定性 8 层组装引擎 (`LayeredPromptAssembler`)**：
    - 严格确定性装配顺序：Base Identity (1) -> Safety Policy (2, Immutable) -> Agent Mode (3) -> Runtime Context (4) -> Project Rules & Memory (5) -> Skill Index (6) -> Active Skills (7) -> Handover Guidelines (8)。
    - 系统安全规则绝对不可变性（`IMMUTABLE`）：无论项目级还是用户级配置如何定义，内置核心安全规则绝对不被覆盖或擦除，坚决捍卫本地代码优先、工作区边界与命令审批底线。
    - 独立分层预算管理（`LayerBudgetConfig`）：各层分配独立软硬字符预算配额，超额平滑截断并标记截断状态。
  - **三层覆盖继承与健康隔离 (`SkillRegistry`)**：
    - 支持 `BUILTIN` (classpath:/skills/) < `USER` (~/.xhlcli/skills/) < `PROJECT` (.xhlcli/skills/) 三层扫描；
    - 同名 Skill 按照高优先级完全覆盖低优先级；
    - 动态启停切换与状态持久化；
    - 单个 Skill YAML 解析错误安全隔离在自身状态为 `ERROR`，绝不阻塞应用启动与其他正常技能。
  - **元数据解析与沙箱化引用隔离 (`SkillParser` + `SkillReferenceResolver`)**：
    - 支持 `SKILL.md` 标准 YAML Frontmatter 解析（`name`, `description`, `allowed-tools`, `author`, `tags`）与 Markdown 正文分离；
    - 相对路径参考资料读取（`references/` 与 `scripts/`）严格限制在 Skill 根目录内，彻底拦截 `../` 路径穿越逃逸攻击。
  - **渐进式披露与按需加载 (`SkillIndex` + `LoadSkillTool`)**：
    - 启动期向系统提示词仅注入启用的 Skill 紧凑索引（名称与简短描述，< 2500 字符预算），杜绝 Context Debt；
    - 模型任务匹配时自主调用本地工具 `load_skill`，正文按需激活注入；
    - 同一 Run 内部自动幂等去重，防止模型重复加载浪费上下文。
  - **内置高质量开箱即用 Skill**：
    - `git-feature-workflow`：代码审查、回归测试、Conventional Commits 原子提交与发布流程；
    - `web-research`：问题拆解、多源搜索、正文提取、交叉验证与决策报告生成。
  - **终端管理指令与脱敏审计导出 (`ChatLoop` + `PromptExporter`)**：
    - 扩展终端 `/skill [list|show|enable|disable|reload]` 控制台；
    - 扩展终端 `/prompt [show|export [filepath]]` 审计导出；
    - 导出内容按层清晰标注来源与截断指标，并全量调用 `SecretRedactor` 脱敏所有 API Key 与敏感凭据。
  - **基准测试与 Golden Test 套件**：
    - `PromptGoldenTest` 固化标准上下文下的 Prompt 分层输出与安全规则不可变断言；
    - 全量自动化测试套件扩充至 401 项（100% 绿灯）。

## [0.11.0] - 2026-09-22

### Added
- **Web 检索与浏览器安全沙箱子系统（Web & Browser Integration Subsystem）** (Phase 13):
  - **网络安全策略与 SSRF 防护围栏 (`NetworkPolicy`)**：
    - 协议白名单校验：仅允许 `http` 与 `https` 协议，直接拦截 `file:`, `ftp:`, `gopher:` 等危险协议。
    - 私网与本地 IP 拦截：禁止访问 `localhost`, `0.0.0.0`，解析并拦截 IPv4/IPv6 loopback、any-local、link-local (`169.254.0.0/16`) 与 RFC 1918 私网地址 (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`)。
    - 重定向全链路追踪校验：在 HTTP 301/302/307/308 重定向跟随循环中，对每一个跳跃目标 URL 严格二次校验，杜绝通过公网跳转内网的 SSRF 穿透漏洞。
    - Token Bucket 限流：内置平滑令牌桶算法，对外部 Web 请求实施 30 次/60 秒的速率限制。
  - **多源搜索引擎抽象与免 Key 降级 (`SearchProvider`)**：
    - 领域模型与抽象接口：定义不可变 `SearchResult` (title, url, snippet, publishedDate) 与统一 `SearchProvider` 契约。
    - 四大搜索引擎 Provider：
      - `SerpApiSearchProvider`：基于 Google Search API 聚合高质量搜索结果；
      - `SearxngSearchProvider`：支持自建开源隐私元搜索引擎；
      - `ZhipuSearchProvider`：支持智谱 Web Search API，国内网络通畅；
      - `DuckDuckGoSearchProvider`：全自动免 Key 零配置 HTML 抓取降级兜底方案。
    - `SearchProviderFactory`：根据环境变量与配置自动推导并组装最佳可用搜索引擎。
  - **受限网页抓取与 Readability 正文提取 (`WebFetcher` + `HtmlExtractor`)**：
    - 5MB 受限流式读取与 OOM 防御，支持 HTTP Content-Type 与 HTML `<meta>` 标签多级字符集编码智能嗅探。
    - 基于 Jsoup 清理 `<script>`, `<style>`, `<nav>`, `<footer>`, `<header>`, `<aside>` 与常见广告容器，结合语义节点与文本密度打分算法提取主体内容。
    - 结构化渲染为高质量 Markdown（标题层级、段落、列表、表格、代码块及缩进保留）；反爬或 SPA 空正文时自动输出浏览器工具降级建议。
  - **浏览器沙箱分层与安全守护者 (`BrowserGuard` + `BrowserSession`)**：
    - `ISOLATED` (独立沙箱) 与 `SHARED` (共享宿主 Chrome) 双模式切换。
    - `SensitivePagePolicy`：加载银行、支付（Stripe/Alipay/PayPal）、云控制台（AWS/GCP/Azure/阿里云）等敏感规则库，支持用户自定义 `~/.xhlcli/sensitive_patterns.txt`。
    - `BrowserGuard`：
      - 敏感页面写操作（`click`, `fill`, `evaluate_script` 等）强制单步 HITL 审批，会话内 `Approve-All` 无法穿透；
      - `SHARED` 模式下严禁通过 `close_page` 关闭非 Agent 自身开启的宿主工作标签页；
      - 自动跟踪会话导航历史与当前活跃页面敏感状态。
    - `BrowserConnectivityCheck`：本地 9222 等 CDP 端口探活与 Chrome 标签页清单 (`/json/list`) 提取。
  - **本地工具与终端交互控制台**：
    - 向 `ToolRegistry` 注入 `web_search`, `web_fetch`, `browser_connect`, `browser_disconnect`, `browser_status` 工具并接入系统提示词与安全审计流。
    - 新增终端 `/browser` 命令族（`/browser [status|connect <port>|disconnect|tabs]`）。
  - **测试与评测物证**：
    - 新增 37 项专项单元与端到端集成测试，全量 366 项自动化测试 100% 绿灯。
    - 产出设计规范 `docs/specs/2026-09-22-phase-13-web-and-browser-design.md`、实施计划 `docs/plans/2026-09-22-phase-13-web-and-browser.md` 与评测报告 `docs/engineering/web-and-browser-evaluation.md`。

## [0.10.0] - 2026-09-21

### Added
- **Model Context Protocol (MCP) 生态扩展（MCP Extensibility Subsystem）** (Phase 12):
  - 核心协议模型与 JSON-RPC 2.0 序列化：实现标准 JSON-RPC 2.0 协议包（`JsonRpcRequest`, `JsonRpcResponse`, `JsonRpcNotification`, `JsonRpcError`）及 MCP 2024-11-05 规约模型（`McpToolDefinition`, `McpResource`, `McpResourceContent`, `McpCallResult` 等）。
  - 双通道传输抽象与实现：
    - `StdioMcpTransport`：利用 `ProcessBuilder` 与 UTF-8 管道与本地子进程交互，非协议行文本隔离过滤，64KB 循环缓冲环捕获 stderr 崩溃诊断，结合 `CancellationToken` 支持协作式销毁。
    - `StreamableHttpMcpTransport`：基于 OkHttp 实现 HTTP POST / SSE 通信，支持自定义 Header 与 Bearer Token 注入，支持 Socket 取消断开。
  - 两级配置安全合并与环境变量解析器 `McpConfigLoader`：用户全局 `~/.xhlcli/mcp.json` 与项目级 `.xhlcli/mcp.json` 双层解析，项目配置就近覆盖，严格支持 `${ENV}` 变量插值与默认值语法；缺失未定义环境变量抛出 `UnresolvedEnvException` 并隔离标记故障服务器，绝不阻断系统启动。
  - 工具发现与命名空间适配器 `McpToolAdapter`：动态映射工具至 `mcp__{server}__{tool}` 隔离命名空间，自动清洗 inputSchema（剥离 `$schema`/`definitions` 冗余约束），映射 Text 与 Image（转为安全摘要描述），接入 `ToolRegistry` 实现热加载与并发线程安全查询。
  - 安全沙箱与人机确认联动：所有 MCP 动态工具默认评定为 `RiskLevel.MEDIUM_RISK`，支持 `trustedReadOnly` 白名单放行机制；调用入参与出参经 `StreamingSecretRedactor` 自动脱敏并存入 `AuditLog`。
  - 资源读取协议支持：实现 `resources/list`、`resources/read` 接口，支持文本与 base64 二进制资源提取与终端展示。
  - 服务器生命周期管理与启动预算 `McpServerManager`：支持启动、停止、重启、日志查看；内置 3 秒并发拉起启动预算（`DEFAULT_STARTUP_BUDGET = Duration.ofSeconds(3)`），启动超时自动降级；监听 `notifications/tools/list_changed` 通知并实时热刷新工具列表。
  - 交互终端命令族扩展：新增 `/mcp` 命令族（`/mcp list`、`/mcp status <server>`、`/mcp tools`、`/mcp resources`、`/mcp read <uri>`、`/mcp restart <server>`、`/mcp stop <server>`、`/mcp start <server>`、`/mcp logs <server>`）。
  - 全套新增 24 项专项单元测试与端到端集成测试（`McpIntegrationTest`, `McpCliIntegrationTest` 等），全量 329 项自动化测试 100% 绿灯。
  - 产出设计规范 `docs/specs/2026-09-21-phase-12-mcp-design.md`、实施计划 `docs/plans/2026-09-21-phase-12-mcp.md` 与评测报告 `docs/engineering/mcp-evaluation.md`。

## [0.9.0] - 2026-09-18

### Added
- **多模型路由与能力声明（Multi-Model Adaptation & Capability Declarations）** (Phase 11):
  - 核心领域模型与能力元信息 `ModelCapabilities`：解耦并精确声明最大上下文窗口、工具调用支持（`supportsTools`）、视觉多模态支持（`supportsImageInput` / `supportsVision()`）、Prompt Cache 模式（`promptCacheMode`）及推理模型标记（`requiresReasoningEffort`）。
  - 通用 LLM 抽象接口扩展 `LlmClient`：解耦 Provider 名称（`providerName()`）、模型名称（`modelName()`）与模型能力元信息（`capabilities()`）。
  - 统一 OpenAI-compatible 族适配：抽象出可复用的 `AbstractOpenAiCompatibleClient`，重构 `DeepSeekClient`，并新增 `OpenAiClient`（标准 OpenAI `/v1/chat/completions` 协议），支持 `gpt-4o`、`o1`、`o3-mini` 及推理模型特性识别。
  - 原生 Anthropic 协议适配器 `AnthropicClaudeClient` 与流式解析器 `AnthropicSseParser`：支持原生 `/v1/messages` 协议、顶层 `system` 拆分、Anthropic 格式工具声明（`input_schema`）与双向转化（`tool_use` / `tool_result`）、多事件 SSE 增量解析（`content_block_delta`、`input_json_delta`）及自动重试与鉴权。
  - 本地隐私优先 Ollama 适配器 `OllamaClient`：本地免 Key 直连 `/api/chat`，原生逐行 NDJSON 流式解析与工具调用双向映射。
  - Provider 注册中心与模型工厂 `LlmProviderRegistry`：统一注册与管理 deepseek、openai、anthropic、ollama 等多 Provider，支持环境变量与 `.env` 凭据自动探测、模型别名模糊匹配（`claude-3-7-sonnet` -> `claude-3-7-sonnet-20250219`、`gpt-4o`、`deepseek-chat` 等）与运行时代工实例化。
  - 上下文预算动态联动与超限告警：模型切换触发 `TokenBudget.updateContextWindow()` 动态重算分层配额，当历史对话超出新模型容量时发出结构化超限预警并建议执行 `/compact`。
  - 工具能力硬拦截与防御护栏：在 `ReactAgent`、`PlanExecuteAgent`、`Planner` 与 `TeamOrchestrator` 中引入 `supportsTools` 前置能力校验，拦截无工具调用能力模型（如 `o1`、轻量无 tool 权重）的无效 Agent/Plan/Team 执行请求并给出明确指导。
  - 终端命令与交互增强：新增 `/model` 指令族（`/model list` 查看可用模型与凭据探测状态，`/model status` 查看当前激活模型及其详细能力参数，`/model use <model>` 运行时动态热切换）。
  - 全套新增 14 项专项契约测试与集成测试（`LlmProviderContractTest`, `AnthropicClaudeClientTest`, `OllamaClientTest`, `LlmProviderRegistryTest`, `ModelSwitchingIntegrationTest`），全量 305 项自动化测试 100% 绿灯。
  - 产出设计规范 `docs/specs/2026-09-18-phase-11-multi-model-design.md`、实施计划 `docs/plans/2026-09-18-phase-11-multi-model.md` 与评测报告 `docs/engineering/multi-model-evaluation.md`。

## [0.8.0] - 2026-09-16

### Added
- **Multi-Agent 协作架构（Multi-Agent Collaboration）** (Phase 10):
  - 专职角色架构（1+2+1 体系）：明确划分 `PLANNER`（专职规划解耦，严禁工具调用）、`WORKER`（池化执行单元，持有完整开发工具与多轮 ReAct 能力）、`REVIEWER`（专职质量审查，纯逻辑审查无写工具）与 `TeamOrchestrator`（统筹编排调度，对全局目标负责）。
  - 角色提示词工程 `TeamPrompts`：针对规划者、执行者、审查者深度定制 System Prompt，强制规范 JSON 协议输出与 Local Code First 原则。
  - 最小化上下文交接包 `HandoverPackage`：规范 Agent 间任务交接标准，仅传递当前步骤目标、依赖结果摘要、验收标准与文件改动，杜绝全量对话历史交叉污染。
  - 结构化质量审查与容错降级 `ReviewResult`：支持标准 JSON 审查报告（approved, summary, issues, suggestions）解析与文本启发式容错降级，具备保守性安全兜底。
  - 有限重试与熔断保护机制：执行质量不达标时条目化反馈打回 Worker 修正，单步支持最多重试 2 次（`MAX_RETRIES = 2`），超出立即熔断终止，杜绝死循环与 Token 浪费。
  - 独立专职子代理 `SubAgent`：具备独立会话生命周期、`clearHistory` 历史复位与工具权限动态下发控制，支持流式日志重定向输出。
  - 无依赖步骤受控并发（Worker 池排他调度）：复用 `BlockingQueue<SubAgent>` 保证 Worker 实例互斥借用，每个并发步骤使用独立内存流（`ByteArrayOutputStream`）缓冲输出，批次完成后按步骤保序 flush 到终端，彻底根除多 Agent 并发控制台交错乱序。
  - 交付汇总看板：汇总各步骤状态、重试次数、审查结论与最终交付物明细，自动沉淀至项目级长期记忆（`MemoryManager`）。
  - CLI 指令打通：`/team` 交互式提示与 `/team <任务描述>` 一键拉起，内置极简轻量任务降级引导提示。
  - 全套新增 20 项 Multi-Agent 专项单元测试，全量 291 项测试 100% 绿灯。
  - 产出设计规范 `docs/specs/2026-09-16-phase-10-multi-agent-design.md`、实施计划 `docs/plans/2026-09-16-phase-10-multi-agent.md` 与评测报告 `docs/engineering/multi-agent-evaluation.md`。

## [0.7.0] - 2026-09-14

### Added
- **并行执行（Bounded Parallelism）** (Phase 09):
  - 有界并发执行器 `BoundedParallelExecutor`：基于 Java 21 平台线程池受控并发，实现单批次工具并发加速，并发度限制支持 1-16（默认 4）。
  - 并发资格与资源互斥检测 `ParallelEligibilityDecider` / `ResourceAccess`：严格遵循“读读共享、读写互斥、写写互斥”原则，对只读且显式声明 `allowsParallel` 的工具开放并发，写操作、命令执行、未声明工具及同文件操作强制串行。
  - 批次划分调度器 `ParallelBatchScheduler`：贪心将模型单轮次返回的多个工具调用切分为连续的并行批次与串行批次，精确记录原始索引 `IndexedToolCall`。
  - 乱序保序归并（In-order Merge）：多线程异步完成的工具结果在主线程按原始下标保序重构，确保 Prompt 上下文与 Observation 序列的绝对确定性。
  - 失败隔离与协作式取消（Fault Isolation & Cooperative Cancellation）：单工具超时（`toolTimeout`，默认 60s）转化为结构化错误，不波及同批次其他成功工具；Run 级 `CancellationToken` 协作式中断运行中与未启动任务。
  - `ReactAgent` 与 `PlanExecuteAgent` 深度整合：`EventSequencer` 线程安全升级，DAG 任务层支持受控有界并发度分块。
  - 配置与 CLI 选项扩展：支持 `--max-concurrency <1-16>` 与 `--tool-timeout <seconds>`，对应环境变量 `XHLCLI_MAX_CONCURRENCY`、`XHLCLI_TOOL_TIMEOUT_SECONDS` 及配置文件。
  - 全套新增 25 项单元与集成测试（全量 271 项测试 100% 绿灯）。
  - 产出设计规范 `docs/specs/2026-09-14-phase-09-parallel-execution-design.md`、实施计划 `docs/plans/2026-09-14-phase-09-parallel-execution.md` 与基准报告 `docs/engineering/parallel-execution-benchmark.md`。

## [0.6.0] - 2026-09-11

### Added
- **智能规划（Plan-and-Execute）** (Phase 08):
  - 任务领域模型 `Task`：支持多任务类型与生命周期状态，细粒度依赖绑定（`dependencies` / `dependents`）。
  - 执行计划聚合根 `ExecutionPlan`：实现 DFS 三色标记拓扑排序与严格有向环检测（Cycle Detection），杜绝非法/循环依赖任务进入执行层。
  - Kahn 分层批次算法（`getExecutionBatches`）：实现 DAG 拓扑分层推进、ASCII 边框可视化（`visualize`）与紧凑折叠摘要（`summarize`）。
  - 智能规划器 `Planner`：内置单步轻量任务快速识别与规则短路（`isSimpleGoal`），复杂任务 LLM 两遍建图解析，支持错误驱动的自适应重规划（`replan`）。
  - 人机协同审阅交互（HITL Review）：提供 `PlanReviewInputParser` 与 `PlanReviewHandler`，支持回车/run 确认执行、cancel/esc 零副作用取消、输入补充约束触发结合新条件的重新规划。
  - 执行引擎 `PlanExecuteAgent`（实现 `AgentRunner`）：DAG 拓扑分层调度，单任务主线程直跑，多任务受控并发（最多 4 线程）与独立内存缓冲流（`ByteArrayOutputStream`）日志隔离，彻底根除并发日志交错；单步任务受限 ReAct 循环（最多 5 轮）；具备 `MAX_REPLAN_ATTEMPTS = 2` 失败重排熔断保护与短期记忆自动回写。
  - CLI 指令打通：`/plan` 与 `/plan <任务描述>`，支持终端交互式任务规划与审阅。
  - 全套新增 31 项单元测试与端到端集成测试，全量 246 项测试 100% 绿灯。
  - 技术架构设计规范 `docs/specs/2026-09-11-phase-08-plan-and-execute-design.md`、实施计划 `docs/plans/2026-09-11-phase-08-plan-and-execute.md` 与评测报告 `docs/engineering/plan-and-execute-evaluation.md`。

## [0.5.0] - 2026-09-10

### Added
- **Codebase RAG** (Phase 07):
  - 引入嵌入式 SQLite 存储（`sqlite-jdbc:3.49.1.0`）与 Java AST 解析（`javaparser-core:3.28.0`）。
  - 基于文件 SHA-256 哈希的确定性增量索引机制，未修改文件毫秒级跳过，修改/删除实时同步。
  - 混合语义检索引擎 `CodeRetriever`：融合自然语言 Embedding、结巴与 ASCII 关键字分词（`RagQueryTokenizer`）、类型优先（method/class）及双重命中加权算法。
  - 结构化检索结果格式化器 `SearchResultFormatter`：CLI 友好卡片摘要与代码片段安全截断。
  - 只读 Agent 工具 `search_code`：作为自然语言代码探索入口注册到 `ToolRegistry`，并更新 Agent System Prompt。
  - CLI 交互指令支持：`/index [status|clean]`、`/search <query>` 与精确正则搜索 `/search-text <pattern>`。
  - 多 Provider 向量客户端 `EmbeddingClient`（Fake/Ollama/OpenAI/智谱），支持 100% 离线确定性单元测试。
  - 全套 24 项 RAG 专项测试与端到端黄金评测集 `CodeRetrieverGoldenSetTest`，全量测试 223 项全部通过。
  - 技术设计与评测报告 `docs/engineering/codebase-rag-evaluation.md`。

## [0.4.0] - 2026-09-07

### Added
- **Code Search** (Phase 06):
  - 全面扩充代码检索 Golden Set 评测集至 7 大真实场景（类定义、接口实现、方法调用、配置键、测试、入口函数、不存在符号安全负向用例），双引擎通过率 100%。
  - `GrepCodeTool` 无结果智能建议：依据当前搜索参数自动提示放宽大小写敏感、移除 glob 或缩短 pattern。
  - CLI 人工调试指令 `/search-text <pattern>`：终端直出高亮检索结果与 suggested_reads，无需消耗大模型 Token。
  - Agent System Prompt 核心检索流水线强化：明确 `glob_files -> grep_code -> read_file` 代码探索路径，强制本地代码首选原则，杜绝误触发网络搜索。
  - 发布交付物报告 `docs/engineering/code-search-golden-set.md`。

## [0.3.0] - 2026-09-02

### Added
- **Context & Memory** (Phase 05):
  - `TokenBudget` 字符级上下文预算管理与预估。
  - `ContextAssembler` 8 层标准上下文组装管线。
  - `MemoryManager` 与 `LongTermMemory`：项目级（`<workspace>/.xhlcli/memory`）与全局级（`~/.xhlcli/memory`）双重作用域隔离。
  - `ConversationHistoryCompactor`：长对话大模型驱动结构化无损压缩，保护 Tool Call / Tool Result 成对关系。
  - CLI 控制指令：`/context`、`/compact`、`/save`、`/memory`。
  - 动态实时长期记忆注入与端到端集成测试 `AgentMemoryIntegrationTest`。

- Phase 04 安全策略与人工审批 (Safety and Approval)：
  - 系统硬策略 `PathGuard`：路径围栏防逃逸（绝对路径越界、`..` 穿越、符号链接指向外部），不可被用户批准绕过。
  - 系统硬策略 `CommandGuard`：危险 Shell 命令 Fast-fail 拦截（`sudo`、`rm -rf /`、`mkfs`、`dd of=/dev`、fork bomb、`curl|sh`、`find /`、`chmod 777`、`shutdown/reboot`）。
  - 脱敏审计日志 `AuditLog`：每日 JSONL 结构化审计落盘（`~/.xhlcli/audit/`），自动掩码 Bearer Token、API Key、Password、Secret 等敏感凭据。
  - 风险分级策略 `ApprovalPolicy`：只读工具自动放行，写入/命令工具需人工审批，未注册工具默认高危。
  - 人工审批交互 `TerminalHitlHandler`：结构化终端审批框（CJK/Emoji 显示宽度精确对齐），支持 `y/a/n/s/m` 五种决策。
  - `DefaultToolExecutor` 编排：Schema → 硬策略 → HITL 审批 → 执行 → 审计，修改参数重新校验。
  - `ChatBootstrap` 装配与 `ChatLoop` `/clear` 联动清除会话临时授权。
  - `AgentSafetyIntegrationTest` 端到端安全闭环验证。
- Phase 03 本地工具集 (Local Tools)：
  - `WorkspacePathResolver`：强制限制文件操作在项目工作区内，防止路径穿越与非法越界。
  - 只读探索与读取：`list_dir`（过滤系统隐藏/构建目录）、`read_file`（支持 offset/limit 分页读取与行号标注）、`glob_files`（按 glob 匹配项目文件）。
  - 代码搜索体系：`JavaCodeSearchEngine`（纯 Java 跨平台搜索）、`RipgrepCodeSearchEngine`（流式 `rg --json` 快速搜索与超时降级）、`grep_code`（行上下文展示、预算截断与 suggested_reads 推荐）。
  - 受控写入与补丁：`write_file`（单文件 5MB 上限、自动建目录）、`apply_patch`（单处唯一匹配安全替换）。
  - 版本控制与 Shell：`git_diff`（工作区 Git 差异查看）、`execute_command`（短时受控 Shell 命令执行、输出截断与实时取消）。
  - `CodeSearchGoldenSetTest` 评测集与 `LocalToolsCodingLoopTest` 真实 Agent 循环集成测试。
- Phase 02 ReAct Agent：结构化 Tool Call/Observation、按原顺序的调用执行与调用 ID 关联回灌。
- 演示工具；参数 Schema 校验、结果预算、结构化工具失败和安全摘要。
- 最大迭代、600 秒整体超时、Ctrl+C 取消、一次空响应重试、连续三轮重复无进展保护，以及终态后禁止启动新的模型或工具工作。
- 统一 `RunEvent` 和 Plain 终端渲染；事件及渲染输出不包含 reasoning 或未脱敏凭据。

### Verification

- `./mvnw test` 全量通过 187 项自动化测试（覆盖 Phase 04 安全策略、HITL 审批、审计日志、所有 8 个本地工具、两个搜索引擎、Golden Set 评测集及完整 ReAct 工具循环）。

## [0.2.0] - 2026-08-26

### Added

- DeepSeek 流式终端对话、进程内多轮历史和 Token 用量展示。
- `/help`、`/config`、`/clear`、`/exit` 与响应期间 Ctrl+C 取消。
- `.env`、环境变量、用户 JSON 和命令行参数的安全配置分层。
- OpenAI-compatible SSE 解析、错误分类、超时和受控一次重试。
- 50 项离线自动测试、可执行 shaded JAR 和 macOS CI。

### Security

- API Key 不接受命令行参数或用户 JSON 持久化；终端、错误和 DEBUG 元数据统一脱敏。

### Verification

- Phase 01 通过真实 DeepSeek 五轮会话、Ctrl+C 取消恢复、演示脱敏和 macOS Java 21 CI 验收。

## [0.1.0] - 2026-08-25

### Added

- Java 21 Maven Wrapper 工程基线。
- 可执行 XhlCLI JAR，以及默认说明、`--help` 和 `--version` 命令。
- CLI 行为自动测试和 GitHub Actions Java 21 构建验证。
- Phase 00–18 产品需求、全局技术设计、路线图和协作规范。

[Unreleased]: https://github.com/sz-xiaohuolong/XhlCli/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/sz-xiaohuolong/XhlCli/releases/tag/v0.2.0
[0.1.0]: https://github.com/sz-xiaohuolong/XhlCli/releases/tag/v0.1.0
