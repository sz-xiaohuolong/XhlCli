# XhlCLI 项目全局进度 (Progress)

| 范围 | 当前事实 | 阻塞/未验证 | 下一步 |
|---|---|---|---|
| **Release Baseline** | `Current Release: v1.0.1 (Patch Delivered)`；`Workflow State: READY_FOR_NEXT_RELEASE`；`Operational Status: ACTIVE` | 无阻塞 | 等待下一阶段 (Phase 19 / v1.1.0) 需求输入与冻结 |
| **最新交付** | `v1.0.1 全局 ~/.xhlcli/config.json 支持 API Key 与 Provider 凭据级联` (VERIFIED) | 无缺口 | 保持发布基线，准备新特性规划 |
| **全量验证** | 525 项自动化测试 100% 绿灯；`mvn clean package` 成功构建 `xhlcli-1.0.1.jar` | 无未覆盖 AC | 随时就绪执行下一轮 TDD |

---

## 1. 定位信息 (Context Baseline)

- **Current Release**: `v1.0.1` (Patch: 用户全局配置支持 API Key)
- **Last Stable Tag**: `v1.0.0` (Commit: `2741d36`)
- **Working Tree**: `clean`（525 项测试 100% 绿灯）
- **Last Audited Date**: 2026-10-07
- **Document Map**: [`docs/PROJECT.md`](PROJECT.md)
- **Architecture Baseline**: [`docs/TECH_DESIGN.md`](TECH_DESIGN.md)
- **Changelog**: [`CHANGELOG.md`](../CHANGELOG.md)

---

## 2. 演进阶段交付矩阵 (Phase Delivery Matrix)

