# 桌面端 Plan 划线批注与 Diff 行内评论回发：验收与使用说明

2026-11-17，围绕桌面端 `PlanReview` 与 `DiffReviewPanel` 的增强及后端 `execute + notes` 适配已完成代码实现、自动化测试、前端构建、`impeccable` 规则检查及基于隔离目录与本地模拟模型的真实 Electron 端到端界面检查。本次未调用真实收费模型 Provider，也未重新打包或覆盖本机已安装的 `.app` 分发包。

需求：[REQ-PDA01～PDA04 / AC-PDA01～PDA10](../prd/desktop-plan-diff-annotations.md) · 设计：[技术方案设计](../specs/2026-11-17-desktop-plan-diff-annotations-design.md) · 计划：[实施计划](../plans/2026-11-17-desktop-plan-diff-annotations.md) · 当前进度：[PROGRESS](../PROGRESS.md)

---

## 1. 解决的问题与业务流程

### 1.1 解决的用户痛点

1. **长计划逐句/逐步骤批注成本高**：此前 `PlanReview` 只有单一全局文本框，用户必须手写描述“第几步哪句话要改/要删”；此外用户对计划细节有补充要求但不想推翻整个计划重规划时，无法在“同意并执行”时附带执行备注。
2. **代码差异无法行级反馈回发**：此前右侧变更面板只有只读 `<pre>`，无真实新旧行号、无单/多行评论、面板固定偏窄、关闭或切换会话会丢草稿，发现问题时只能手动复制路径到聊天框说明。

### 1.2 核心业务流程

1. **Plan 划词批注 / 双栏精审 / 备注执行**：
   - 用户发送 `/plan <目标>` 后，`PlanExecuteAgent` 调用 `Planner` 生成步骤，`DesktopService.reviewPlan` 发送 `plan.ready` 并把轮次置为 `plan_review`。
   - 前端 `PlanReview`（`desktop/src/plan-review.jsx`）渲染目标、摘要和步骤，同时按 `xhl:draft:plan:<sessionId>:<planId>` 从 `localStorage` 恢复未提交草稿。
   - 用户在计划正文中用鼠标划词时，界面弹出轻浮条提供「添加批注」和「红线删除」；也可点击步骤右侧「批注」快速选中该步骤。保存后，`react-markdown` 通过 HAST（HTML 抽象语法树）插件安全包裹 `<mark class="plan-mark-comment">` 或 `<del class="plan-mark-deletion">`，并同步展示在批注清单与原生 `<dialog>` 双栏精审浮层中。
   - 用户点击「更新计划」时，前端将全局意见、行内批注与红线删除整理为结构化 Markdown，以 `action="supplement"` 调用 `plan.respond` 触发重规划；点击「同意执行（含备注）」时，以 `action="execute"` 携带同一份结构化备注发送至后端，`DesktopService.respondToPlan` 校验长度（上限 `32000` 字符）并脱敏持久化 `plan.resolved`，`PlanExecuteAgent` 不重规划直接写入 `ExecutionPlan.executionNotes` 并注入每一步任务提示上下文（自动失败重规划时也会继承该备注）。
2. **Diff 真实行号解析 / 多行评论 / 回发**：
   - 用户点击完成轮次上的「查看变更」，`DesktopChanges.diff` 返回每个文件的 JGit unified diff 字符串。
   - `DiffReviewPanel`（`desktop/src/diff-review.jsx`）调用 `parseUnifiedDiff` 逐行计算 `oldLine` 与 `newLine`，按 `xhl:draft:diff:<sessionId>:<turnId>` 恢复草稿评论及宽度偏好（`标准 / 加宽 / 全屏`）。
   - 用户点击代码行或通过 `Shift + 点击` / 行号槽拖拽连选同文件连续代码行，写入行内评论；完成后可点击「汇总填入输入框」（将结构化修改意见追加写入底部聊天框并聚焦，不覆盖既有草稿）或「直接发送给 Agent」（工作目录空闲且本轮未撤销时直接调用 `turn.start` 发起修复并清除 Diff 草稿、关闭面板）。

---

## 2. 已执行的验证

运行环境：macOS arm64、OpenJDK 21.0.8、Maven Wrapper（`./mvnw`）、Node.js v23.11.0、`esbuild` 0.28.2、Electron 45.2.3。

