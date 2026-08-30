# Changelog

本项目的重要变更记录在此文件中，格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added

- Phase 04 安全策略与人工审批 (Safety and Approval)：
  - 系统硬策略 `PathGuard`：路径围栏防逃逸（绝对路径越界、`..` 穿越、符号链接指向外部），不可被用户批准绕过。
  - 系统硬策略 `CommandGuard`：危险 Shell 命令 Fast-fail 拦截（`sudo`、`rm -rf /`、`mkfs`、`dd of=/dev`、fork bomb、`curl|sh`、`find /`、`chmod 777`、`shutdown/reboot`）。
  - 脱敏审计日志 `AuditLog`：每日 JSONL 结构化审计落盘（`~/.xhlcli/audit/`），自动掩码 Bearer Token、API Key、Password、Secret 等敏感凭据。
  - 风险分级策略 `ApprovalPolicy`：只读工具自动放行，写入/命令工具需人工审批，未注册工具默认高危。
  - 人工审批交互 `TerminalHitlHandler`：结构化终端审批框（CJK/Emoji 显示宽度精确对齐），支持 `y/a/n/s/m` 五种决策。
  - `DefaultToolExecutor` 编排：Schema → 硬策略 → HITL 审批 → 执行 → 审计，修改参数重新校验。
  - `ChatBootstrap` 装配与 `ChatLoop` `/clear` 联动清除会话临时授权。
  - `AgentSafetyIntegrationTest` 端到端安全闭环验证。
- Phase 03 本地工具集 (Local Tools)：
  - `WorkspacePathResolver`：强制限制文件操作在项目工作区内，防止路径穿越与非法越界。
  - 只读探索与读取：`list_dir`（过滤系统隐藏/构建目录）、`read_file`（支持 offset/limit 分页读取与行号标注）、`glob_files`（按 glob 匹配项目文件）。
  - 代码搜索体系：`JavaCodeSearchEngine`（纯 Java 跨平台搜索）、`RipgrepCodeSearchEngine`（流式 `rg --json` 快速搜索与超时降级）、`grep_code`（行上下文展示、预算截断与 suggested_reads 推荐）。
  - 受控写入与补丁：`write_file`（单文件 5MB 上限、自动建目录）、`apply_patch`（单处唯一匹配安全替换）。
  - 版本控制与 Shell：`git_diff`（工作区 Git 差异查看）、`execute_command`（短时受控 Shell 命令执行、输出截断与实时取消）。
  - `CodeSearchGoldenSetTest` 评测集与 `LocalToolsCodingLoopTest` 真实 Agent 循环集成测试。
- Phase 02 ReAct Agent：结构化 Tool Call/Observation、按原顺序的调用执行与调用 ID 关联回灌。
- 演示工具；参数 Schema 校验、结果预算、结构化工具失败和安全摘要。
- 最大迭代、600 秒整体超时、Ctrl+C 取消、一次空响应重试、连续三轮重复无进展保护，以及终态后禁止启动新的模型或工具工作。
- 统一 `RunEvent` 和 Plain 终端渲染；事件及渲染输出不包含 reasoning 或未脱敏凭据。

### Verification

- `./mvnw test` 全量通过 187 项自动化测试（覆盖 Phase 04 安全策略、HITL 审批、审计日志、所有 8 个本地工具、两个搜索引擎、Golden Set 评测集及完整 ReAct 工具循环）。

## [0.2.0] - 2026-08-26

### Added

- DeepSeek 流式终端对话、进程内多轮历史和 Token 用量展示。
- `/help`、`/config`、`/clear`、`/exit` 与响应期间 Ctrl+C 取消。
- `.env`、环境变量、用户 JSON 和命令行参数的安全配置分层。
- OpenAI-compatible SSE 解析、错误分类、超时和受控一次重试。
- 50 项离线自动测试、可执行 shaded JAR 和 macOS CI。

### Security

- API Key 不接受命令行参数或用户 JSON 持久化；终端、错误和 DEBUG 元数据统一脱敏。

### Verification

- Phase 01 通过真实 DeepSeek 五轮会话、Ctrl+C 取消恢复、演示脱敏和 macOS Java 21 CI 验收。

## [0.1.0] - 2026-08-25

### Added

- Java 21 Maven Wrapper 工程基线。
- 可执行 XhlCLI JAR，以及默认说明、`--help` 和 `--version` 命令。
- CLI 行为自动测试和 GitHub Actions Java 21 构建验证。
- Phase 00–18 产品需求、全局技术设计、路线图和协作规范。

[Unreleased]: https://github.com/sz-xiaohuolong/XhlCli/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/sz-xiaohuolong/XhlCli/releases/tag/v0.2.0
[0.1.0]: https://github.com/sz-xiaohuolong/XhlCli/releases/tag/v0.1.0
