# 实施计划 — 桌面端 Plan 划线批注与 Diff 行内评论回发

基于已冻结的 [桌面端 Plan 与 Diff 批注 PRD](../prd/desktop-plan-diff-annotations.md)（`REQ-PDA01`～`REQ-PDA04`、`AC-PDA01`～`AC-PDA10`）与已批准的 [技术方案设计](../specs/2026-11-17-desktop-plan-diff-annotations-design.md)，按“Java 执行备注后端闭环 → 前端纯逻辑解析与格式化模块 → Plan 内联划词与双栏精审 → Diff 行号选区评论与回发 → 自动化校验与文档同步”的顺序实施。遵循开闭原则，将新能力拆入独立模块（`annotations.mjs`、`plan-review.jsx`、`diff-review.jsx`、`diff-review.css`），尽量减少对已有 `app.jsx` 与 `turn-view.jsx` 的修改。

| 计划信息 | 内容 | 关联信息 | 内容 |
|---|---|---|---|
| 需求依据 | [PRD](../prd/desktop-plan-diff-annotations.md)（`REQ-PDA01`～`REQ-PDA04`、`AC-PDA01`～`AC-PDA10`） | 方案依据 | [技术方案设计](../specs/2026-11-17-desktop-plan-diff-annotations-design.md)（`APPROVED`） |
| 修改边界 | `src/main/java/com/xhlcli/{plan,agent,desktop}/`、`desktop/src/`、`src/test/java/`、`desktop/test/` 及对应文档 | 当前事实 | [TECH_DESIGN.md](../TECH_DESIGN.md#计划确认与正文展示修复2026-11-06) |
| 执行与结果 | [PROGRESS.md](../PROGRESS.md) | 验证记录 | [验收与使用说明](../engineering/desktop-plan-diff-annotations-verification.md)（完成后生成） |

---

## 1. 环境准备与前置依赖

### 1.1 执行前提

| 前置条件 | 所需准备或确认 | 在哪里检查、满足什么条件 |
|---|---|---|
| 产品与方案批准 | PRD 状态为 `FROZEN`，方案状态为 `APPROVED` | 已在 2026-11-17 确认并更新元数据 |
| Java 与 Node 构建环境 | Java 21+、Maven Wrapper（`./mvnw`）、Node.js 与 `desktop/node_modules` | `java -version` 输出 21+，`cd desktop && npm test` 基线 19 项通过 |
| 保留无关工作区修改 | 工作区存在用户既有未提交改动（`.gitignore`、`AGENTS.md`、`docs/RESEARCH.md`、`GLOSSARY.md`、Phase 20 草稿及 `docs/PROJECT.md` 中 Phase 20 条目） | 只暂存和提交本任务相关文件与差异块，不回滚或混入无关修改 |

- **配置与凭据**：不新增环境变量、API Key 或外部服务端口；零新增 `desktop/package.json` 或 `pom.xml` 依赖。
- **共同执行约束**：保持既有工具硬策略（`PathGuard`、`CommandGuard`）与 `plan_review` 独立人工确认边界；前端所有批注与 Diff 正文一律使用 React 文本转义，禁止 `dangerouslySetInnerHTML`；不改动 CLI 纯终端 `/plan` 交互及 `desktop/package.json` 版本号。
- **提交与交付方式**：遵循 `AGENTS.md` 约定，验证通过后只提交本任务相关修改并推送 `origin/main`，不打 Tag 或创建 Release。

### 1.2 本地命令与验证基准

| 用途 | 命令或操作 | 前提及适用范围 | 预期结果 |
|---|---|---|---|
| 桌面端单测 | `cd desktop && npm test` | 已安装 `desktop/node_modules` | 所有 `desktop/test/*.test.cjs` 通过，0 失败 |
| 桌面端前端构建 | `cd desktop && npm run build` | `esbuild` 0.28.2 可用 | 成功生成 `desktop/dist/app.js`、`app.css`、`index.html` |
| Java 专项单测 | `./mvnw test -Dtest=PlanExecuteAgentTest,DesktopPlanReviewTest,DesktopServiceTest` | OpenJDK 21 | 全部用例通过，覆盖 `execute(notes)` 与 32000 字符上限 |
| Java 全量回归与打包 | `./mvnw test && ./mvnw package -DskipTests` | 提交前 | 全部 Java 单测通过，生成可执行 JAR |
| UI 机械规则扫描 | `impeccable detect --json ...` | 前端 UI 修改完成后 | 核查无阻断可访问性或样式规则违例 |

---

## 2. 需要修改的文件

| 操作 | 文件或目录路径 | 变更职责 | 关联任务 |
|---|---|---|---|
| 修改 | `src/main/java/com/xhlcli/plan/ExecutionPlan.java` | 新增 `executionNotes` 字段与读写方法，记录执行附带备注 | `TASK-PDA01` |
| 修改 | `src/main/java/com/xhlcli/agent/PlanExecuteAgent.java` | 新增 `PlanReviewDecision.execute(notes)`、重规划透传备注及 `buildTaskContext` 注入备注 | `TASK-PDA01` |
| 修改 | `src/main/java/com/xhlcli/desktop/DesktopService.java` | 放宽 `MAX_PLAN_FEEDBACK_CHARS` 至 `32000`，支持 `execute` 携带可选 `feedback`，修复 `NullNode` 读取边界 | `TASK-PDA01` |
| 修改 | `src/test/java/com/xhlcli/agent/PlanExecuteAgentTest.java`、`src/test/java/com/xhlcli/desktop/DesktopPlanReviewTest.java`、`src/test/java/com/xhlcli/desktop/DesktopServiceTest.java` | 验证 `execute + notes` 不重规划且透传任务上下文、重规划保留备注、32000 上限校验与历史事件兼容 | `TASK-PDA01` |
| 新增 | `desktop/src/annotations.mjs` | Unified Diff 解析（真实双行号）、Plan/Diff 选区锚点与结构化 Markdown 拼接、草稿追加与安全 `localStorage` LRU 持久化 | `TASK-PDA02` |
| 新增 | `desktop/test/annotations.test.cjs` | 覆盖 `parseUnifiedDiff` 多 hunk/新删文件/二进制/截断/无换行提示、结构化 Markdown 格式化、草稿持久化与降级 | `TASK-PDA02` |
| 新增 | `desktop/src/plan-review.jsx` | 独立 Plan 批注与精审视图模块：划词浮条、步骤快捷批注、HAST 安全高亮/红线、原生 `<dialog>` 双栏精审及草稿同步 | `TASK-PDA03` |
| 修改 | `desktop/src/plan-review.css`、`desktop/src/turn-view.jsx` | 补充批注/红线/双栏精审样式；`turn-view.jsx` 仅向 `PlanReview` 透传 `sessionId` 与完整 `plan.resolved` 决议记录 | `TASK-PDA03` |
| 新增 | `desktop/src/diff-review.jsx`、`desktop/src/diff-review.css` | 右侧代码变更审阅面板：双行号槽、单行/`Shift`/行号槽拖拽连选、行内评论列表与编辑、标准/加宽/全屏切换、草稿缓存、汇总填入与直接发送 | `TASK-PDA04` |
| 修改 | `desktop/src/app.jsx` | 以最小改动将 `.diff-panel` 委托给 `DiffReviewPanel`，注入草稿追加聚焦与直接发送、全局 `Esc` 对原生 `<dialog>` 让位 | `TASK-PDA04` |
| 修改 | `desktop/test/conversation.test.cjs` | 新增 Plan 批注渲染、含备注执行文案、历史状态标签及 `DiffReviewPanel` SSR 渲染与向后兼容测试 | `TASK-PDA03`、`TASK-PDA04` |
| 新增 / 更新 | `docs/engineering/desktop-plan-diff-annotations-verification.md`、`docs/TECH_DESIGN.md`、`docs/PROGRESS.md`、`docs/PROJECT.md` | 沉淀验证事实、人工验收步骤及最新架构与进度索引 | `TASK-PDA05` |

---

## 3. 任务步骤与依赖

### 3.1 任务依赖与可开始的任务（Ready Frontier）

| 任务 | 可验证交付 | 关联 REQ/AC | 前置任务或条件 |
|---|---|---|---|
| `TASK-PDA01` | Java 后端支持 `execute + notes` 与 `32000` 字符反馈上限并过测 | `REQ-PDA02` / `AC-PDA04`, `AC-PDA05` | PRD 冻结、方案批准（已满足，可立即开始） |
| `TASK-PDA02` | `annotations.mjs` 纯逻辑与 `annotations.test.cjs` 全量单测通过 | `REQ-PDA01`～`04` / `AC-PDA03`, `AC-PDA06`, `AC-PDA07`, `AC-PDA09` | 无后端阻塞，可立即开始 |
| `TASK-PDA03` | Plan 划词/步骤快捷批注、HAST 高亮红线、原生 `<dialog>` 双栏精审与备注执行 UI | `REQ-PDA01`, `REQ-PDA02` / `AC-PDA01`～`04`, `AC-PDA09` | `TASK-PDA01`, `TASK-PDA02` |
| `TASK-PDA04` | `DiffReviewPanel` 真实行号、单/多行评论、三档宽度、草稿持久化与双回发入口 | `REQ-PDA03`, `REQ-PDA04` / `AC-PDA06`～`10` | `TASK-PDA02` |
| `TASK-PDA05` | 全量 Java/Desktop 测试、构建、`impeccable` 规则检查、文档同步与远端推送 | 全部 `AC-PDA01`～`10` | `TASK-PDA01`～`04` |

### 3.2 任务明细

#### TASK-PDA01：Java 后端 `execute + notes` 与结构化反馈上限适配
- **交付结果**：`DesktopService.respondToPlan` 与 `PlanExecuteAgent` 支持在 `execute` 决策中接收可选备注（最多 `32000` 字符）并注入任务执行上下文而不触发重规划。
- **涉及文件**：`ExecutionPlan.java`、`PlanExecuteAgent.java`、`DesktopService.java`、`PlanExecuteAgentTest.java`、`DesktopPlanReviewTest.java`、`DesktopServiceTest.java`。
- **实施步骤**：
  1. 在 `ExecutionPlan` 新增 `executionNotes` 字段与规范化存取方法。
  2. 在 `PlanExecuteAgent` 新增 `PlanReviewDecision.execute(String notes)` 工厂方法；在 `reviewAndExecutePlan` 的 `EXECUTE` 分支写入备注；在 `executePlan` 自动 `replan` 前将已有 `executionNotes` 透传给新计划；在 `buildTaskContext` 末尾拼接“用户批准执行附带备注（请在执行中一并落实）”。
  3. 在 `DesktopService.respondToPlan` 使用 `MAX_PLAN_FEEDBACK_CHARS = 32_000` 并安全处理 `NullNode`；支持 `execute` 携带 `0..32000` 字符备注并随 `plan.resolved` 持久化。
  4. 补充失败用例并验证通过。
- **验证与预期**：运行 `./mvnw test -Dtest=PlanExecuteAgentTest,DesktopPlanReviewTest,DesktopServiceTest`，确认不触发额外规划请求且每个任务 prompt 均包含备注，超 32000 字符抛出中文参数错误。

#### TASK-PDA02：前端纯逻辑模块 `desktop/src/annotations.mjs` 与单测
- **交付结果**：独立的 ES 模块实现 Diff 解析、选区锚定、Markdown 汇总拼装、草稿追加与安全 `localStorage` LRU 读写，全部逻辑由 `desktop/test/annotations.test.cjs` 覆盖。
- **验证与预期**：`node --test test/annotations.test.cjs` 覆盖多 hunk、新/删文件、二进制提示、截断尾部提示、`\ No newline at end of file`、损坏 JSON 恢复与超限降级。

#### TASK-PDA03：Plan 内联划词批注与原生 `<dialog>` 双栏精审
- **交付结果**：`plan-review.jsx` + `plan-review.css` 提供内联划词（`COMMENT`/`DELETION`）、步骤快捷批注、HAST 安全标记渲染、批注清单管理、原生 `<dialog showModal()>` 双栏精审、`execute + notes` 按钮语义切换与历史状态区分。
- **验证与预期**：`conversation.test.cjs` 验证 `PlanReview` 渲染高亮/红线/历史含备注标签，且保持旧版无 `feedback` 事件向后兼容。

#### TASK-PDA04：Diff 审阅面板重构接入与宽度/草稿闭环
- **交付结果**：`diff-review.jsx` + `diff-review.css` + `app.jsx` 最小接线实现真实双行号展示、单行/`Shift`/行号槽拖拽多选评论、标准/加宽/全屏切换、会话轮次草稿恢复与“汇总填入输入框 / 直接发送给 Agent”。
- **验证与预期**：`conversation.test.cjs` 验证 `DiffReviewPanel` 在正常、空、已撤销及目录忙碌状态下的渲染与按钮禁用规则；`npm run build` 无警告打包成功。

#### TASK-PDA05：整体验证、文档同步与远程推送
- **交付结果**：运行全量 `./mvnw test`、`./mvnw package -DskipTests`、`cd desktop && npm test && npm run build`、`impeccable detect`，写入 `docs/engineering/desktop-plan-diff-annotations-verification.md` 并同步 `docs/TECH_DESIGN.md`、`docs/PROGRESS.md`、`docs/PROJECT.md`，仅提交本任务文件并推送 `origin/main`。

---

## 4. 需求、任务与验证的对应关系

| PRD 验收编号 | 验证场景与观察结果 | 检查类型 | 测试文件、用例或操作入口 | 关联任务 | 实际结果入口 |
|---|---|---|---|---|---|
| `AC-PDA01` | Plan 划词/步骤快捷批注创建、高亮与红线渲染、清单增删 | 单元 / 组件 / 人工交互 | `conversation.test.cjs`、`plan-review.jsx` | `TASK-PDA03` | [验收记录](../engineering/desktop-plan-diff-annotations-verification.md) |
| `AC-PDA02` | 双栏精审 `<dialog>` 展开/关闭、`Esc` 退出与双向草稿同步 | 组件 / 人工交互 | `plan-review.jsx` | `TASK-PDA03` | [验收记录](../engineering/desktop-plan-diff-annotations-verification.md) |
| `AC-PDA03` | 混合批注触发 `supplement` 生成结构化要求并进入新一轮审阅 | 单元 / 集成 | `annotations.test.cjs`、`DesktopPlanReviewTest` | `TASK-PDA01`, `02`, `03` | [验收记录](../engineering/desktop-plan-diff-annotations-verification.md) |
| `AC-PDA04` | `execute + notes` 不调用重规划、注任务上下文并记录历史标签 | 单元 / 集成 | `PlanExecuteAgentTest`、`DesktopPlanReviewTest`、`conversation.test.cjs` | `TASK-PDA01`, `TASK-PDA03` | [验收记录](../engineering/desktop-plan-diff-annotations-verification.md) |
| `AC-PDA05` | 8001~32000 字符正常接收，超过 32000 字符两端均返回中文错误提示 | 单元 / 集成 | `DesktopPlanReviewTest`、`DesktopServiceTest`、`plan-review.jsx` | `TASK-PDA01`, `TASK-PDA03` | [验收记录](../engineering/desktop-plan-diff-annotations-verification.md) |
| `AC-PDA06` | 多 hunk/增删/二进制/截断/无换行 Diff 真实行号 100% 准确 | 单元 | `annotations.test.cjs` | `TASK-PDA02` | [验收记录](../engineering/desktop-plan-diff-annotations-verification.md) |
| `AC-PDA07` | 单/多行及删除行评论汇总填入聚焦输入框且不覆盖已有草稿 | 单元 / 组件 | `annotations.test.cjs`、`diff-review.jsx` | `TASK-PDA02`, `TASK-PDA04` | [验收记录](../engineering/desktop-plan-diff-annotations-verification.md) |
| `AC-PDA08` | 直接发送调用 `turn.start` 并清草稿关面板；目录忙碌/已撤销禁用发送 | 组件 / 集成 | `conversation.test.cjs`、`diff-review.jsx`、`app.jsx` | `TASK-PDA04` | [验收记录](../engineering/desktop-plan-diff-annotations-verification.md) |
| `AC-PDA09` | Plan 与 Diff 草稿切会话/关面板后恢复，提交或直接发送后清除；异常静默降级 | 单元 | `annotations.test.cjs` | `TASK-PDA02`～`04` | [验收记录](../engineering/desktop-plan-diff-annotations-verification.md) |
| `AC-PDA10` | 标准/加宽/全屏与窄窗口布局无溢出遮挡、机械扫描通过 | 静态 / 构建 / 人工视觉 | `diff-review.css`、`impeccable detect`、桌面界面检查 | `TASK-PDA04`, `TASK-PDA05` | [验收记录](../engineering/desktop-plan-diff-annotations-verification.md) |

---

## 5. 交付、发布与恢复预案

- **交付目标**：当前分支 `main` 同步至 `origin/main`；不打 Tag、不发版、不自动覆盖本机已打包 `.app`（需打包可由用户按 `desktop/package.json` 的 `npm run package` 触发）。
- **停止条件**：任意 Java/Node 自动化测试或前端 `esbuild` 构建失败时停止推送，先排查修复或如实记录限制。
- **恢复步骤**：Java 端 `ExecutionPlan.executionNotes` 默认为 `null` 且无库表变更，前端草稿键使用 `xhl:draft:*` 独立前缀；如出现异常，可安全回退本次提交而不影响旧版 `state.json` 读取与已有对话历史。

---

## 6. 预期里程碑与计划变化记录

- **M1（后端打通）**：`TASK-PDA01` 通过 Java 专项测试。
- **M2（前端闭环）**：`TASK-PDA02`～`TASK-PDA04` 完成并通过 Node 单测与 `esbuild` 打包。
- **M3（收口交付）**：`TASK-PDA05` 完成全量回归、文档同步与远程推送。
- 实际进展与偏差统一记入 [PROGRESS.md](../PROGRESS.md) 与 [验收记录](../engineering/desktop-plan-diff-annotations-verification.md)。