| 检查项 | 命令或方式 | 实际结果与覆盖范围 |
|---|---|---|
| **Java 专项单测与集成测试** | `./mvnw test -Dtest=PlanExecuteAgentTest,DesktopPlanReviewTest,DesktopServiceTest` | **26 项全部通过**（0 失败）：覆盖 `execute(notes)` 不重规划、备注注入全部步骤上下文、失败自动重规划透传备注、32000 字符上限与溢出报错、`NullNode` 安全处理、脱敏落盘 |
| **Java 全量回归与打包** | `./mvnw -q test && ./mvnw -q package -DskipTests` | **736 项中 735 项运行全部通过**（0 失败、0 错误；仅既有 `HeadroomLiveTest` 因未传外部环境变量跳过 1 项），成功打包 `target/xhlcli-1.0.1.jar` |
| **桌面端 Node 单测** | `cd desktop && npm test` | **28 项全部通过**（0 失败）：`annotations.test.cjs` 6 项（多 hunk/增删/二进制/截断/无换行 Diff 行号、锚点拼装、`mixed` 以删除行结尾锚定不漂移、Plan 格式化、LRU 100 草稿与损坏/超限降级）、`conversation.test.cjs` 9 项（含 Plan HAST `<mark>`/`<del>` 渲染、跨 `<mark>` 防误桥接与 `<br>` 匹配、历史含备注标签、旧事件兼容、`DiffReviewPanel` 忙碌/撤销禁用状态）及原有 13 项桥接/事件/命令/剪贴板/运行时测试 |
| **桌面端前端构建** | `cd desktop && npm run build` | 成功打包 `desktop/dist/app.js`、`app.css`、`index.html` |
| **Impeccable UI 机械检查** | `impeccable detect --json desktop/src/plan-review.jsx ... desktop/src/app.jsx` | 首轮构建后扫描返回 `[]`（0 项规则告警；后续 CSS/JSX 交互修复未再重跑 CLI，由下文 CDP 布局边界实测覆盖） |
| **真实 Electron + Java 后端端到端 GUI 验证** | 隔离目录 + 本地模拟 SSE 模型 + Electron CDP 真实鼠标/键盘驱动 | 全流程通过：DOM 划词与跨步骤按钮拖选防“批注”文案泄漏、跨块（`<strong>`+`<li>`）HAST `<mark>` 渲染、弹窗外点击关闭选区浮条、`<dialog>` 内嵌套编辑框按 `Esc` 分层退出（首次只关内嵌表单、二次再关弹层）、切换会话后草稿恢复、`同意执行（含备注）` 仅请求 1 次规划且任务 prompt 包含批注、真实鼠标在 `.diff-gutter` 按住跨 4 行拖拽（拖动期间不插框不跳行、释放后选中整段且以删除行结尾时锚定不漂移）、`.diff-header` 样式优先级与 `1160px` 加宽态输入框不挤压、`全屏` 态「汇总填入输入框」自动收起遮罩并聚焦且重开面板保留草稿、「直接发送给 Agent」发起新一轮并清草稿收面板 |

### 2.1 未验证范围（诚实记录）

- **真实付费大模型 Provider 响应质量**：端到端测试使用的是本地 HTTP 模拟 SSE 服务，未在真实 DeepSeek / OpenAI / Anthropic 线上接口实际运行完整端到端对话以评估模型对结构化批注的遵循度。
- **macOS 安装包重新打包**：`npm run package` / `npm run distribute` 未在本轮重新执行（当前代码修改生效于开发态 `npm start` 及后续重新打包）。
- **VoiceOver 屏幕阅读器语音播报与物理触控板习惯手势**：已通过 CDP 真实指针事件验证 `.diff-gutter` 拖拽与键盘 `Esc` 层级关闭，但 VoiceOver 实机朗读及真实硬件触控板长按手势尚未人工实操确认。

---

## 3. 验收条件对应矩阵

