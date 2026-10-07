# Release v0.13 Product Specification

<!-- Source: specs/2026-10-06-phase-15-terminal-productization-design.md -->

# Phase 15：终端产品化与交互治理架构设计规范 (Technical Design Specification)

> **文档状态：** 提案中 (Proposed)  
> **设计日期：** 2026-10-06  
> **目标版本：** `v0.13.0`  
> **对应 PRD：** [`docs/prd/phase-15-terminal-productization.md`](../prd/phase-15-terminal-productization.md)  
> **实现状态：** 待用户批准实施

---

## 1. 架构目标与四维交付定位

在具备了受控 ReAct、代码库 AST RAG、DAG 规划执行、Multi-Agent 协作、MCP 扩展与分层 Skill 系统之后，XhlCLI 已具备强大的后端推理与工程执行能力。然而，终端用户体验直接决定了 Agent 的可用性与可靠性：
1. **输入与输出交错混乱**：长文本流式输出、工具异步回调、Worker 并行日志与后台探测若直接竞争终端标准输出（`System.out`），会导致用户正在编辑的输入行被冲毁撕裂；
2. **缺乏状态可见性**：用户无法直观获知当前活动模型、上下文 Token 配额消耗、多 Agent 执行阶段、以及已挂载的 MCP/Skill 运行态；
3. **输出排版原始粗糙**：大段 Markdown 表格、代码块、超长工具结果与 Git Diff 缺乏层次化排版与语法高亮，长日志严重淹没关键决策信息；
4. **终端环境碎片化与缺乏降级**：在 CI 环境、无 TTY 管道重定向、`NO_COLOR` 或窄屏幕终端下，ANSI 控制字符会导致乱码或排版崩溃。

Phase 15 将已有 Agent 能力整合为 **稳定、清晰、自适应、可降级** 的工业级终端交互产品体验。

### [What] 职责与定位 (Role & Boundary)
- **拓扑位置**：处于系统的最前端表现层（Presentation Layer），封装 JLine 4 终端能力，向上提供统一的渲染抽象契约，向下适配多样化的终端环境与回退场景。
- **输入输出**：
  - 输入：Agent 执行事件流（`RunEvent`）、系统状态通知、人工审批请求（HITL）、键盘交互输入事件（Tab 补全、语法高亮、历史导航）。
  - 输出：结构化渲染的行内流式正文、底部自适应动态状态栏（`Status`）、安全脱敏的输入历史、以及在非交互终端下的纯文本降级输出。
- **职责边界**：
  - **负责**：
    - `TerminalRenderer` 契约抽象与行内富文本 (`InlineTerminalRenderer`) / 纯文本 (`PlainRunRenderer`) 双模实现；
    - 首屏轻量引导、流式增量输出与用户输入隔离保护；
    - 底部状态栏自适应宽度计算（支持 CJK/Emoji 显示列宽与优先级裁剪）；
    - Markdown 轻量渲染（标题、列表、代码块边界、表格自适应列宽对齐）；
    - 工具输出折叠与 Git Diff 语法着色；
    - 统一命令/路径自动补全（JLine `Completer`）与语法高亮（JLine `Highlighter`）；
    - 包含凭据脱敏与超长过滤的持久化安全输入历史（`SafeHistory`）；
    - 审批状态无缝暂停与恢复（防 raw mode 残留与历史污染）；
    - `NO_COLOR`、窄屏（80/120/160列）与管道重定向优雅降级。
  - **不负责**：
    - 不构建全屏多栏 TUI（如 complex curses/fullscreen 布局，保持原生终端滚动历史与管道友好性）；
    - 不做 IDE 级别的完整代码编辑器与树形目录控件；
    - 不依赖专属终端图形协议（如 iTerm2/Kitty 协议渲染图片）；
    - 视觉语法高亮仅为辅助提示，绝不作为安全防线替代底层 `PathGuard` 与 `CommandGuard`。

---

## 2. 系统拓扑与核心流转链路

### 2.1 整体架构拓扑

