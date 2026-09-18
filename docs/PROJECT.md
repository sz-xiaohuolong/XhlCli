# XhlCLI 项目定位与资产映射 (Project & Document Map)

> 本文档遵循 [`vibe-workflow`](references/artifacts.md) 规范构建，作为项目全局资产索引导航（Document Map）。  
> **定位准则**：索引只导航与映射职责，不承载产品或技术实现结论。

---

## 1. 当前项目定位 (Current Positioning)

- **Project Name**: XhlCLI (小火龙终端 Coding Agent)
- **Current Release**: Phase 10: Multi-Agent 协作架构 (`v0.8.0`, Tag: `v0.8.0`, 2026-09-16)
- **Next Release**: Phase 11: 多模型路由与能力声明 (`docs/prd/phase-11-multi-model.md`)
- **Quality Profile**: Comprehensive（291 项自动化测试 100% 绿灯 + 专项 Benchmark 物证 + DoD 铁律闭环）
- **Product Summary**: 基于 Java 21 与本地优先原则构建的高性能轻量终端 Coding Agent，具备受控 ReAct、安全沙箱围栏、AST 代码库 RAG、DAG 规划执行与多 Agent 协同能力。
- **Primary Users**: 后端及全栈软件工程师，偏好在终端高效完成跨文件重构、代码探索与工程任务。

---

## 2. 事实源映射矩阵 (Document Map)

按照 Vibe-Workflow 规范，严格划分 **Living Documents（持续演进事实源）** 与 **Release-scoped Artifacts（已发布阶段封存制品）**：

### 2.1 持续演进事实源 (Living Documents)

| 职责类别 | 实际路径 | 事实所有权与说明 |
| :--- | :--- | :--- |
| **Agent 行为准则与入口** | [`AGENTS.md`](../AGENTS.md) | Agent 长期行为规则、事实优先级、开发流程与阶段交付铁律 |
| **项目定位与资产映射** | [`docs/PROJECT.md`](PROJECT.md) | 当前定位、Release 状态、文档地图与追溯索引 |
| **真实架构事实源** | [`docs/TECH_DESIGN.md`](TECH_DESIGN.md) | 描述由当前代码和测试核验的真实系统架构 (Phase 11 基线) |
| **总览需求与边界** | [`docs/PRD.md`](PRD.md) | 总体产品定位、能力矩阵与跨阶段规划 |
| **路线图与交付状态** | [`docs/ROADMAP.md`](ROADMAP.md) | 全阶段（Phase 00 ~ 18）交付状态、交付日期与版本履历 |
| **技术预研与竞品分析** | [`docs/RESEARCH.md`](RESEARCH.md) | 竞品分析 (Claude Code, Cline 等) 与关键技术选型 |
| **版本演进履历** | [`CHANGELOG.md`](../CHANGELOG.md) | 基于 Keep a Changelog 规范记录版本变更详情 |

### 2.2 阶段封存制品 (Release-scoped Artifacts)

| 阶段制品目录 | 包含内容 | 治理准则 |
| :--- | :--- | :--- |
| [`docs/prd/`](prd/) | 各阶段独立需求规约 (`phase-00-*.md` ~ `phase-18-*.md`) | 冻结需求定义与验收目标 |
| [`docs/specs/`](specs/) | 各阶段技术方案设计 (`YYYY-MM-DD-phase-XX-*-design.md`) | 目标方案规约；实现验证后核心内容合并至 `TECH_DESIGN.md` |
| [`docs/plans/`](plans/) | 各阶段实施路线任务清单 (`YYYY-MM-DD-phase-XX-*.md`) | Task 清单、DoD 与执行记录；交付后封存 |
| [`docs/engineering/`](engineering/) | 基准测试报告与评估物证 (`*-evaluation.md`, `*-benchmark.md`) | 阶段客观性能实测数据与事实物证 |

---

## 3. 全阶段需求追溯链 (Requirement Traceability Map)

已交付阶段具备完整的 `PRD → Spec → Plan → Evidence → Release Tag` 全链路追踪链：

