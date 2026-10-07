# Release v0.13 Implementation Plan

<!-- Source: plans/2026-10-06-phase-15-terminal-productization.md -->

# Phase 15：终端产品化实施路线与计划 (Implementation Plan)

> **文档性质：** 阶段实施任务清单与执行蓝图  
> **计划日期：** 2026-10-06  
> **目标版本：** `v0.13.0`  
> **对应 PRD：** [`docs/prd/phase-15-terminal-productization.md`](../prd/phase-15-terminal-productization.md)  
> **对应 Spec：** [`docs/specs/2026-10-06-phase-15-terminal-productization-design.md`](../specs/2026-10-06-phase-15-terminal-productization-design.md)  
> **执行准则：** 必须等待用户明确确认批准（Human Approval Gate）后方可开始编码！

---

## 1. 阶段目标与交付范围

将已有的 Agent 核心能力整合为 **稳定、清晰、自适应、可降级** 的工业级终端交互产品体验：
1. **统一渲染契约与双模实现 (`com.xhlcli.render`)**：定义 `TerminalRenderer` 接口，提供行内富文本 (`InlineTerminalRenderer`) 与纯文本 (`PlainRunRenderer`) 两种实现，并基于 `TerminalEnvironment` 自动完成环境探测与无缝降级；
2. **底部自适应动态状态栏 (`TerminalStatusBar`)**：集成 JLine 4 `org.jline.utils.Status`，基于 `TerminalWidthCalculator` 实现中英文与 Emoji 宽度精准计算，并根据 80/120/160 列终端实现多字段优先级智能裁剪；
3. **输出排版美化与工具折叠 (`com.xhlcli.render.format`)**：构建轻量级 `MarkdownRenderer`（支持标题、列表、边界代码块与自适应列宽对齐表格）、`DiffRenderer`（彩色 Git Diff 与变更统计）及 `ToolFoldingManager`（超长工具输出折叠）；
4. **终端交互增强三件套 (`com.xhlcli.cli.terminal`)**：实现 `TerminalCompleter`（统一斜杠命令、二级子命令、动态模型/MCP/Skill 及 `@path` 路径补全）、`TerminalInputHighlighter`（实时命令高亮与密钥警告）、以及具备敏感过滤与超长截断的 `SafeHistory`；
5. **审批体验闭环与指令运维**：改造 `TerminalHitlHandler` 消除控制台冲突与历史污染，扩展 `/history [list|clear]` 命令，全面统一 Agent、Planner、Worker 的输出至 `TerminalRenderer`；
6. **工程验证与阶段交付**：编写 `TerminalGoldenTest` 与 ANSI 纯度验证，保证全量自动化测试 100% 绿灯（430+ 项测试），产出评测物证，更新 Living Docs 并发布 Tag `v0.13.0`。

---

## 2. 详细任务分解 (Task Breakdown)

### Task 1: 终端环境探测与渲染器抽象契约 (`com.xhlcli.render`)
- [ ] **1.1** 创建环境检测工具 `com.xhlcli.render.TerminalEnvironment`：
  - 检测 `NO_COLOR` 环境变量（非空即生效，遵循 no-color.org 标准）；
  - 检测 `--plain` 命令行启动参数；
  - 检测终端类型是否为 `dumb` 或无交互 TTY（管道重定向）；
  - 提供 `isAnsiSupported()`、`isInteractiveTerminal()` 判断方法。
- [ ] **1.2** 定义统一渲染契约 `com.xhlcli.render.TerminalRenderer` 接口：
  - 核心事件渲染：`accept(RunEvent event)`；
  - 首屏与引导：`printWelcome(String model, String version, String workspace, TerminalExtSummary summary)`；
  - 交互消息输出：`printMessage(String)`、`printErrorMessage(String)`、`printHelp()`、`printConfig(ChatConfig)`、`printUnknownCommand(String)`、`printCleared()`、`printGoodbye()`；
  - 状态同步：`updateStatus(TerminalStatus status)`；
  - 资源释放：实现 `AutoCloseable`。