```text
+-----------------------------------------------------------------------------------------+
|                                终端用户交互与表现层 (CLI UI)                            |
+-----------------------------------------------------------------------------------------+
       |                                                                           ^
       | 键盘输入 / Tab 补全 / 历史回溯                                            | 屏幕渲染 / 状态刷新
       v                                                                           |
+---------------------+    +-------------------------+    +--------------------------------+
|  JLineTerminalSession|--->|  TerminalInputEnhancer  |<---|  InlineTerminalRenderer        |
|  - JLine Terminal   |    |  - TerminalCompleter    |    |  - LineReader.printAbove()     |
|  - JLine LineReader |    |  - TerminalHighlighter  |    |  - org.jline.utils.Status      |
|  - SafeHistory      |    |  - Path/Command Source  |    |  - MarkdownRenderer            |
+---------------------+    +-------------------------+    |  - DiffRenderer                |
       |                                                  |  - ToolFoldingManager          |
       | 用户提交 Prompt / 斜杠命令                        +--------------------------------+
       v                                                                           ^
+----------------------------------------------------------------------------------+-------+
|                                   ChatLoop 调度中枢                                      |
|  - 斜杠命令分发 (/model, /mcp, /skill, /plan, /team, /history, /prompt, ...)              |
|  - 状态同步 (TerminalStatus: Mode, Phase, Model, Token, MCP, Skills)                     |
+----------------------------------------------------------------------------------+-------+
       |                                                                           ^
       | 驱动执行                                                                  | 统一 RunEvent / 通知
       v                                                                           |
+------------------------------------------------------------------------------------------+
|                  Agent 业务与执行核心 (ReactAgent / PlanAgent / Team / MCP)               |
+------------------------------------------------------------------------------------------+
```

### 2.2 核心交互流转链路 (Happy Path)

1. **启动与环境探测**：
   - `TerminalEnvironment` 检测当前运行环境：检查 `NO_COLOR` 环境变量、`--plain` 启动参数、以及标准输出是否重定向或终端类型为 `dumb`；
   - 若支持交互式终端，构建 `InlineTerminalRenderer`；若为非交互终端或开启无颜色模式，降级为 `PlainRunRenderer`；
   - 渲染产品首屏（FR-15-01）：显示产品名称、版本、当前模型、项目目录、扩展状态摘要以及 3 条入门提示，隐藏不必要的后台启动日志。
2. **交互循环与状态同步**：
   - 底部状态栏展示当前 Agent 状态：`[REAct] [Idle] | deepseek-chat | Ctx: 4.5k/64k (7%) | MCP: 2 | Skills: 4 | /path/to/project`；
   - 用户键入字符时，`TerminalHighlighter` 实时高亮斜杠命令与 `@path` 路径，遇到疑似 API Key / Token 时黄色告警；
   - 用户按 `<TAB>` 时，`TerminalCompleter` 快速提示斜杠命令、二级子命令、动态模型列表、已挂载 MCP/Skill 名称及项目相对路径。
3. **流式输出与输入防覆盖 (FR-15-02)**：
   - 模型返回 `TextDelta` 或工具执行中，`InlineTerminalRenderer` 通过 `lineReader.printAbove(...)` 增量写入终端正文区域；
   - 正在编辑的输入提示行与用户输入文本始终被 JLine 完整保留在屏幕底端，绝不发生光标错位或被流式文本覆盖。
4. **工具调用与折叠渲染 (FR-15-05)**：
   - 工具开始：渲染清晰标签 `⚙️  [Tool] readFile: path="src/main/..."`；
   - 工具完成：根据输出行数与长度自动判定。超过阈值（如 > 5 行）自动折叠为 `✓ Tool readFile (142 行结果已折叠)`；短结果直接格式化输出；
   - Git Diff：自动识别补丁与 diff 内容，新增行以绿色渲染，删除行以红色渲染，并统计变更规模。
5. **审批交互与终端模式恢复 (FR-15-06)**：
   - 触发高危操作时，`TerminalHitlHandler` 暂停输入读取，通过 `Renderer` 输出醒目的结构化审批卡片；
   - 接收用户单键决策（`[y]` / `[n]` / `[a]` / `[m]`）；
   - 决策完成后，干净注销审批临时状态，刷新底部状态栏，完全恢复正常的终端交互模式与输入提示行。
