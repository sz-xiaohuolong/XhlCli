# Phase 16 技术设计规约：隔离快照与版本恢复 (Snapshot & Recovery)

> 文档状态：设计中 (Spec)  
> 文档日期：2026-10-07  
> 责任模块：`com.xhlcli.snapshot`, `com.xhlcli.tool.local`, `com.xhlcli.cli`  
> 依据需求：[`docs/prd/phase-16-lsp-and-snapshots.md`](../prd/phase-16-lsp-and-snapshots.md) 子能力 B

---

## 1. 设计背景与核心目标

在编码型 Agent 进行复杂代码重构、跨文件编辑或多轮次尝试时，即使具备 AST 检索与模型反思能力，依然不可避免会出现逻辑改崩、语法破损或产生副作用的代码修改。传统的 Git 机制通常依赖用户手动创建提交（commit）或分支（stash/branch），容易导致用户工作区分支被污染，或在用户未提交状态下被覆盖。

Phase 16 旨在建立一套完全独立于用户 `.git` 的 **Side-History 隔离快照与版本恢复子系统**：
1. **绝对隔离**：快照物理保存在宿主用户主目录下（`~/.xhlcli/snapshots/<project_hash>/<worktree_hash>/.git`），严禁修改用户项目的 `.git`、HEAD、分支或暂存区。
2. **纯 Java 嵌入式**：采用 Eclipse JGit 纯 Java 库，零系统 Git 外部进程依赖，跨平台稳定无环境差异。
3. **Turn/Run 级双向快照**：用户/Agent 任务开始前同步记录 `pre-turn` 快照，结束后异步记录 `post-turn` 快照。
4. **安全后悔药机制**：执行恢复前自动生成 `pre-restore` 保护快照；若目标文件在快照后存在非归属当前 Run 的外部变动，明确阻断与告警。
5. **人机与自愈双入口**：用户可通过 `/restore <N>` 手动回滚；Agent 亦可通过高危工具 `revert_turn` 自主撤销改崩的代码，全程纳管于 HITL 人工审批与 `AuditLog` 审计。

---

## 2. 系统架构与交互时序

### 2.1 架构分层

```text
┌────────────────────────────────────────────────────────────────────────┐
│                        User & Terminal Interaction                     │
│               /snapshot [list|status|clean]  /restore <N>              │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ Command Routing
┌───────────────────────────────────▼────────────────────────────────────┐
│                              ChatLoop                                  │
│             ReAct Run / Plan DAG Run / Team Orchestration              │
└───────────────────┬────────────────────────────────┬───────────────────┘
                    │ runTurn (pre/post snapshot)    │ Tool Execution
┌───────────────────▼──────────────────┐   ┌─────────▼───────────────────┐
│           SnapshotService            │   │      DefaultToolExecutor    │
│ • preTurnSnapshot (Sync)             │   │ • ApprovalPolicy (HITL)     │
│ • postTurnSnapshotAsync (Single-Pool)│   │ • revert_turn (High Risk)   │
│ • restorePreTurn (pre-restore guard) │   │ • AuditLog (Structured)    │
└───────────────────┬──────────────────┘   └─────────────────────────────┘
                    │ JGit API
┌───────────────────▼────────────────────────────────────────────────────┐
│                           SideGitManager                               │
│ • Path Hashing (~/.xhlcli/snapshots/<proj_hash>/<worktree_hash>/.git)  │
│ • JGit Init & Exclusion (.git, target, node_modules, .idea, etc.)      │
│ • Commit Tree Traversal & Atomic Restorer                              │
└───────────────────┬────────────────────────────────────────────────────┘
                    │ Pure Java FS IO
┌───────────────────▼──────────────────┐   ┌─────────────────────────────┐
│  Workspace (User Project Files)      │   │ User Project .git           │
│  (Read & File Restore Only)          │   │ (ABSOLUTELY UNTOUCHED)      │
└──────────────────────────────────────┘   └─────────────────────────────┘
```

---

## 3. 核心领域模型与接口契约

### 3.1 领域模型 (`com.xhlcli.snapshot`)

#### `SnapshotPhase`
```java
public enum SnapshotPhase {
    PRE_TURN("pre-turn"),
    POST_TURN("post-turn"),
    PRE_RESTORE("pre-restore");

    private final String label;
    SnapshotPhase(String label) { this.label = label; }
    public String label() { return label; }
}
```

#### `TurnSnapshot`
```java
public record TurnSnapshot(
        String commitId,
        SnapshotPhase phase,
        String turnId,
        Instant createdAt,
        String summary
) {
    public String shortCommitId() {
        return commitId == null || commitId.length() <= 10 ? commitId : commitId.substring(0, 10);
    }
}
```

