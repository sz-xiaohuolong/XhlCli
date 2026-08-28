# Changelog

本项目的重要变更记录在此文件中，格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added

- Phase 02 ReAct Agent：结构化 Tool Call/Observation、按原顺序的调用执行与调用 ID 关联回灌。
- 仅进程内 `echo_text` 与 `current_time` 演示工具；参数 Schema 校验、结果预算、结构化工具失败和安全摘要。
- 最大迭代、600 秒整体超时、Ctrl+C 取消、一次空响应重试、连续三轮重复无进展保护，以及终态后禁止启动新的模型或工具工作。
- 统一 `RunEvent` 和 Plain 终端渲染；事件及渲染输出不包含 reasoning 或未脱敏凭据。

### Verification

- `./mvnw clean verify` 通过 127 项离线测试；可执行 JAR 的帮助、版本 `0.3.0-SNAPSHOT` 和 Java 21 字节码门禁均通过。
- 按用户明确决定，Phase 02 不要求人工演示或录屏；本期没有版本标签。

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