6. **历史记录安全入库 (FR-15-09)**：
   - 提交后的命令经由 `SafeHistory` 校验：滤除空白行、滤除连续重复、过滤超长文本 (> 4000 字符) 及敏感凭据；
   - 格式化后安全追加写入持久化历史文件 `~/.xhlcli/history`。

---

## 3. 详细模块设计与契约规范

### 3.1 渲染接口与双模实现 (`com.xhlcli.render`)

```java
package com.xhlcli.render;

import com.xhlcli.config.ChatConfig;
import com.xhlcli.model.RunEvent;

public interface TerminalRenderer extends AutoCloseable {
    /** 接收并渲染 Agent 执行事件流 */
    void accept(RunEvent event);

    /** 渲染首屏欢迎与环境信息 */
    void printWelcome(String model, String version, String workspace, TerminalExtSummary summary);

    /** 打印帮助文档 */
    void printHelp();

    /** 打印配置详情 */
    void printConfig(ChatConfig config);

    /** 打印通用消息 */
    void printMessage(String message);

    /** 打印错误消息 */
    void printErrorMessage(String message);

    /** 打印未知命令提示 */
    void printUnknownCommand(String command);

    /** 打印清屏/新会话提示 */
    void printCleared();

    /** 打印退出提示 */
    void printGoodbye();

    /** 更新底部状态栏信息 */
    void updateStatus(TerminalStatus status);

    /** 清理并关闭渲染器 */
    @Override
    void close();
}
```

#### 双模实现定位：
1. **`InlineTerminalRenderer` (行内流式终端实现)**：
   - 依赖 `Terminal`、`LineReader` 与 `org.jline.utils.Status`；
   - 使用 ANSI 颜色样式与文本高亮；
   - 正文输出通过 `LineReader.printAbove(...)` 保证输入行稳定性；
   - 底部使用 `Status.update(...)` 显示自适应状态行；
   - 集成 `MarkdownRenderer` 与 `DiffRenderer` 格式化正文。
2. **`PlainRunRenderer` (纯文本降级实现)**：
   - 不依赖 JLine 光标控制与 `Status`；
   - 彻底禁用 ANSI 转义字符序列（输出干净纯文本）；
   - 状态变化以纯文本行格式输出；
   - 专门用于 CI 流水线、输出重定向或开启 `NO_COLOR` / `--plain` 场景。

### 3.2 底部状态栏与自适应裁剪 (`com.xhlcli.render.terminal`)

#### 数据模型 `TerminalStatus`：
```java
public record TerminalStatus(
    String mode,          // "REAct", "Plan", "Team"
    String phase,         // "Idle", "Thinking", "Tool: read_file"
    String model,         // "deepseek-chat"
    int currentTokens,    // 4520
    int maxTokens,        // 64000
    int activeMcpServers, // 2
    int activeSkills,     // 4
    String workspacePath  // "/Users/.../code/xhlcli"
) {}
```

#### 自适应宽度与优先级裁剪算法 (`TerminalStatusBar`)：
终端宽度有限（常见 80、120、160 列），字段超宽时必须按**确定性优先级**裁剪，严防换行破坏终端底部布局：
- **P0（始终保留）**：运行模式 `[REAct]`、当前阶段 `[Thinking]`、当前模型 `deepseek-chat`；
- **P1（次高优先级）**：上下文 Token 消耗与百分比 `[4.5k/64k 7%]`；
- **P2（中优先级）**：扩展摘要 `[MCP:2 Skills:4]`；
- **P3（最低优先级，最先裁剪）**：工作区路径（当列宽不足时缩写为目录基名或 `.../dirname`）。
- **列宽计算**：使用 `TerminalWidthCalculator`（基于 `WCWidth`），精准计算中文全角字符（2 列）与英文半角字符（1 列），避免 Emoji 与 CJK 导致截断越界。

### 3.3 Markdown 与 Git Diff 格式化引擎 (`com.xhlcli.render.format`)