#### `RestoreResult`
```java
public record RestoreResult(
        boolean success,
        String commitId,
        String message,
        List<String> restoredFiles,
        List<String> removedFiles
) {
    public static RestoreResult success(String commitId, List<String> restored, List<String> removed);
    public static RestoreResult failure(String message);
    public String formatForCli();
}
```

#### `SnapshotConfig`
```java
public record SnapshotConfig(
        boolean enabled,
        Path snapshotsRoot,
        int maxSnapshots,
        List<String> excludes
) {
    public static SnapshotConfig fromEnvironment();
}
```
默认排除路径集合：`.git`, `.xhlcli/snapshots`, `target`, `node_modules`, `dist`, `.idea`, `*.class`, `*.jar`。

---

## 4. 关键机制实现原理

### 4.1 独立 Side-Git 路径哈希算法
每个工作区对应唯一的隔离 Git 目录：
- `project_hash`：基于规范化绝对路径 `projectRoot.toAbsolutePath().normalize()` 计算 SHA-256 前 8 位十六进制字符。
- `worktree_hash`：基于父路径或独立工作树标识计算 SHA-256 前 8 位。
- 最终路径：`~/.xhlcli/snapshots/<project_hash>/<worktree_hash>/.git`。
- JGit 配置：`gitDir` 设为此目录，`workTree` 设为 `projectRoot`。

### 4.2 双向快照生命周期
1. **Pre-Turn Snapshot（同步）**：
   - 触发时机：用户输入提交后、Agent 决策/工具执行前；
   - 必须**同步完成**，防止 Agent 写入文件与快照扫描发生写并发；
   - 记录 Commit 消息：`pre-turn <turnId>\n<summary>`；
   - 若失败：仅向 stderr/日志发出告警，**不中断**用户对话。
2. **Post-Turn Snapshot（异步）**：
   - 触发时机：Agent 完成回复或 Plan/Team 批次完成；
   - 提交至单线程守护线程池 `xhlcli-snapshot-writer` 异步排队执行；
   - 记录 Commit 消息：`post-turn <turnId>\n<summary>`；
   - 确保快速返回，不拖慢终端交互响应时间。

### 4.3 安全恢复与三层防护
当执行 `/restore <N>` 或 Agent 调用 `revert_turn(offset=N)` 时：
1. **第一层：Pre-Restore 保护快照**
   - 恢复动作执行前，强制创建 `pre-restore` 快照；
   - 如果用户误操作或恢复后后悔，可通过快照历史再次撤销恢复。
2. **第二层：工作区冲突检测**
   - 检查自该快照之后修改的文件，若存在外部未知变动（未归属本会话且未在基线快照中记录），发出告警；
3. **第三层：原子写回与孤儿文件清理**
   - 从 JGit ObjectDatabase 读取目标 Commit 的 Tree；
   - 比较当前工作区跟踪文件：写回恢复目标版本；
   - 将目标快照中不存在、但在当前工作区中由后续 Turn 新增的文件安全物理删除；
   - 清理空的父目录，保证工作区完全一致。

### 4.4 危险工具与 HITL 审批
- 本地工具：`revert_turn`；
  - 参数：`offset` (integer, 必填, 默认 1)；
  - 描述：恢复到 Side-Git 记录的最近第 N 个 pre-turn 快照；
- 安全策略：
  - 在 `ApprovalPolicy` 注册为 `HIGH_RISK`；
  - 触发 HITL 交互确认，告知用户即将回滚的文件数量与风险；
  - 执行结果全量落入 `AuditLog`。

---

## 5. 验收标准与测试矩阵

1. **JGit 隔离性断言**：运行全套快照、写回与清理，用户项目 `.git` 内部的 `HEAD`、`refs/heads`、`index` 文件内容与修改时间毫秒级一致。
2. **Pre/Post 快照时机**：验证用户正常会话、`/plan` 与 `/team` 均能生成规范的 pre/post 快照对。
3. **精准回滚验证**：新建文件、修改文件、删除文件混合操作后，`/restore 1` 可 100% 还原文件状态（内容一致、新增文件被清理）。
4. **Pre-Restore 保护验证**：执行 restore 后，快照列表中必定包含一条最新的 `pre-restore` 快照。
5. **Fail-Safe 容错**：当快照根目录不可写或 JGit 内部异常时，Agent 会话正常完成，降级告警输出。
6. **全量回归保障**：原有 451 项测试保持 100% 绿灯，新增 30+ 项快照与恢复专项单测与 Golden Test。