- [ ] **1.3** 重构 `PlainRunRenderer` 实现 `TerminalRenderer` 接口：
  - 保留纯文本输出能力，严格确保不输出任何 ANSI 控制字符（`\u001B[`）；
  - 状态更新以纯文本行格式简洁打印。
- [ ] **1.4** 编写 `TerminalEnvironmentTest` 与 `PlainRunRendererTest`。

### Task 2: 行内终端渲染器、状态栏与事件串行化 (`com.xhlcli.render.terminal`)
- [ ] **2.1** 创建数据模型 `TerminalStatus` (mode, phase, model, currentTokens, maxTokens, activeMcpServers, activeSkills, workspacePath)；
- [ ] **2.2** 创建字符显示宽度计算器 `TerminalWidthCalculator`：
  - 基于 Unicode `WCWidth` 计算半角、全角（CJK）及 Emoji 的真实终端列宽；
  - 提供定宽截断与补充省略号 (`…`) 工具方法。
- [ ] **2.3** 创建底部状态栏管理器 `TerminalStatusBar`：
  - 接入 JLine 4 `org.jline.utils.Status`；
  - 按优先级规则动态裁剪：P0 (模式+阶段+模型) > P1 (Token配额) > P2 (MCP/Skill) > P3 (路径)；
  - 针对 80、120、160 列宽度自适应计算，强制保证绝不换行破坏终端底部滚动区。
- [ ] **2.4** 实现 `InlineTerminalRenderer`：
  - 依赖 `Terminal`、`LineReader` 与 `Status`；
  - 流式文本和事件统一调用 `LineReader.printAbove(...)`，确保正在编辑的用户输入行不被覆盖；
  - 维护内部同步锁，实现事件原子化串行化输出，杜绝多线程竞争撕裂屏幕。
- [ ] **2.5** 编写 `TerminalStatusBarTest` 与 `InlineTerminalRendererTest`。

### Task 3: Markdown 排版与 Git Diff 格式化引擎 (`com.xhlcli.render.format`)
- [ ] **3.1** 创建轻量 Markdown 格式化器 `MarkdownRenderer`：
  - 标题高亮渲染 (`# `、`## `、`### `)；
  - 列表层级与圆点格式化 (`• `)；
  - 代码块包裹边框 (`┌─── lang ───` 与 `└──────────`) 与语法缩进；
  - 结构化表格自适应：计算各列最大字符宽度，根据终端列宽按比例预算列宽，自动对齐内容并渲染整洁边框，列超宽时自适应裁剪；
  - 行内代码与粗体强调高亮。
- [ ] **3.2** 创建差异渲染器 `DiffRenderer`：
  - 识别 Unified Diff 补丁格式；
  - 首行输出文件路径与统计 `📄 File: src/... (+12, -3)`；
  - 行级着色：`+` 绿色（新增），`-` 红色（删除），`@@` 黄色（行号位置），其他浅灰。
- [ ] **3.3** 创建工具输出折叠管理器 `ToolFoldingManager`：
  - 短输出（<= 5 行且 <= 200 字符）直接渲染摘要；
  - 长输出自动折叠为 `✓ Tool name: N lines omitted`，防止长日志刷屏。
- [ ] **3.4** 编写 `MarkdownRendererTest`、`DiffRendererTest` 与 `ToolFoldingManagerTest`。

### Task 4: 终端输入交互增强三件套 (`com.xhlcli.cli.terminal`)
- [ ] **4.1** 实现统一补全器 `TerminalCompleter` (实现 JLine `Completer`)：
  - 一级斜杠命令补全 (`/help`, `/model`, `/mcp`, `/skill`, `/plan`, `/team`, `/history`, `/browser` 等)；
  - 二级子命令补全 (`/model use`, `/mcp restart`, `/skill enable`, `/history clear` 等)；
  - 三级动态词汇补全：动态接入 `LlmProviderRegistry` 模型名、`McpServerManager` 服务名、`SkillRegistry` 技能名；
  - 项目路径补全：以 `@` 触发或路径参数时，自动扫描 `projectDirectory` 补全相对路径。
