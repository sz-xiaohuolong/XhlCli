# Changelog

本项目的重要变更记录在此文件中，格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [Unreleased]

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
