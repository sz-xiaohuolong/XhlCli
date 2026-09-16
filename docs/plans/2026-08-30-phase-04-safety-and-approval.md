# Phase 04 安全策略与人工审批 (Safety and Approval) 实施计划与记录

> **对应设计：** `docs/specs/2026-08-30-phase-04-safety-and-approval-design.md`  
> **对应需求：** `docs/prd/phase-04-safety-and-approval.md`  

## 1. 任务拆分与执行状态

- [x] **Task 1: 系统硬策略与审计 (`com.xhlcli.policy`)**
  - 创建 `PolicyException` 异常
  - 创建 `PathGuard` 及单测 `PathGuardTest`（路径穿越、软链逃逸与越界拦截）
  - 创建 `CommandGuard` 及单测 `CommandGuardTest`（高危命令 Fast-fail 拦截）
  - 创建 `AuditLog` 及单测 `AuditLogTest`（JSONL 每日审计与敏感词脱敏）

- [x] **Task 2: 人工审批与风险策略 (`com.xhlcli.hitl`)**
  - 创建 `RiskLevel` 风险枚举
  - 创建 `ApprovalPolicy` 及单测 `ApprovalPolicyTest`（只读/中危/高危分类与未注册默认高危）
  - 创建 `ApprovalRequest` 及单测 `ApprovalRequestTest`（基于 CJK 宽度的终端边框格式化）
  - 创建 `ApprovalResult` 决策模型
  - 创建 `HitlHandler` 交互契约
  - 创建 `TerminalHitlHandler` 及单测 `TerminalHitlHandlerTest`（处理 y/a/n/s/m 交互、会话放行与安全拒绝）

- [x] **Task 3: DefaultToolExecutor 安全编排**
  - 整合硬策略检查、HITL 审批判断与审计日志记录
  - 编写 `DefaultToolExecutorSafetyTest` 验证拦截、放行与修改参数重新校验

- [x] **Task 4: 启动装配与命令联动**
  - `ChatBootstrap` 装配 `PathGuard`、`AuditLog` 与 `TerminalHitlHandler`
  - `ChatLoop` /clear 命令联动清除会话临时授权
  - 编写 `AgentSafetyIntegrationTest` 验证 ReAct 循环安全闭环

- [x] **Task 5: 文档与全量验证**
  - 编写 Phase 04 设计与计划记录文档
  - 更新 `CHANGELOG.md`、`AGENTS.md` 与 `source-adoption-map.md`
  - 全量 187 项自动化测试全部通过
