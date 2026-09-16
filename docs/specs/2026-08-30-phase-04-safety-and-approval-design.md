# Phase 04 安全策略与人工审批 (Safety and Approval) 技术设计

> 日期：2026-08-30  
> 状态：已完成实施与全量自动化测试验证  
> 对应需求：`docs/prd/phase-04-safety-and-approval.md`  

## 1. 目标与设计原则

Phase 04 建立了独立于 Agent 决策的安全与人工审批防御层，确保所有工具调用在执行前均经过硬策略拦截、风险识别、人工审批与脱敏审计：

1. **系统硬策略高于用户批准 (Hard Policy)**：
   - 当模型请求越界路径（如 `/etc/passwd`、`../secret`、逃逸软链接）或高危破坏性命令（如 `sudo`、`rm -rf /`、`mkfs`、`fork bomb`、`curl|sh` 等）时，系统直接在最前端快速拒绝并记录审计，**绝不弹出审批框**，用户亦无法通过批准强制执行。
2. **风险分级与精准打断 (HITL)**：
   - **只读操作**（`read_file`、`list_dir`、`glob_files`、`grep_code`、`git_diff`、`echo_text`、`current_time`）：自动放行并审计，不打断用户。
   - **中高危操作**（`write_file`、`apply_patch`、`execute_command` 及未注册的新工具）：弹出结构化终端审批框，展示工具名、风险等级、执行参数与说明。
   - **决策选项**：
     - `[y/Enter]` 批准单次执行；
     - `[a]` 批准本次会话后续所有同类工具调用（临时授权，`/clear` 或退出时销毁）；
     - `[n]` 拒绝执行并可附带原因；
     - `[s]` 跳过当前步骤；
     - `[m]` 修改参数（修改后重新进行 Schema 校验与硬策略检查）。
3. **脱敏审计 (AuditLog)**：
   - 每日在 `~/.xhlcli/audit/audit-YYYY-MM-DD.jsonl` 中记录 JSONL 格式审计条目；
   - 自动截断超长字段并掩码敏感信息（Bearer Token、API Key、Password、Secret 等）；
   - 审计写入失败采用 fail-safe 降级打印警告，不影响开发主流程。

---

## 2. 架构分层

```text
模型提出 ToolCall
  │
  ▼
DefaultToolExecutor
  ├── 1. ToolSchemaValidator (参数 Schema 校验)
  ├── 2. PathGuard / CommandGuard (系统硬策略检查)
  ├── 3. ApprovalPolicy + HitlHandler (风险分级与终端 HITL 审批)
  ├── 4. Tool.execute (工具真正执行)
  ├── 5. AuditLog (脱敏审计日志记录)
  └── 6. ToolResultBudget (结果预算裁剪)
```

---

## 3. 参考实现采用策略

| 参考提交/文件 | 采用内容 | XhlCLI 演进与设计 |
|---|---|---|
| `75e6642:src/main/java/com/paicli/policy/PathGuard.java` | 路径围栏、符号链接解析、向上查找父级目录以验证新建文件软链 | 提取为标准组件，支持 Path 与 String 入参，规范化跨平台真实根路径 |
| `75e6642:src/main/java/com/paicli/policy/CommandGuard.java` | 命令 Fast-fail 正则黑名单 | 支持组合标志与多参数匹配（如 `rm -r -f /`），提供 `check` 与 `validateSafe` |
| `75e6642:src/main/java/com/paicli/policy/AuditLog.java` | 每日 JSONL 审计、敏感凭据正则脱敏 | 统一落盘至 `~/.xhlcli/audit`，集成不可变 `AuditEntry` 与多级审计者标记 |
| `f90d9f5:src/main/java/com/paicli/hitl/*` | `ApprovalPolicy`、`ApprovalRequest`、`ApprovalResult`、`TerminalHitlHandler` | 基于 CJK/Emoji 显示宽度精确对齐终端边框，与 `ChatLoop` /clear 联动清理临时放行 |

---

## 4. 验证结果

- 覆盖 `PathGuardTest`、`CommandGuardTest`、`AuditLogTest`、`ApprovalPolicyTest`、`ApprovalRequestTest`、`TerminalHitlHandlerTest`、`DefaultToolExecutorSafetyTest`、`AgentSafetyIntegrationTest` 等完整测试集。
- 全项目 187 项自动化测试 100% 通过。