| AC | 依据与结论 |
|---|---|
| `AC-PDA01` | 通过：`conversation.test.cjs` 验证 HAST `<mark>`/`<del>` 与步骤批注按钮；Electron E2E 实测 DOM 划词与步骤快捷批注 |
| `AC-PDA02` | 通过：Electron E2E 实测原生 `<dialog>` 双栏模态展开、批注计数同步、关闭与切换会话保持一致 |
| `AC-PDA03` | 通过：`annotations.test.cjs` 验证结构化 Markdown 输出顺序与内容；`DesktopPlanReviewTest` 验证 `supplement` 重规划 |
| `AC-PDA04` | 通过：`PlanExecuteAgentTest`、`DesktopPlanReviewTest`、Electron E2E 验证不触发重规划、任务上下文含备注、历史显示 `你已同意执行（含备注）` |
| `AC-PDA05` | 通过：`DesktopPlanReviewTest` 验证 16000 字符正常接收、32001 字符报错中文提示、前端拦截超限提交 |
| `AC-PDA06` | 通过：`annotations.test.cjs` 覆盖多 hunk、新删文件、二进制、截断、`\\ No newline` 真实双行号 |
| `AC-PDA07` | 通过：`annotations.test.cjs` 与 Electron E2E 验证 `L1` / `old L..` / snippet 汇总追加写入并聚焦输入框 |
| `AC-PDA08` | 通过：`conversation.test.cjs` 验证忙碌与已撤销时禁用「直接发送给 Agent」；Electron E2E 验证空闲时触发 `turn.start` 并收起面板 |
| `AC-PDA09` | 通过：`annotations.test.cjs` 覆盖恢复/清除/LRU 100/损坏超限降级；Electron E2E 验证跨会话切换恢复与提交后清理 |
| `AC-PDA10` | 通过：Electron E2E 实测标准/加宽/全屏与 `900x620` 窄窗口布局截图及矩形边界、`impeccable detect` 返回 `[]` |

---

## 4. 人工验收操作步骤（供您执行）

### 4.1 启动条件

1. 环境要求：macOS、Java 21+（`java -version`）、Node.js 22+。
2. 已在设置页配置任意可用且支持工具调用的模型（或按 `desktop/README.md` 启动本地验收模型）。
3. 在仓库根目录执行启动命令（确保包含本次打包的最新 JAR 与前端构建产物）：
   ```bash
   cd /Users/daiyifei/Documents/code/agent-cli/xhlcli
   ./mvnw package -DskipTests
   cd desktop
   npm run build
   npm start
   ```

### 4.2 逐项操作与预期结果

1. **Plan 划词批注与双栏精审**
   - **操作**：选择一个临时测试项目，在输入框输入 `/plan 为当前项目新增一个示例说明文件并检查格式`，发送；等待计划卡片出现。
   - **预期**：计划卡片右上角出现「展开精审」按钮，每一步右侧悬停或聚焦时出现「批注」按钮。
   - **操作**：用鼠标选中某步骤中的一段文字，在浮条点击「添加批注」，输入意见并保存；再选中摘要中一段文字点击「红线删除」直接确认标删；最后点击「展开精审」。
   - **预期**：计划正文对应文字出现浅黄高亮和红色删除线；双栏 `<dialog>` 弹层打开，左侧显示完整计划与高亮，右侧集中展示 2 条批注；按 `Esc` 或右上角 `×` 收起后，内联卡片与批注列表保持同步。
2. **带备注直接执行（不重规划）与草稿持久化**
   - **操作**：不提交，先点击左侧另一个聊天再切回当前聊天，然后点击「同意执行（含备注）」。
   - **预期**：切换回来后未提交的批注仍在；点击后按钮变为执行中状态，**不会**再次生成第二版待确认计划，而是直接执行步骤，结束后计划折叠卡片显示 `你已同意执行（含备注）`，展开仍可看到附带的备注记录。
3. **Diff 真实行号、多行评论、宽度切换与回发**
   - **操作**：在有文件改动的轮次卡片点击「查看变更」打开右侧面板；点击顶部「加宽」与「全屏」后再切回「标准」；在代码区点击某一行，再按住 `Shift` 点击下方第 3 行（或在行号数字区域按住拖拽跨行），输入修改意见并点击「添加评论」。
   - **预期**：面板宽度平滑切换且底部按钮不被遮挡；代码区每行显示真实旧/新行号，评论卡片以 `L起始-L结束` 锚定在最后选中行下方。
   - **操作**：关闭变更面板后再重新点开「查看变更」，确认评论仍在；点击「汇总填入输入框」。
   - **预期**：下方聊天输入框自动获得键盘焦点，并填入包含文件路径、行号范围、引用代码与意见正文的结构化文本；若您原先输入框已有文字，原文字保留在上方，不会丢失。
   - **操作**：再次点开「查看变更」写一条评论并点击「直接发送给 Agent」。
   - **预期**：变更面板自动收起，评论草稿清除，Agent 立即基于该修改意见发起新一轮执行。
