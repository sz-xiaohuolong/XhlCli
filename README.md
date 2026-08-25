# XhlCLI

XhlCLI 是一个基于 Java 21 规划和建设的本地智能终端 Coding Agent。产品目标是在用户授权范围内，通过自然语言完成代码检索、文件修改、命令执行、测试验证和变更总结，并逐步扩展上下文工程、代码库 RAG、计划执行、Multi-Agent、MCP 与终端产品化能力。

## 当前状态

项目当前处于 Phase 00 的规划与设计阶段，已经完成产品需求、分期路线、需求研究、全局技术设计和首期实施计划，尚未开始 Java 工程与 Agent 功能实现。

计划中的能力不代表当前版本已经交付。每一期只有在源码、自动测试、手工验收和文档同步完成后才会更新为已交付状态。

## 开发原则

- Java 21、终端优先、本地优先。
- 先形成可验证的编码闭环，再增加复杂 Agent 能力。
- 精确代码搜索优先，RAG 作为语义补充。
- 所有高风险工具统一经过安全策略、人工审批和审计。
- 每一期独立编译、测试、演示并形成真实提交。
- 不伪造提交时间、测试结果和尚未交付的能力。

## 文档导航

- [产品总 PRD](PRD.md)
- [需求研究](RESEARCH.md)
- [全局技术设计](TECH_DESIGN.md)
- [AI 开发规则](AGENTS.md)
- [Phase 00 实施计划](docs/plans/2026-08-25-phase-00-project-foundation.md)
- [Phase 00–18 分期 PRD](docs/prd/)

## 规划里程碑

| 里程碑 | 阶段 | 目标 |
|---|---|---|
| M0 | Phase 00–01 | 可构建工程与流式终端对话 |
| M1 | Phase 02–06 | 可安全完成小型代码任务的 Coding Agent |
| M2 | Phase 07–10 | RAG、计划、并行与 Multi-Agent |
| M3 | Phase 11–15 | 多模型、MCP、Web、Skill 与终端产品化 |
| M4 | Phase 16–18 | 诊断、快照、Runtime、多模态与开源发行 |

## 下一步

按照 [Phase 00 实施计划](docs/plans/2026-08-25-phase-00-project-foundation.md) 初始化 Java 21 Maven 工程，交付 `--help`、`--version`、自动测试和 CI 基线。

## License

[MIT](LICENSE)
