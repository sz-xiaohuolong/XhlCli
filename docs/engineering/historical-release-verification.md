# 历史发布简要验收记录

归并日期：2026-10-17。来源为原版本目录中的四份简短验收摘要；原文可从 Git 提交 `690a4e17578369c62eef04b5b426e994b8b1d153` 取回。它们除版本标题外完全相同，现合并保存。

## 1. 原记录的适用版本

| 原记录版本 | 对应发布标识 | 需求或范围入口 | 原记录的交付状态 |
|---|---|---|---|
| v0.1 | v0.1.0 | [Phase 00](../prd/phase-00-project-foundation.md) | VERIFIED |
| v0.2 | v0.2.0 | [Phase 01](../prd/phase-01-terminal-chat.md)、[Phase 02](../prd/phase-02-react-agent.md)、[Phase 03](../prd/phase-03-local-tools.md)、[Phase 04](../prd/phase-04-safety-and-approval.md) | VERIFIED |
| v0.3 | v0.3.0 | [Phase 05](../prd/phase-05-context-and-memory.md) | VERIFIED |
| v1.0.1 | v1.0.1 | [用户全局配置 API Key](../prd/v1.0.1-user-config-api-key.md) | VERIFIED |

## 2. 共同验收摘要

> 交付状态：VERIFIED
> 测试门禁：自动化测试套件通过

原记录的结果：
- 核心功能单元测试与集成测试通过
- 可执行 JAR 构建与冒烟验证通过

上述状态和结果是原记录的主张。原摘要未附运行日期、被测提交、完整命令、测试数量或原始日志，本次归并不补造这些证据，也未重跑对应历史版本。当前实际验证应查对应评测材料与当前进度，不能据这份摘要认定新版本通过。

## 3. Phase 00 的原设计说明

原版本目录没有独立技术设计正文，只说明“本版本架构方案与实现基线一致，详见 SPEC.md 与 TECH_DESIGN.md”。原 SPEC 实际是 [Phase 00 需求](../prd/phase-00-project-foundation.md)的副本；[TECH_DESIGN](../TECH_DESIGN.md)是持续演进的当前架构入口，应通过历史提交查看当时内容。
