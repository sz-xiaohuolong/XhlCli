# Phase 16：隔离快照与版本恢复实施路线与计划 (Implementation Plan)

> **文档性质：** 阶段实施任务清单与执行蓝图  
> **计划日期：** 2026-10-07  
> **目标版本：** `v0.14.0`  
> **对应 PRD：** [`docs/prd/phase-16-lsp-and-snapshots.md`](../prd/phase-16-lsp-and-snapshots.md) 子能力 B  
> **对应 Spec：** [`docs/specs/2026-10-07-phase-16-snapshot-and-recovery-design.md`](../specs/2026-10-07-phase-16-snapshot-and-recovery-design.md)  
> **执行准则：** 必须等待用户明确确认批准（Human Approval Gate）后方可开始编码！

---

## 1. 阶段目标与交付范围

建立一套完全独立于用户 `.git` 的 **Side-History 隔离快照与版本恢复子系统**：
1. **JGit 纯 Java 依赖与核心领域模型 (`com.xhlcli.snapshot`)**：引入 JGit 7.6.0，建立 `SnapshotPhase`、`TurnSnapshot`、`RestoreResult`、`SnapshotConfig`；
2. **Side-Git 隔离存储与树操作引擎 (`SideGitManager`)**：实现基于 SHA-256 的独立快照仓库路径计算，配置 JGit 隔离仓库与排除项（`.git`, `target`, `node_modules` 等），执行 `preTurnSnapshot` 与 `postTurnSnapshot`，绝对不污染用户已有 `.git`；
3. **安全恢复引擎与防御屏障 (`SideGitManager` 恢复机制)**：实现 `restorePreTurn(offset)` 定位目标快照，恢复前强制创建 `pre-restore` 保护快照，检测外部未归属变动防误覆盖，安全清理孤儿文件并写回工作区；
4. **快照服务与异步写入调度 (`SnapshotService`)**：实现单线程守护线程池串行异步写入 `post-turn` 快照，`pre-turn` 同步执行保障一致性，快照失败容错降级不阻塞 Agent 主流程，支持快照列表、状态展示与清理；
5. **终端运维指令与 Agent 自愈高危工具 (`com.xhlcli.cli` + `com.xhlcli.tool.local`)**：扩展 `ChatCommand`、`ChatCommandParser`（`/snapshot [list|status|clean]`、`/restore <N>`），在 `TerminalCompleter` 增加补全，实现 `revert_turn` 工具并纳入 `ApprovalPolicy` 高危审批与 `AuditLog` 审计；
6. **全链路集成包装、Golden Test 验收、物证与发布**：在 `ChatBootstrap` 与 `ChatLoop` 中无缝包装 ReAct / Plan / Team 各模式 turn 生命周期，编写 `SnapshotGoldenTest` 端到端验收，运行 480+ 测试保持 100% 绿灯，产出评测报告，更新 Living Docs 并发布 Tag `v0.14.0`。

---

## 2. 详细任务分解 (Task Breakdown)

### Task 1: JGit 依赖与快照核心模型 (`com.xhlcli.snapshot`)
- [ ] **1.1** 在 `pom.xml` 中引入 Eclipse JGit 依赖 (`org.eclipse.jgit:org.eclipse.jgit:7.6.0.202603022253-r`)；
- [ ] **1.2** 创建枚举 `com.xhlcli.snapshot.SnapshotPhase`：
  - `PRE_TURN("pre-turn")`；
  - `POST_TURN("post-turn")`；
  - `PRE_RESTORE("pre-restore")`；
- [ ] **1.3** 创建领域模型 `com.xhlcli.snapshot.TurnSnapshot` (commitId, phase, turnId, createdAt, summary) 及 `shortCommitId()` 截断方法；
- [ ] **1.4** 创建结果模型 `com.xhlcli.snapshot.RestoreResult` (success, commitId, message, restoredFiles, removedFiles) 及 `formatForCli()` 格式化方法；
- [ ] **1.5** 创建配置模型 `com.xhlcli.snapshot.SnapshotConfig` (enabled, snapshotsRoot, maxSnapshots, excludes)：
  - 支持 `XHLCLI_SNAPSHOT_ENABLED` / `-Dxhlcli.snapshot.enabled` (默认 true)；
  - 支持 `XHLCLI_SNAPSHOT_DIR` / `-Dxhlcli.snapshot.dir` (默认 `~/.xhlcli/snapshots`)；
  - 支持 `XHLCLI_SNAPSHOT_MAX` / `-Dxhlcli.snapshot.max` (默认 50)；
  - 支持 `XHLCLI_SNAPSHOT_EXCLUDES` / `-Dxhlcli.snapshot.excludes` 合并默认排除规则；