| 阶段 / Release | 需求定义 (PRD) | 设计规约 (Spec) | 实施计划 (Plan) | 评测物证 (Evidence) | 交付 Tag | 状态 |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Phase 00** 工程骨架 | [phase-00](prd/phase-00-project-foundation.md) | - | [2026-08-25-phase-00](plans/2026-08-25-phase-00-project-foundation.md) | CI 基线通过 | `v0.1.0` | 已封存 |
| **Phase 01** 终端对话 | [phase-01](prd/phase-01-terminal-chat.md) | [2026-08-26-phase-01](specs/2026-08-26-phase-01-terminal-chat-design.md) | [2026-08-26-phase-01](plans/2026-08-26-phase-01-terminal-chat.md) | 演示 Demo GIF | `v0.2.0` | 已封存 |
| **Phase 02** ReAct 循环 | [phase-02](prd/phase-02-react-agent.md) | [2026-08-27-phase-02](specs/2026-08-27-phase-02-react-agent-design.md) | [2026-08-27-phase-02](plans/2026-08-27-phase-02-react-agent.md) | 单元测试套件 | `v0.2.0` | 已封存 |
| **Phase 03** 本地工具 | [phase-03](prd/phase-03-local-tools.md) | [2026-08-29-phase-03](specs/2026-08-29-phase-03-local-tools-design.md) | [2026-08-29-phase-03](plans/2026-08-29-phase-03-local-tools.md) | 工具测试套件 | `v0.2.0` | 已封存 |
| **Phase 04** 安全与审批 | [phase-04](prd/phase-04-safety-and-approval.md) | [2026-08-30-phase-04](specs/2026-08-30-phase-04-safety-and-approval-design.md) | [2026-08-30-phase-04](plans/2026-08-30-phase-04-safety-and-approval.md) | HITL 交互测试 | `v0.2.0` | 已封存 |
| **Phase 05** 上下文与记忆 | [phase-05](prd/phase-05-context-and-memory.md) | [2026-09-02-phase-05](specs/2026-09-02-phase-05-context-and-memory-design.md) | [2026-09-02-phase-05](plans/2026-09-02-phase-05-context-and-memory.md) | 压缩与持久化测试 | `v0.3.0` | 已封存 |
| **Phase 06** 代码检索 | [phase-06](prd/phase-06-code-search.md) | [2026-09-07-phase-06](specs/2026-09-07-phase-06-code-search-design.md) | [2026-09-07-phase-06](plans/2026-09-07-phase-06-code-search.md) | [code-search-golden-set](engineering/code-search-golden-set.md) | `v0.4.0` | 已封存 |
| **Phase 07** 代码库 RAG | [phase-07](prd/phase-07-codebase-rag.md) | [2026-09-10-phase-07](specs/2026-09-10-phase-07-codebase-rag-design.md) | [2026-09-10-phase-07](plans/2026-09-10-phase-07-codebase-rag.md) | [codebase-rag-evaluation](engineering/codebase-rag-evaluation.md) | `v0.5.0` | 已封存 |
| **Phase 08** 规划执行 | [phase-08](prd/phase-08-plan-and-execute.md) | [2026-09-11-phase-08](specs/2026-09-11-phase-08-plan-and-execute-design.md) | [2026-09-11-phase-08](plans/2026-09-11-phase-08-plan-and-execute.md) | [plan-and-execute-evaluation](engineering/plan-and-execute-evaluation.md) | `v0.6.0` | 已封存 |
| **Phase 09** 并发执行 | [phase-09](prd/phase-09-parallel-execution.md) | [2026-09-14-phase-09](specs/2026-09-14-phase-09-parallel-execution-design.md) | [2026-09-14-phase-09](plans/2026-09-14-phase-09-parallel-execution.md) | [parallel-execution-benchmark](engineering/parallel-execution-benchmark.md) | `v0.7.0` | 已封存 |
| **Phase 10** Multi-Agent | [phase-10](prd/phase-10-multi-agent.md) | [2026-09-16-phase-10](specs/2026-09-16-phase-10-multi-agent-design.md) | [2026-09-16-phase-10](plans/2026-09-16-phase-10-multi-agent.md) | [multi-agent-evaluation](engineering/multi-agent-evaluation.md) | `v0.8.0` | 已封存 |
| **Phase 11** 多模型路由 | [phase-11](prd/phase-11-multi-model.md) | [2026-09-18-phase-11](specs/2026-09-18-phase-11-multi-model-design.md) | [2026-09-18-phase-11](plans/2026-09-18-phase-11-multi-model.md) | [multi-model-evaluation](engineering/multi-model-evaluation.md) | `v0.9.0` | 已封存 |
| **Phase 12** MCP 扩展协议 | [phase-12](prd/phase-12-mcp-extensibility.md) | 待设计 | 待制定 | 待评测 | 规划中 | 下一阶段 |

---

## 4. 支持的工程命令 (Supported Commands)

| 目的 | 命令 | 备注 |
| :--- | :--- | :--- |
| **环境检查** | `java -version` | 必须为 Java 21+ (`export JAVA_HOME=/opt/homebrew/opt/openjdk`) |
| **全量自动化测试** | `mvn clean test` | 305 项自动化测试（单元、集成、契约与 Golden Set） |
| **单测运行** | `mvn test -Dtest=<TestClass>` | 运行指定测试类 |
| **打包产物** | `mvn package -DskipTests` | 构建 `target/xhlcli-0.9.0-SNAPSHOT.jar` 可执行 Fat JAR |
| **运行 CLI** | `java -jar target/xhlcli-0.9.0-SNAPSHOT.jar` | 启动交互式终端 Agent |
