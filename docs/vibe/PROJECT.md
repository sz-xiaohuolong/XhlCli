# XhlCLI 项目定位与资产映射 (Project & Document Map)

> 本文档遵循 [`vibe-workflow`](references/artifacts.md) 规范构建，作为项目全局资产索引导航（Document Map）。  
> **定位准则**：索引只导航与映射职责，不承载产品或技术实现结论。

---

## 1. 当前项目定位 (Current Positioning)

- **Project Name**: XhlCLI (小火龙终端 Coding Agent)
- **Current Release**: `v1.0.1` 补丁发布（全局 `~/.xhlcli/config.json` 支持 API Key 与多 Provider 凭据映射，2026-10-07）
  - 基线：Phase 18 现代化静态官网 (`website/`)、交互式终端模拟器、全量指令速查、Vercel 部署配置与跨平台 `install.sh`
- **Quality Profile**: Comprehensive（525 项自动化测试 100% 绿灯 + 阶段评测物证 + DoD 铁律闭环）
- **Product Summary**: 基于 Java 21 与本地优先原则构建的高性能轻量终端 Coding Agent，具备受控 ReAct、安全沙箱围栏、AST 代码库 RAG、DAG 规划执行、多 Agent 协同、Side-History 隔离快照恢复、后台持久任务/Runtime API 与图片多模态上下文预处理/视觉防御护栏。
- **Primary Users**: 后端及全栈软件工程师，偏好在终端高效完成跨文件重构、代码探索与工程任务。

---

## 2. 事实源映射矩阵 (Document Map)

按照 Vibe-Workflow 规范，严格划分 **Living Documents（持续演进事实源）** 与 **Release-scoped Artifacts（已发布阶段封存制品）**：

### 2.1 持续演进事实源 (Living Documents)

| 职责类别 | 实际路径 | 事实所有权与说明 |
| :--- | :--- | :--- |
| **Agent 行为准则与入口** | [`AGENTS.md`](../AGENTS.md) | Agent 长期行为规则、事实优先级、开发流程与阶段交付铁律 |
| **项目定位与资产映射** | [`docs/PROJECT.md`](PROJECT.md) | 当前定位、Release 状态、文档地图与追溯索引 |
| **项目全局执行进度** | [`docs/PROGRESS.md`](PROGRESS.md) | 当前 Workflow 状态、Release 状态、Last Stable Commit 与验证物证 |
| **真实架构事实源** | [`docs/TECH_DESIGN.md`](TECH_DESIGN.md) | 描述由当前代码和测试核验的真实系统架构 (Phase 16 基线) |
| **总览需求与边界** | [`docs/PRD.md`](PRD.md) | 总体产品定位、能力矩阵与跨阶段规划 |
| **路线图与交付状态** | [`docs/ROADMAP.md`](ROADMAP.md) | 全阶段（Phase 00 ~ 18）交付状态、交付日期与版本履历 |
| **技术预研与竞品分析** | [`docs/RESEARCH.md`](RESEARCH.md) | 竞品分析 (Claude Code, Cline 等) 与关键技术选型 |
| **版本演进履历** | [`CHANGELOG.md`](../CHANGELOG.md) | 基于 Keep a Changelog 规范记录版本变更详情 |

### 2.2 阶段封存制品 (Release-scoped Artifacts)

| 阶段制品目录 | 包含内容 | 治理准则 |
| :--- | :--- | :--- |
| [`docs/vibe/releases/<release-id>/`](releases/) | 各版本独立制品包：`PROJECT_BRIEF.md`, `SPEC.md`, `PROPOSED_DESIGN.md`, `IMPLEMENTATION_PLAN.md`, `VERIFICATION.md` | 严格按版本归档，冻结需求定义与验收闭环，已发布版本不回写 |
| [`docs/prd/`](../prd/) | 历史阶段需求定义存档 (镜像保留) | 追溯参考 |
| [`docs/specs/`](../specs/) | 历史技术方案设计存档 (镜像保留) | 追溯参考 |
| [`docs/plans/`](../plans/) | 历史实施计划存档 (镜像保留) | 追溯参考 |
| [`docs/engineering/`](../engineering/) | 历史评测报告物证存档 (镜像保留) | 追溯参考 |