- [ ] **1.6** 编写单元测试 `SnapshotConfigTest` 与 `TurnSnapshotTest`。

### Task 2: Side-Git 隔离存储与树操作引擎 (`SideGitManager`)
- [ ] **2.1** 创建 `com.xhlcli.snapshot.SideGitManager`：
  - 计算基于规范化绝对路径的 `projectHash` 与父级工作树 `worktreeHash`（SHA-256 前 8 位）；
  - 定位隔离 git 目录：`~/.xhlcli/snapshots/<projectHash>/<worktreeHash>/.git`；
- [ ] **2.2** 实现独立 JGit 仓库初始化与排除文件写入：
  - 若 `gitDir` 不存在则执行 `Git.init().setGitDir(...).setDirectory(...)`；
  - 将 excludes 写入 `gitDir/info/exclude`，阻断 `.git`, `target`, `node_modules` 等大文件与依赖目录；
  - 构建 JGit `Repository`（`setGitDir(gitDir).setWorkTree(projectRoot)`）；
- [ ] **2.3** 实现快照创建逻辑：
  - `preTurnSnapshot(turnId, summary)` 与 `postTurnSnapshot(turnId, summary)`；
  - 配置提交者身份 `PersonIdent("XhlCLI Snapshot", "snapshot@xhlcli.local")`；
  - 暂存工作区（`git.add().addFilepattern(".")`），处理删除项，生成 RevCommit；
  - 解析 Commit 转换为 `TurnSnapshot`；
- [ ] **2.4** 编写单元测试 `SideGitManagerTest`：
  - 验证新增、修改、删除文件时的快照 commit 生成；
  - 验证默认排除项（`target/`, `.git/` 等）绝对不进入快照；
  - 核心断言：验证用户项目真实的 `.git` 目录下的 HEAD、refs 与 index 文件在快照执行前后毫秒级未被任何读写污染。

### Task 3: 恢复引擎与安全防线 (`SideGitManager` 恢复机制)
- [ ] **3.1** 实现快照列表与目标定位：
  - `listSnapshots(limit)`：逆序获取最近 Commit 并转换为 `TurnSnapshot` 列表；
  - `findPreTurnCommit(offset)`：在历史中按 offset (1-based) 精确定位第 N 个 `pre-turn` 快照；
- [ ] **3.2** 实现安全恢复 `restorePreTurn(offset)`：
  - **Pre-Restore 保护快照**：恢复操作前立即同步记录 `pre-restore` 快照，确保具备撤销后悔药；
  - **目标树遍历与还原**：读取目标 Commit 的 Tree，将历史文件写回当前工作区（`Files.write`）；
  - **孤儿新增文件清理**：检查当前工作区已跟踪文件，若其在目标 Tree 中不存在且非排除项，安全删除；
  - **递归空目录清理**：修剪因孤儿文件删除而产生的空目录；
- [ ] **3.3** 编写单元测试 `SideGitRestoreTest`：
  - 验证多轮修改后执行 `restorePreTurn(1)` 可完全恢复至修改前状态；
  - 验证恢复后在历史中必定存在对应的 `pre-restore` 保护快照；
  - 验证用户项目原有 `.git` 状态在恢复过程中同样零变化。

### Task 4: 快照服务与异步写入调度 (`SnapshotService`)
- [ ] **4.1** 创建 `com.xhlcli.snapshot.SnapshotService` 实现 `AutoCloseable`：
  - 持有 `SideGitManager`；
  - 创建单线程守护线程池 `xhlcli-snapshot-writer` 用于异步写入，确保同一项目多快照按序串行化执行；
- [ ] **4.2** 实现生命周期包装方法：
  - `<T> T runTurn(String mode, String input, ThrowingSupplier<T> supplier)`；
  - `snapshotBeforeTurn(turnId, summary)`（同步执行，保障在 Agent 改文件前树结构已固化）；
  - `snapshotAfterTurnAsync(turnId, summary)`（异步提交线程池，保障交互即时返回）；
  - 异常静默隔离：快照失败仅 stderr 打印告警，绝不抛出异常阻断 Agent 业务主循环；
- [ ] **4.3** 实现管理与查询方法：
  - `listSnapshots(int limit)`（等待异步队列 idle 后返回）；
  - `restorePreTurn(int offset)`（等待异步队列 idle 后执行恢复）；
  - `status()` 与 `clean()` 委托；
  - `close()` 优雅关闭线程池；