1. **`MarkdownRenderer`**：
   - **标题**：`# H1` (加粗青色), `## H2` (青色), `### H3` (下划线)；
   - **列表项**：无序列表 `- ` 格式化为符号 `• ` 并带对应缩进；有序列表 `1. ` 规范化缩进；
   - **代码块**：检测 ```` ```lang ... ``` ````，为代码块渲染细线边界框（如 `┌─── lang ───` 与 `└──────────`），并使用淡色背景或特殊前缀；
   - **表格**：解析 Markdown 表格语法，扫描所有单元格计算各列最大显示字符宽；根据终端列宽按比例预算列宽，自动对齐内容并渲染整洁的 ASCII/Unicode 边框；超宽时优雅裁剪单元格文本（尾部加 `…`）；
   - **行内样式**：加粗 `**text**`、行内代码 `` `code` `` 赋予醒目样式。
2. **`DiffRenderer`**：
   - 自动识别 Git Patch / Unified Diff 格式；
   - 首行输出文件路径与统计：`📄 File: path (+12 -3)`；
   - 逐行高亮：`+` 绿色（新增行），`-` 红色（删除行），`@@` 黄色（定位块），其他上下文灰色；
   - 在 Plain 模式下保留原生文本结构，不带 ANSI 色彩。

### 3.4 交互增强：补全、高亮与安全历史 (`com.xhlcli.cli.terminal`)

1. **统一命令与路径补全器 (`TerminalCompleter`)**：
   - 实现 JLine `Completer` 接口；
   - **一级命令补全**：`/help`, `/clear`, `/config`, `/context`, `/compact`, `/save`, `/memory`, `/search`, `/search-text`, `/index`, `/plan`, `/team`, `/model`, `/mcp`, `/browser`, `/skill`, `/prompt`, `/history`, `/exit`；
   - **二级子命令补全**：
     - `/model` -> `list`, `use`, `status`
     - `/mcp` -> `list`, `status`, `tools`, `resources`, `read`, `restart`, `stop`, `start`, `logs`
     - `/skill` -> `list`, `show`, `enable`, `disable`, `reload`
     - `/prompt` -> `show`, `export`
     - `/browser` -> `status`, `connect`, `disconnect`, `tabs`
     - `/memory` -> `list`, `search`, `delete`, `clear`
     - `/history` -> `list`, `clear`
     - `/index` -> `status`, `clean`
   - **三级动态词补全**：
     - `/model use <TAB>` 从 `LlmProviderRegistry` 动态读取模型 ID 与别名；
     - `/mcp status|restart|... <TAB>` 从 `McpServerManager` 动态读取已配置 Server 名称；
     - `/skill show|enable|... <TAB>` 从 `SkillRegistry` 动态读取已发现 Skill 名称；
   - **项目路径补全**：
     - 输入以 `@` 开头（例如 `@src/ma<TAB>`）或特定文件参数时，自动从 `projectDirectory` 递归扫描匹配文件与目录，补全相对路径。
2. **实时语法高亮器 (`TerminalInputHighlighter`)**：
   - 实现 JLine `Highlighter` 接口；
   - 斜杠命令前缀渲染为主题色；
   - `@path` 路径引用渲染为青色下划线；
   - 敏感凭据探测：检测形如 `sk-[a-zA-Z0-9]{20,}` 或 `ghp_[a-zA-Z0-9]{36}` 时，将输入字符标记为黄色警告下划线，提醒用户谨防误提交密钥。
3. **安全持久化历史 (`SafeHistory`)**：
   - 基于 JLine `DefaultHistory` 包装或实现 `History` 接口；
   - 持久化文件存储在 `~/.xhlcli/history`；
   - **入库安全过滤规则**：
     1. 忽略空白行或仅包含空格的行；
     2. 忽略与上一条完全相同的连续重复行；
     3. 过滤包含敏感密钥格式的输入（调用 `SecretRedactor` / 密钥正则扫描）；
     4. 过滤超大粘贴文本（单条长度超过 4000 字符）；
     5. 过滤包含 Base64 图片数据的文本（如 `data:image/...`）；
   - **容错降级**：若历史文件所在磁盘满、无写入权限或文件损坏，记录 warning 日志并自动退化为内存临时历史，绝不崩溃主交互循环；
   - **终端维护指令**：支持 `/history list [limit]` 查看最近历史，`/history clear` 一键清空本地历史文件。