---

## 3. 全阶段需求追溯链 (Requirement Traceability Map)

已交付阶段具备完整的 `PRD → Spec → Plan → Evidence → Release Tag` 全链路追踪链：

| 阶段 / Release | 需求范围 (Brief) | 设计规约 (Spec/Design) | 实施计划 (Plan) | 评测物证 (Verification) | 交付 Tag | 归档位置 |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Phase 00** 工程骨架 | [Brief](releases/v0.1/PROJECT_BRIEF.md) | [Spec](releases/v0.1/SPEC.md) | [Plan](releases/v0.1/IMPLEMENTATION_PLAN.md) | [Evidence](releases/v0.1/VERIFICATION.md) | `v0.1.0` | [`v0.1/`](releases/v0.1/) |
| **Phase 01~04** 对话与Agent | [Brief](releases/v0.2/PROJECT_BRIEF.md) | [Spec](releases/v0.2/SPEC.md) | [Plan](releases/v0.2/IMPLEMENTATION_PLAN.md) | [Evidence](releases/v0.2/VERIFICATION.md) | `v0.2.0` | [`v0.2/`](releases/v0.2/) |
| **Phase 05** 上下文与记忆 | [Brief](releases/v0.3/PROJECT_BRIEF.md) | [Spec](releases/v0.3/SPEC.md) | [Plan](releases/v0.3/IMPLEMENTATION_PLAN.md) | [Evidence](releases/v0.3/VERIFICATION.md) | `v0.3.0` | [`v0.3/`](releases/v0.3/) |
| **Phase 06** 代码检索 | [Brief](releases/v0.4/PROJECT_BRIEF.md) | [Spec](releases/v0.4/SPEC.md) | [Plan](releases/v0.4/IMPLEMENTATION_PLAN.md) | [Evidence](releases/v0.4/VERIFICATION.md) | `v0.4.0` | [`v0.4/`](releases/v0.4/) |
| **Phase 07** 代码库 RAG | [Brief](releases/v0.5/PROJECT_BRIEF.md) | [Spec](releases/v0.5/SPEC.md) | [Plan](releases/v0.5/IMPLEMENTATION_PLAN.md) | [Evidence](releases/v0.5/VERIFICATION.md) | `v0.5.0` | [`v0.5/`](releases/v0.5/) |
| **Phase 08** 规划执行 | [Brief](releases/v0.6/PROJECT_BRIEF.md) | [Spec](releases/v0.6/SPEC.md) | [Plan](releases/v0.6/IMPLEMENTATION_PLAN.md) | [Evidence](releases/v0.6/VERIFICATION.md) | `v0.6.0` | [`v0.6/`](releases/v0.6/) |
| **Phase 09** 并发执行 | [Brief](releases/v0.7/PROJECT_BRIEF.md) | [Spec](releases/v0.7/SPEC.md) | [Plan](releases/v0.7/IMPLEMENTATION_PLAN.md) | [Evidence](releases/v0.7/VERIFICATION.md) | `v0.7.0` | [`v0.7/`](releases/v0.7/) |
| **Phase 10** Multi-Agent | [Brief](releases/v0.8/PROJECT_BRIEF.md) | [Spec](releases/v0.8/SPEC.md) | [Plan](releases/v0.8/IMPLEMENTATION_PLAN.md) | [Evidence](releases/v0.8/VERIFICATION.md) | `v0.8.0` | [`v0.8/`](releases/v0.8/) |
| **Phase 11** 多模型路由 | [Brief](releases/v0.9/PROJECT_BRIEF.md) | [Spec](releases/v0.9/SPEC.md) | [Plan](releases/v0.9/IMPLEMENTATION_PLAN.md) | [Evidence](releases/v0.9/VERIFICATION.md) | `v0.9.0` | [`v0.9/`](releases/v0.9/) |
| **Phase 12** MCP 扩展协议 | [Brief](releases/v0.10/PROJECT_BRIEF.md) | [Spec](releases/v0.10/SPEC.md) | [Plan](releases/v0.10/IMPLEMENTATION_PLAN.md) | [Evidence](releases/v0.10/VERIFICATION.md) | `v0.10.0` | [`v0.10/`](releases/v0.10/) |
| **Phase 13** Web 与浏览器 | [Brief](releases/v0.11/PROJECT_BRIEF.md) | [Spec](releases/v0.11/SPEC.md) | [Plan](releases/v0.11/IMPLEMENTATION_PLAN.md) | [Evidence](releases/v0.11/VERIFICATION.md) | `v0.11.0` | [`v0.11/`](releases/v0.11/) |
| **Phase 14** Skill 与 Prompt 分层 | [Brief](releases/v0.12/PROJECT_BRIEF.md) | [Spec](releases/v0.12/SPEC.md) | [Plan](releases/v0.12/IMPLEMENTATION_PLAN.md) | [Evidence](releases/v0.12/VERIFICATION.md) | `v0.12.0` | [`v0.12/`](releases/v0.12/) |
| **Phase 15** 终端交互产品化 | [Brief](releases/v0.13/PROJECT_BRIEF.md) | [Spec](releases/v0.13/SPEC.md) | [Plan](releases/v0.13/IMPLEMENTATION_PLAN.md) | [Evidence](releases/v0.13/VERIFICATION.md) | `v0.13.0` | [`v0.13/`](releases/v0.13/) |
| **Phase 16** 隔离快照与版本恢复 | [Brief](releases/v0.14/PROJECT_BRIEF.md) | [Spec](releases/v0.14/SPEC.md) | [Plan](releases/v0.14/IMPLEMENTATION_PLAN.md) | [Evidence](releases/v0.14/VERIFICATION.md) | `v0.14.0` | [`v0.14/`](releases/v0.14/) |
| **Phase 17A** Runtime 与后台任务 | [Brief](releases/v0.15/PROJECT_BRIEF.md) | [Spec](releases/v0.15/SPEC.md) | [Plan](releases/v0.15/IMPLEMENTATION_PLAN.md) | [Evidence](releases/v0.15/VERIFICATION.md) | `v0.15.0` | [`v0.15/`](releases/v0.15/) |
| **Phase 17B** 多模态视觉护栏 | [Brief](releases/v0.15.1/PROJECT_BRIEF.md) | [Spec](releases/v0.15.1/SPEC.md) | [Plan](releases/v0.15.1/IMPLEMENTATION_PLAN.md) | [Evidence](releases/v0.15.1/VERIFICATION.md) | `v0.15.1` | [`v0.15.1/`](releases/v0.15.1/) |
| **Phase 18** 静态官网与开源发布 | [Brief](releases/v1.0/PROJECT_BRIEF.md) | [Spec](releases/v1.0/SPEC.md) | [Plan](releases/v1.0/IMPLEMENTATION_PLAN.md) | [Evidence](releases/v1.0/VERIFICATION.md) | `v1.0.0` | [`v1.0/`](releases/v1.0/) |
| **v1.0.1 Patch** 全局配置 API Key | [Brief](releases/v1.0.1/PROJECT_BRIEF.md) | [Spec](releases/v1.0.1/SPEC.md) | [Plan](releases/v1.0.1/IMPLEMENTATION_PLAN.md) | [Evidence](releases/v1.0.1/VERIFICATION.md) | `v1.0.1` | [`v1.0.1/`](releases/v1.0.1/) |

---

## 4. 支持的工程命令 (Supported Commands)

| 目的 | 命令 | 备注 |
| :--- | :--- | :--- |
| **环境检查** | `java -version` | 必须为 Java 21+ (`export JAVA_HOME=/opt/homebrew/opt/openjdk`) |
| **全量自动化测试** | `mvn clean test` | 525 项自动化测试（单元、集成、契约与 Golden Set 100% 绿灯） |
| **单测运行** | `mvn test -Dtest=<TestClass>` | 运行指定测试类 |
| **打包产物** | `mvn package -DskipTests` | 构建 `target/xhlcli-1.0.1.jar` 可执行 Fat JAR |
| **运行 CLI** | `java -jar target/xhlcli-1.0.1.jar` | 启动交互式终端 Agent |