- [ ] **4.4** 编写单元测试 `SnapshotServiceTest`。

### Task 5: 终端指令、智能补全与危险自愈工具 (`com.xhlcli.cli` + `com.xhlcli.tool.local`)
- [ ] **5.1** 扩展 `ChatCommand`：
  - 新增 `SNAPSHOT`、`RESTORE`；
- [ ] **5.2** 在 `ChatCommandParser` 注册解析：
  - `/snapshot` -> `SNAPSHOT` ("list")；
  - `/snapshot status` -> `SNAPSHOT` ("status")；
  - `/snapshot clean` -> `SNAPSHOT` ("clean")；
  - `/restore [N]` -> `RESTORE` (offset，默认 1)；
- [ ] **5.3** 在 `TerminalCompleter` 增加补全候选：
  - 一级命令 `/snapshot`、`/restore`；
  - 二级子命令 `/snapshot list`、`/snapshot status`、`/snapshot clean`；
- [ ] **5.4** 创建自愈本地工具 `com.xhlcli.tool.local.RevertTurnTool`：
  - 工具名：`revert_turn`；
  - 参数：`offset` (integer, 默认 1)；
  - 描述：恢复到 Side-Git 记录的最近第 N 个 pre-turn 快照，属于高危回滚操作；
  - 注册至 `ToolRegistry`；
- [ ] **5.5** 在 `ApprovalPolicy` 标记 `revert_turn` 为 `HIGH_RISK`：
  - 交互终端强制弹窗触发 HITL 确认；
  - 执行结果由 `DefaultToolExecutor` 自动记入 `AuditLog`；
- [ ] **5.6** 编写单元测试 `SnapshotCliTest`、`RevertTurnToolTest`、`ApprovalPolicySnapshotTest`。

### Task 6: 全链路生命周期包装、Golden Test 验收、物证与发布
- [ ] **6.1** 重构 `ChatBootstrap` 与 `ChatLoop`：
  - 实例化 `SnapshotService` 并注入 `ChatLoop` 与 `ToolRegistry`；
  - 对用户输入、`/plan`、`/team` 任务入口实施统一 `runTurn` 环绕切面；
  - 在 `ChatLoop` 处理 `SNAPSHOT` 与 `RESTORE` 指令，通过 `TerminalRenderer.printMessage` 规范呈现；
- [ ] **6.2** 编写端到端验收套件 `SnapshotGoldenTest`：
  - 模拟真实 Agent 工作流：“创建文件 -> 修改代码 -> 引入错误 -> 触发恢复 -> 100% 复原”；
  - 验证用户项目真实 `.git` 仓库分支、HEAD、状态绝对无损；
- [ ] **6.3** 执行全量回归测试：`mvn clean verify` 确保 480+ 测试 100% 绿灯；
- [ ] **6.4** 产出评测报告：`docs/engineering/snapshot-and-recovery-evaluation.md`；
- [ ] **6.5** 更新 Living Docs (`PROJECT.md`, `ROADMAP.md`, `TECH_DESIGN.md`, `CHANGELOG.md`, `AGENTS.md`, `README.md`, `specs/README.md`, `plans/README.md`)；
- [ ] **6.6** 升级 `pom.xml` 为 `0.14.0-SNAPSHOT`，执行 Git Commit、打 Tag `v0.14.0` 并 Push。

---

## 3. 验收标准与交付清单 (Definition of Done)

- [ ] **JGit 依赖正常编译**：Maven 依赖下载无冲突，Fat JAR 打包包含 JGit 类；
- [ ] **快照绝对物理隔离**：项目根下原有 `.git` 毫秒级时间戳与 SHA 无修改，快照全部保存在 `~/.xhlcli/snapshots/`；
- [ ] **自动 Pre/Post 快照正常落地**：对话每轮前后生成 `pre-turn` 与 `post-turn`，ReAct / Plan / Team 均覆盖；
- [ ] **双入口安全恢复**：用户 `/restore 1` 与 Agent `revert_turn` 均能精准写回并清理孤儿文件，恢复前生成 `pre-restore` 保护快照；
- [ ] **HITL 与审计全覆盖**：`revert_turn` 必须通过人工审批，拦截非法调用，审计日志正常写入；
- [ ] **Fail-Safe 容错**：快照异常不崩溃，终端降级提示；
- [ ] **全量测试通过**：480+ 测试（包含新增 30+ 项快照与恢复专项测试）100% 绿灯；
- [ ] **Git 发布**：Tag `v0.14.0` 推送至 GitHub。