### 3.5 审批交互无缝恢复与事件串行化 (`TerminalHitlHandler` + `EventChannel`)

1. **审批交互状态保护 (FR-15-06)**：
   - `TerminalHitlHandler` 改造：不再直接创建野生的 `new BufferedReader(System.in)` 和直接调用 `System.out.println`；
   - 改为通过 `JLineTerminalSession` 统一读取输入，使用 `TerminalRenderer` 输出结构化审批卡片；
   - 审批完成后，重绘输入提示符与状态行，确保 JLine 终端模式（raw mode / cooked mode）状态完好，不污染历史。
2. **事件串行化防竞争 (FR-15-11)**：
   - 所有的输出通道（包括 Planner 规划输出、Team Worker 异步汇报、MCP Server 日志、后台健康探测）全部接入 `TerminalRenderer`；
   - `InlineTerminalRenderer` 内部维护并发同步锁，所有写入操作原子化，彻底根除多线程交错导致的输出撕裂。

---

## 4. 边界场景与异常防御策略

| 边界场景 | 风险分析 | 防御与解决策略 |
| :--- | :--- | :--- |
| **超窄终端 (< 60 列)** | 状态栏换行导致终端疯狂滚动，输入行错位 | `TerminalStatusBar` 检测列宽，低于 80 列时压缩为极简状态 `[REAct] deepseek [5k]`, 绝不触发换行 |
| **中英文字符与 Emoji 混排** | 字符数不等同于显示列宽，导致截断对齐错误 | 引入 `TerminalWidthCalculator`，使用 Unicode `WCWidth` 计算全角宽度与图元簇宽度 |
| **管道重定向与 CI 环境** | 收到 ANSI 控制码导致日志文件充满乱码 | `TerminalEnvironment` 自动探测无 TTY / 文件重定向，强制切入 `PlainRunRenderer` |
| **`NO_COLOR` 环境变量声明** | 用户明确禁用色彩 | 遵循 [no-color.org](https://no-color.org) 标准，完全剥离所有 ANSI 颜色代码，保留纯文本排版 |
| **用户在模型流式输出时打字** | 正在输入的字符被流式文字冲掉或被截断 | `InlineTerminalRenderer` 强制调用 JLine 的 `LineReader.printAbove()`，保证输入缓冲区始终置底且完整 |
| **历史文件损坏或权限受限** | 启动抛出 `IOException` 导致 CLI 无法打开 | `SafeHistory` 捕获异常，输出告警提示，平滑降级为基于内存的临时历史列表 |
| **输入包含超大粘贴代码 (>10KB)** | 历史文件急剧膨胀，再次打开时卡顿 | `SafeHistory` 设定 4000 字符阈值，超长输入不持久化入磁盘文件 |

---

## 5. 验收标准与测试策略 (DoD)

1. **自动化测试套件**：
   - 新增 35+ 项单元与集成测试，覆盖环境检测、宽度计算、状态栏裁剪、Markdown 渲染、Diff 渲染、Completer 补全、Highlighter 高亮与 SafeHistory 过滤；
   - 全量回归测试套件 100% 绿灯（430+ 项测试全部通过）。
2. **Golden Test 验收**：
   - 编写 `TerminalRenderGoldenTest`，针对 80/120/160 列终端状态栏输出、Markdown 渲染结果与 Diff 高亮进行字符级比对。
3. **ANSI 与 Plain 纯净度断言**：
   - 验证在 `PlainRunRenderer` 及 `NO_COLOR` 模式下，输出流中绝对不包含 `\u001B[` 或任何 ANSI 控制符。
4. **历史文件安全性校验**：
   - 测试提交测试用 API Key 与大文本，验证退出后 `~/.xhlcli/history` 中不含任何敏感密钥与超长文本。
5. **Living Docs 与工程物证**：
   - 产出评测报告 `docs/engineering/terminal-productization-evaluation.md`；
   - 同步更新全套 Living Docs，版本号升级至 `0.13.0-SNAPSHOT`，打 Tag `v0.13.0` 并推送到 GitHub。