| 阶段 / Release | 交付范围 | 状态 | 核心物证 / 验证方式 |
|---|---|---|---|
| **Phase 00** (`v0.1.0`) | Java 21 工程基线、CLI 元信息与 CI 门禁 | `VERIFIED` | 自动化单测与 CI 基线 |
| **Phase 01** (`v0.2.0`) | 终端流式对话、安全配置、脱敏与 DeepSeek 适配 | `VERIFIED` | 演示 Demo GIF 与真实 5 轮会话 |
| **Phase 02** (`v0.2.0`) | 受控 ReAct 循环、Tool Call/Observation 与终止控制 | `VERIFIED` | MockWebServer 契约与单元测试 |
| **Phase 03** (`v0.2.0`) | 9 个本地开发工具（文件读写、补丁、命令执行、代码搜索、Diff） | `VERIFIED` | 工具套件单元与集成测试 |
| **Phase 04** (`v0.2.0`) | 路径越界保护 `WorkspacePathResolver`、CommandGuard、审计日志与 HITL 审批 | `VERIFIED` | 安全沙箱测试与人机审批模拟 |
| **Phase 05** (`v0.3.0`) | Token 预算、层级上下文组装、长期记忆与对话压缩 | `VERIFIED` | 记忆持久化与压缩金标测试 |
| **Phase 06** (`v0.4.0`) | Ripgrep/Java 双引擎精准检索、代码探索流水线与 `/search-text` | `VERIFIED` | Code Search Golden Set (8/8) |
| **Phase 07** (`v0.5.0`) | SQLite + AST 增量语义索引、混合加权检索、`/index`、`/search` | `VERIFIED` | Codebase RAG 评测物证 |
| **Phase 08** (`v0.6.0`) | DAG 拓扑分层调度、Kahn 算法批次、HITL 审阅交互与 `/plan` | `VERIFIED` | Plan & Execute 评测报告 |
| **Phase 09** (`v0.7.0`) | 有界受控并发调度 (Bounded Parallelism)、乱序保序归并、超时与取消传播 | `VERIFIED` | 并行执行基准 Benchmark |
| **Phase 10** (`v0.8.0`) | Multi-Agent 协作团队（1+2+1 体系、审查熔断、Worker 池与 `/team`） | `VERIFIED` | Multi-Agent 评测报告 |
| **Phase 11** (`v0.9.0`) | 多模型路由与能力声明（OpenAI/Anthropic/Ollama、ModelCapabilities、`/model`） | `VERIFIED` | 多模型路由评测报告 |
| **Phase 12** (`v0.10.0`) | MCP 协议集成、双通道传输、工具发现与调用、`/mcp` | `VERIFIED` | MCP 扩展评测报告 |
| **Phase 13** (`v0.11.0`) | SSRF 围栏、多搜索引擎抽象、受限正文抽取、CDP 浏览器控制台与 `/browser` | `VERIFIED` | Web & Browser 评测报告 |
| **Phase 14** (`v0.12.0`) | 8 层提示词装配、系统规则不可变、三层技能注册中心、`/skill` `/prompt` | `VERIFIED` | Skill & Prompt 评测报告 |
| **Phase 15** (`v0.13.0`) | JLine 4 动态状态栏、双模渲染、WCWidth 对齐、代码框与 Diff 着色、`/history` | `VERIFIED` | 终端产品化交互评测报告 |
| **Phase 16** (`v0.14.0`) | JGit 物理隔离快照还原、零宿主污染、自愈工具 `revert_turn`、`/snapshot` `/restore` | `VERIFIED` | 快照与版本恢复评测报告 |
| **Phase 17A** (`v0.15.0`) | SQLite 后台持久任务队列、崩溃自愈、Localhost Runtime API、SSE 游标流 | `VERIFIED` | Runtime & Task 评测报告 |
| **Phase 17B** (`v0.15.1`) | 图片多模态上下文预处理、Alpha Flatten、缩放压缩、剪贴板抓图与视觉防御护栏 | `VERIFIED` | Multimodal Golden Test 物证 |
| **Phase 18** (`v1.0.0`) | 现代化静态官网 (`website/`)、终端模拟器、指令速查表、Vercel 部署、`install.sh` | `VERIFIED` | 静态官网评测报告与发版 |
| **Patch 1.0.1** (`v1.0.1`) | 全局用户配置 `~/.xhlcli/config.json` 支持 API Key 与多 Provider 级联 | `VERIFIED` | 525 项测试全绿，jar 冒烟通过 |
| **Phase 19 / v1.1.0** | 待定义新特性与产品演进 | `NOT_STARTED` | 等待人类决策与需求冻结 |

---

## 3. 最新验证物证 (Verification Evidence)

| 检查项 | 命令 / 流程 | 结果 | 耗时 / 细节 | 证据位置 |
|---|---|---|---|---|
| **全量自动化测试套件** | `./mvnw test` | `525 Tests, 0 Failures, 0 Errors, 0 Skipped` | 15.94s | `target/surefire-reports/` |
| **Jar 打包与 Shade** | `./mvnw clean package -DskipTests` | `BUILD SUCCESS` | 生成 `target/xhlcli-1.0.1.jar` | `target/` |
| **CLI 启动与元信息** | `java -jar target/xhlcli-1.0.1.jar --version` | `XhlCLI 1.0.1` | 冒烟通过 | 标准输出 |
| **一键安装器测试** | `bash install.sh` | 成功安装并输出快捷配置指导 | 本地验证通过 | `~/.xhlcli/bin/xhlcli` |

---

## 4. 下一步行动指南 (Next Steps)

1. **当前状态**：`v1.0.1` 代码、测试、安装器与文档已实现绝对一致闭环。
2. **新迭代准入门禁 (Entry Gate)**：
   - 依据 `vibe-workflow` 宪法第一条：“工程开始前必须冻结需求 (Requirement Status == FROZEN)”。
   - 当用户提出下一阶段的新特性、新架构或新产品需求时，首先执行需求澄清（Requirement Clarification）与范围冻结，生成对应的子 PRD / SPEC。
   - 随后进入标准研发管线：`SPECIFIED` → `DESIGNED` → `PLANNED` → `BUILDING` → `VERIFYING` → `READY_TO_SHIP`。
