# 方案设计 — 桌面端 Plan 划线批注与 Diff 行内评论回发

推荐直接在现有 Electron + React 桌面端（`desktop/src/`）内置结构化批注与 Diff 行内评论能力，并仅对 Java 后端 `PlanExecuteAgent`、`ExecutionPlan` 与 `DesktopService` 做最小扩展以支持“同意执行并附带备注（`execute + notes`）”与结构化反馈上限放宽。前端与后端继续沿用已有的私有 JSONL stdio 信道与 `localStorage` 草稿保存，不引入 `plannotator` 外部二进制或额外 HTTP 服务，确保零安装摩擦与单次构建交付；同时将 Plan 版本对比（Plan Diff）显式排除出本次实现范围。

| 文档信息 | 内容 | 关联信息 | 内容 |
|---|---|---|---|
| 关联需求 | [桌面端 Plan 与 Diff 批注 PRD](../prd/desktop-plan-diff-annotations.md)（`REQ-PDA01`～`REQ-PDA04`、`AC-PDA01`～`AC-PDA10`） | 当前技术设计 | [TECH_DESIGN.md](../TECH_DESIGN.md#桌面-mvp-适配增量2026-11-06) |
| 作者 | Antigravity Agent | 最后更新 | 2026-11-17 |
| 适用范围 | `xhlcli/desktop/src/` 与 `com.xhlcli.{plan,agent,desktop}` | 决策依据 | 用户 2026-11-17 问答四项选择 & [桌面视觉约定](../../DESIGN.md) |

- Status: `APPROVED`

---

## 1. 背景与设计目标

### 1.1 技术现状与问题

根据 [`TECH_DESIGN.md`](../TECH_DESIGN.md#计划确认与正文展示修复2026-11-06) 及代码核验：
1. **Plan 审阅现状：** [`DesktopService.reviewPlan`](../../src/main/java/com/xhlcli/desktop/DesktopService.java#L281) 通过 `plan.ready` 下发 `{ id, goal, summary, tasks }`，[`turn-view.jsx`](../../desktop/src/turn-view.jsx#L54) 的 `PlanReview` 组件仅提供整块文字浏览和单个全局 `<textarea>`；`DesktopService.respondToPlan` 仅处理 `supplement` 携带的 `feedback`（上限 8000 字符），`execute` 分支不接收执行备注，[`PlanExecuteAgent`](../../src/main/java/com/xhlcli/agent/PlanExecuteAgent.java#L219) 也没有在直接执行阶段透传备注的字段。
2. **Diff 面板现状：** [`DesktopChanges.diff`](../../src/main/java/com/xhlcli/desktop/DesktopChanges.java#L42) 返回 `List<{ path, type, diff }>`（标准 JGit `DiffFormatter` unified diff 字符串，单文件上限 200,000 字符）；[`app.jsx`](../../desktop/src/app.jsx#L119) 中右侧 `.diff-panel` 固定宽 37%，仅按 `+`/`-`/`@@` 染成只读 `<pre>`，无真实行号解析、无行选区评论、无草稿缓存、无汇总回发入口。

### 1.2 技术目标与非目标

| 技术目标 | 关联需求或约束 | 完成条件及依据 |
|---|---|---|
| 在 `PlanReview` 内实现内联划词（`COMMENT`/`DELETION`）、步骤快捷批注与可选宽屏双栏精审 | `REQ-PDA01` / `AC-PDA01`、`AC-PDA02` | `annotations.mjs` + `turn-view.jsx` + `plan-review.css` 完成渲染与双向同步，`conversation.test.cjs` 与实机检查通过 |
| 打通 `supplement` 汇总反馈及 `execute + notes` 备注透传到任务执行上下文 | `REQ-PDA02` / `AC-PDA03`～`AC-PDA05` | `ExecutionPlan`、`PlanExecuteAgent`、`DesktopService` 改动通过 Java 单元测试 |
| 实现 Unified Diff 双行号解析、单/多行选区评论、宽度切换与汇总填入输入框/直接发送 | `REQ-PDA03`、`REQ-PDA04` / `AC-PDA06`～`AC-PDA10` | `annotations.mjs`、`diff-review.jsx`、`app.jsx` 通过 Node 单测、esbuild 构建及实机验证 |

- **非目标**：不新增计划版本对比（Plan Diff）；不引入 `@pierre/diffs`、CodeMirror、Bun 或 `plannotator` 外部 CLI 进程；不开放新的本地 HTTP 监听端口；不改动 CLI 纯终端 `/plan` 交互流程。
- **设计约束**：遵循 [`DESIGN.md`](../../DESIGN.md) 浅灰/白底/细边框/`#1767b3` 浅色体系；不增加 `desktop/package.json` 第三方依赖；所有可测试纯逻辑抽入 `.mjs` 以兼容现有 `node --test` 运行器。

---

## 2. 技术栈与工程约定

### 2.1 技术栈选择

| 使用位置 | 技术及版本 | 复用或新增 | 版本来源与选择理由 |
|---|---|---|---|
| 桌面前端 UI | `react` 19.3.1、`react-dom` 19.3.1、`react-markdown` ^10.1.0、`remark-gfm` ^4.0.1、`lucide-react` ^0.468.0 | 复用 | 来源：`desktop/package.json`；零新增 npm 依赖，直接由 `esbuild` 0.28.2 打包。 |
| 桌面单测与构建 | Node.js 内置 `node --test` + `react-dom/server` + `esbuild` | 复用 | 来源：`desktop/test/conversation.test.cjs`；将 Diff 解析与反馈格式化独立为 `.mjs` 后可纯离线高覆盖单测。 |
| Java 核心与桌面服务 | Java 21、Jackson、`org.eclipse.jgit` 7.6 | 复用 | 来源：`pom.xml`；直接复用 `ExecutionPlan`、`PlanExecuteAgent` 与 `DesktopService` 现有模型和锁定机制。 |

### 2.2 目录职责与依赖方向

| 目录或模块 | 职责 | 可依赖的模块 | 禁止或需要隔离的依赖 |
|---|---|---|---|
| `desktop/src/annotations.mjs` | Unified Diff 解析、Plan/Diff 批注结构构造、Markdown 反馈拼装、`localStorage` 安全读写 | 仅标准 ES2022 语法 | 禁止依赖 React DOM、Electron IPC 或 Node 专属模块，确保浏览器与 Node 单测直接复用 |
| `desktop/src/diff-review.jsx` | 右侧 Diff 审阅面板（行号槽、单行/`Shift`多选、行内评论表单与列表、宽度切换、回发动作） | `react`, `lucide-react`, `./annotations.mjs` | 不直接持有全局会话存储状态，通过 props 与回调通知 `app.jsx` |
| `desktop/src/turn-view.jsx` | 扩展 `PlanReview`（划词选区浮层、步骤高亮与红线、双栏精审浮层、历史含备注摘要） | `react`, `react-markdown`, `./annotations.mjs`, `./plan-review.css` | 不直接调用 `turn.start` 或改写非活跃轮次 |
| `desktop/src/app.jsx` | 接入 `DiffReviewPanel`、`localStorage` 草稿协同、把 Diff 汇总追加写入底部 `drafts[draftKey]` 并聚焦 `inputRef` 或直接触发 `turn.start` | `window.xhl` IPC、`./diff-review.jsx`、`./annotations.mjs` | 不破坏 `directoryBusy` 目录并发互斥锁 |
| `com.xhlcli.{plan,agent,desktop}` | 承载 `PlanReviewDecision.execute(notes)`、`ExecutionPlan.executionNotes`、放宽 `feedback` 上限与 `plan.resolved` 持久化 | 现有 Java 模块 | 保持 CLI 默认 `ReviewHandler` 向后兼容 |

---

## 3. 总体架构与核心流程

### 3.1 组件边界与交互

```text
[Electron Renderer]
  ├── PlanReview (turn-view.jsx)
  │     ├── 选区/步骤事件 ──▶ annotations.mjs (buildPlanFeedback / draft storage)
  │     ├── 双栏精审浮层 (同步同份 annotations state)
  │     └── 提交 ──▶ window.xhl.request('plan.respond', { sessionId, planId, action, feedback })
  │
  └── DiffReviewPanel (diff-review.jsx) ◀── app.jsx (viewChanges)
        ├── parseUnifiedDiff(file.diff) ──▶ 双行号 + 行类型矩阵
        ├── 单行点击 / Shift 多选 ──▶ 行内评论列表 + localStorage 草稿
        └── 回发:
              ├── [汇总填入输入框] ──▶ app.jsx 拼接 drafts[draftKey] + inputRef.focus()
              └── [直接发送]        ──▶ window.xhl.request('turn.start', { sessionId, input }) + 清草稿关面板

[Java Backend (DesktopMain stdio JSON-RPC)]
  └── DesktopService.respondToPlan
        ├── action = "supplement" ──▶ PlanExecuteAgent 重新规划 (feedback <= 32000)
        └── action = "execute"    ──▶ feedback 可选写入 ExecutionPlan.executionNotes ──▶ 注入每个任务上下文
```

### 3.2 读写路径与状态传播

1. **Plan 批注与审批生命周期：**
   - 当 `plan.ready` 到达且 `turn.status === 'plan_review'`，前端以 `plan:<sessionId>:<plan.id>` 从 `localStorage` 读取未提交草稿（`{ annotations, globalFeedback }`）。
   - 用户划词触发 `onMouseUp`，若选区同时满足非空文本、起始和结束均落在当前 `PlanReview` 内容容器（`.plan-annotatable`）内且未跨出组件，则定位其归属块（`goal` / `summary` / `step:<index>:<task.id>`），在选区右上方渲染轻量浅色操作条（「添加批注」「红线删除」）；点击步骤右上角按钮则直接锚定整条步骤打开行内小表单。
   - **高亮与红线渲染规则：** 为避免直接破坏 `react-markdown` 的 AST/事件流，步骤及摘要采用安全片段包裹方案或渲染后高轻量测高亮组件：将锚定的 `originalText` 在对应文本/Markdown 容器的渲染文本节点里查找首个未标记子串，包入 `<mark className="plan-mark-comment">` 或 `<del className="plan-mark-deletion">`；若因跨标签排版未能精确匹配 DOM 文本节点，卡片仍完整保留在批注清单与侧栏中（满足 `REQ-PDA01` 降级要求）。
   - 点击「更新计划」或「同意并执行」后，待 `await onPlan(plan.id, action, compiledFeedback)` 成功 resolve，即清除 `plan:<sessionId>:<plan.id>` 草稿并同步等待下一批事件；若失败（抛异常），草稿原封不动保留供用户重试。

2. **Diff 行内评论生命周期：**
   - `app.jsx` 打开 `viewChanges(turn)` 时，将 `diff` 状态与 `session.id + ':' + turn.id` 关联，从 `localStorage` 恢复 `diff:<sessionId>:<turnId>`（`{ comments, generalNote }`）及宽度偏好 `xhl:diff-panel-width`。
   - 对每个文件执行 `parseUnifiedDiff(file.diff)` 产出连续渲染行。用户单击任意代码行即设定 `selection = { filePath, startIdx, endIdx }`；若按住 `Shift` 点击同一文件内另一代码行（或按下鼠标在代码行间拖拽并松开），将区间扩展为 `[minIdx, maxIdx]` 并在最后一行下方内嵌展开行内评论编辑器。
   - 点击「汇总填入输入框」时，调用 `formatDiffFeedback(...)` 生成结构化 Markdown，写入当前 `drafts[draftKey]`（若已有文本则用 `\n\n` 追加且去重），保留当前 Diff 评论以便用户继续核对，同时聚焦聊天底部输入框；用户可随时手动点「清空评论」或在点击「直接发送给 Agent」成功触发 `turn.start` 后自动清空 `diff:<sessionId>:<turnId>` 并关闭右侧面板。

---

## 4. 数据、接口与处理规则

### 4.1 前端批注数据模型（`desktop/src/annotations.mjs`）

- **PlanAnnotation：**
  ```js
  {
    id: string,
    type: 'COMMENT' | 'DELETION',
    targetKind: 'goal' | 'summary' | 'step',
    stepIndex?: number,      // 1-based 步骤号，targetKind==='step' 时必填
    taskId?: string,         // 对应 task.id
    originalText: string,    // 截取的原文片段（展示最多 400 字，格式导出保留简洁引用）
    text: string             // 批注内容（DELETION 可为空，导出为“建议删除”）
  }
  ```
- **DiffComment：**
  ```js
  {
    id: string,
    filePath: string,
    side: 'new' | 'old' | 'mixed', // 纯删除行用 'old'，其余优先 'new'
    startLine: number,             // 优先新文件行号；纯删除区间用旧文件起始行
    endLine: number,               // 区间结束行号
    snippet: string,               // 选中代码首尾精简引用（最多截断前 8 行 / 600 字符，避免反馈冗余）
    text: string                   // 必填评论正文
  }
  ```
- **Draft 存储限制：** 使用固定键前缀 `xhl:draft:plan:<sessionId>:<planId>` 与 `xhl:draft:diff:<sessionId>:<turnId>`；同时保留列表索引 `xhl:draft:index`，超过 100 条时按 LRU 移除最旧条目；任何 `JSON.parse` / `localStorage.setItem` 异常均 `try/catch` 静默降级为纯内存。

### 4.2 Java 接口与数据模型扩展

1. **`ExecutionPlan.java`：**
   - 新增字段 `private String executionNotes;` 与 `public String getExecutionNotes()` / `public void setExecutionNotes(String executionNotes)`。
2. **`PlanExecuteAgent.java`：**
   - `PlanReviewDecision` 新增工厂方法：
     ```java
     public static PlanReviewDecision execute(String notes) {
         String normalized = (notes == null || notes.isBlank()) ? null : notes.trim();
         return new PlanReviewDecision(PlanReviewAction.EXECUTE, normalized);
     }
     ```
   - `reviewAndExecutePlan(...)` 中 `EXECUTE` 分支：
     ```java
     if (decision.action() == PlanReviewAction.EXECUTE) {
         if (decision.feedback() != null && !decision.feedback().isBlank()) {
             plan.setExecutionNotes(decision.feedback().trim());
         }
         return executePlan(plan, events, cancellationToken, replanCount);
     }
     ```
   - 若步骤半途失败触发 `planner.replan(...)` 产生新计划对象，在再次调用 `reviewAndExecutePlan` 之前若原计划存在 `executionNotes` 亦同步透传到新计划（除非后续用户重新审批修改）。
   - `buildTaskContext(String goal, ExecutionPlan plan, Task task)`：若 `plan.getExecutionNotes()` 非空，追加一段：
     ```text
     用户批准执行附带备注（请在执行中一并落实）：
     <executionNotes>
     ```
3. **`DesktopService.respondToPlan(ObjectNode p)`：**
   - 常量 `MAX_PLAN_FEEDBACK_CHARS = 32_000`（原为 `8000`）；
   - 输入校验：
     - `execute`：`feedback` 为可选字符串（去除首尾空白；若长度 `> 32000` 抛出 `IllegalArgumentException("执行备注需要不超过 32000 个字符")`）；非空时调用 `PlanReviewDecision.execute(feedback)`，为空时调用 `PlanReviewDecision.execute()`；
     - `supplement`：`feedback` 必填且 `1..32000` 字符；
     - `cancel`：不读取 `feedback`；
   - `plan.resolved` 事件保持 `{ id, action, feedback }` 契约不变（无备注时 `feedback` 为 `""`），旧记录（缺失 `feedback`）在前端读取时完全兼容。

### 4.3 核心算法：Unified Diff 解析规则 (`parseUnifiedDiff`)

逐行扫描 `file.diff.split('\n')`：
1. 忽略末尾因 split 产生的孤立空串（但保留正文空白行 ` `）；
2. 遇到 `@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@` 正则行时重置游标：`oldCursor = parseInt($1, 10)`、`newCursor = parseInt($3, 10)`，标记为 `{ type: 'hunk', oldLine: null, newLine: null }`；
3. 尚未遇到任何 `@@` 前的 `diff --git`、`index`、`---`、`+++`、`Binary files ...` 标记为 `type: 'header'`（`oldLine: null, newLine: null`）；
4. 在 hunk 内：
   - `+` 开头：标记 `type: 'add'`，`oldLine: null, newLine: newCursor++`，去掉首字符 `+` 作为代码内容；
   - `-` 开头：标记 `type: 'del'`，`oldLine: oldCursor++, newLine: null`，去掉首字符 `-`；
   - `\` 开头（`\ No newline at end of file`）或截断末尾提示（`差异过长，已截断显示。`）：标记 `type: 'meta'`，不推进 `oldCursor` / `newCursor`；
   - 其余上下文行（通常 ` ` 开头）：标记 `type: 'context'`，`oldLine: oldCursor++, newLine: newCursor++`。

---

## 5. 关键选择与备选方案

| 方案 | 核心思路 | 收益 | 代价与限制 | 结论与理由 |
|---|---|---|---|---|
| **方案 A（推荐）：在现有 `desktop/src/` 内基于纯 `.mjs` 解析器 + React 组件内建划词/Diff 评论，Java 侧只补 `execute(notes)`** | 零新依赖，直接演进 `PlanReview` 与 `diff-panel`，双栏精审作为同状态展开视图 | 单体打包不变、样式完全符合 `DESIGN.md` 浅色规范、支持离线单测、无跨进程生命周期故障 | 高亮依靠 DOM 文本节点定位，复杂跨元素划词会有部分锚点降级只进侧栏列表 | **采用**；完全契合桌面端现有架构与约束 |
| 方案 B：内嵌 `plannotator` 二进制并通过 `BrowserWindow`/WebView 挂载 | 启动外部 `plannotator` 进程并用 Electron 加载其 localhost 页面 | 可直接复用 `plannotator` 的 KaTeX / Mermaid / `@pierre/diffs` UI | 强绑定外部二进制与 Bun 打包产物；暗/亮主题割裂；多了一层端口生命周期和本地数据分散管理 | **不采用**；破坏零外部依赖与统一桌面工作台定位 |
| 方案 C：引入 `@pierre/diffs` 或 Monaco Diff 到 `desktop/package.json` | 直接使用重型专业代码 Diff 控件渲染和插槽评论 | 大文件虚拟滚动和语法高亮更强 | 显著增加 `esbuild` 打包体积与暗/亮主题定制工作；当前 `DesktopChanges` 单文件已截断至 200KB，原生行渲染足够轻量流畅 | **暂不引入**；未来若需百万行级文件审查再评估 |

---

## 6. 安全、隐私与可观测性

- **输入与长度防御**：`DesktopService.respondToPlan` 校验 `feedback <= 32000` 字符；`turn.start` 沿用已有 `200000` 字符校验；批注内容经 `config.redact(...)` 脱敏后再随 `plan.resolved` 与 `history` 持久化，防止用户在批注中意外粘入本机路径或密钥导致明文泄露到事件文件。
- **权限边界不放松**：`execute + notes` 仅把用户意见作为任务执行指导，执行过程中涉及写入文件、危险命令或工作区外路径仍严格受 `ApprovalBridge`（若为按需审批）、`PathGuard` 和 `CommandGuard` 约束。
- **HTML XSS 防护**：所有批注正文、引用代码和 diff 行均通过 React JSX 默认文本转义渲染，不使用 `dangerouslySetInnerHTML`。

---

## 7. 风险、待决问题、验证与恢复

### 7.1 风险与待决问题

- **待决问题：** 无阻塞待决问题（已于 2026-11-17 随 PRD 定稿一并批准实施）。
- **主要风险与缓解：**
  1. *DOM 划词锚点在折叠/Markdown 混排时匹配不到唯一节点* → 缓解：允许按步骤号 fallback 展示在批注栏，确保批注数据与发送内容 100% 完整，不丢数据。
  2. *`Shift` 多选跨 hunk 或选到文件头部* → 缓解：限定选区锚定同一文件内连续可评论代码行索引，自动跳过夹在中间的 `hunk`/`meta` 不可评论行，仅提取有效代码行作为区间和引用片段。

### 7.2 验证与回退策略

- **验证路径（供后续实施计划拆分任务使用）：**
  1. Java 单测：`./mvnw test -Dtest=PlanExecuteAgentTest,DesktopServiceTest` 及 `./mvnw -q test` 全量回归；
  2. Desktop 单测与构建：`cd desktop && npm test && npm run build`（验证 `annotations.test.cjs` 与扩展后的 `conversation.test.cjs`）；
  3. 真实 Electron GUI 检查：按 `DESIGN.md` 进行一次批量 GUI 检查（检查标准/加宽/全屏 Diff 面板、Plan 划词高亮/红线删除及双栏切换，确保窄窗口下无布局错位）。
- **恢复与兼容：** 前端旧草稿键带版本隔离；后端 `ExecutionPlan.executionNotes` 默认为 `null`，若发现异常可在不迁库的前提下回退 `respondToPlan` 与前端组件，已有 `state.json` 历史数据完全兼容。