- [ ] **4.2** 实现实时高亮器 `TerminalInputHighlighter` (实现 JLine `Highlighter`)：
  - 命令与子命令语法着色；
  - `@path` 路径引用青色下划线高亮；
  - 疑似敏感凭据（如 `sk-...`，`ghp_...` 等特征）黄色/红色下划线警告。
- [ ] **4.3** 实现安全持久化历史管理器 `SafeHistory` (包装 JLine `History`)：
  - 存储路径 `~/.xhlcli/history`；
  - 过滤空白行、连续重复行、包含密钥模式的行、超长文本 (> 4000 字符) 及 Base64 数据；
  - 文件不可写或损坏时优雅降级为内存历史，输出 warning 日志。
- [ ] **4.4** 编写 `TerminalCompleterTest`、`TerminalHighlighterTest` 与 `SafeHistoryTest`。

### Task 5: 审批体验闭环、运维指令与输出收拢 (`com.xhlcli.cli`)
- [ ] **5.1** 改造 `TerminalHitlHandler`：
  - 移除野生 `BufferedReader(System.in)` 与直接 `System.out.println`；
  - 使用统一终端会话读取审批按键（`y` / `n` / `a` / `m`）；
  - 决策后刷新恢复终端模式与输入提示行，确保 raw mode 和历史不残留脏数据。
- [ ] **5.2** 扩展历史管理指令 `/history`：
  - 在 `ChatCommand` 与 `ChatCommandParser` 中注册 `HISTORY`；
  - 支持 `/history list [n]` 查看历史记录；
  - 支持 `/history clear` 清空本地历史文件。
- [ ] **5.3** 重构 `ChatLoop`：
  - 接入 `TerminalRenderer`，将分散的控制台输出收拢入统一通道；
  - 在执行各阶段驱动更新 `TerminalStatus`（模式切换、阶段流转、Token 统计）；
  - 更新 `/help` 菜单。
- [ ] **5.4** 编写 `TerminalHitlHandlerTest` 与 `HistoryCommandTest`。

### Task 6: 核心集成、Golden Test 验收、物证与发布
- [ ] **6.1** 重构 `ChatBootstrap`：
  - 结合 `TerminalEnvironment` 自动装配 `InlineTerminalRenderer` 或 `PlainRunRenderer`；
  - 装配 `TerminalCompleter`、`TerminalInputHighlighter` 与 `SafeHistory` 至 `JLineTerminalSession`；
  - 首屏展示扩展状态摘要 (MCP, Skills, Browser) 与 3 条极简入门提示。
- [ ] **6.2** 编写 `TerminalGoldenTest`：
  - 校验 80、120、160 列终端下状态栏输出排版基准；
  - 校验 Markdown 表格、代码块边界及 Diff 颜色基准；
  - 校验 Plain 模式与 `NO_COLOR` 模式下的 ANSI 纯净度（无任何 `\u001B[`）。
- [ ] **6.3** 运行 `./mvnw clean verify` 确保全量测试 100% 绿灯（430+ 项测试全部通过）。
- [ ] **6.4** 产出评测物证 `docs/engineering/terminal-productization-evaluation.md`。
- [ ] **6.5** 同步更新 Living Docs (`PROJECT.md`, `ROADMAP.md`, `TECH_DESIGN.md`, `specs/README.md`, `plans/README.md`, `CHANGELOG.md`, `AGENTS.md`, `README.md`)。
- [ ] **6.6** 升级 `pom.xml` 为 `0.13.0-SNAPSHOT`，执行 Git 提交、打 Tag `v0.13.0` 并推送到 GitHub。
