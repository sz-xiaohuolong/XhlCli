# XhlCLI

XhlCLI 是一个使用 Java 21 构建的本地智能终端 Coding Agent。项目按照可独立验证的阶段逐步交付；当前只提供 Phase 00 工程基线和 CLI 元信息命令，模型对话、Agent 与本地工具仍属于后续阶段。

## 当前已交付

Phase 00 核心工程已实现并完成本地验证：

- Java 21 Maven Wrapper 工程；
- 可执行 JAR 与 `--help`、`--version` 命令；
- JUnit 5 自动测试；
- GitHub Actions Java 21 构建基线；
- 路线图、贡献、安全和协作规范。

当前版本不会调用模型，也不需要 API Key。

## 快速开始

环境要求：JDK 21。仓库自带 Maven Wrapper，无需单独安装 Maven。

```bash
./mvnw clean verify
java -jar target/xhlcli-0.1.0-SNAPSHOT.jar
java -jar target/xhlcli-0.1.0-SNAPSHOT.jar --help
java -jar target/xhlcli-0.1.0-SNAPSHOT.jar --version
```

预期版本输出：

```text
XhlCLI 0.1.0-SNAPSHOT
```

如果本机同时安装了多个 JDK，可显式选择 Java 21：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21) # macOS
./mvnw clean verify
```

## 文档导航

- [产品总 PRD](PRD.md)
- [路线图](ROADMAP.md)
- [需求研究](RESEARCH.md)
- [全局技术设计](TECH_DESIGN.md)
- [AI 开发规则](AGENTS.md)
- [贡献指南](CONTRIBUTING.md)
- [安全策略](SECURITY.md)
- [Phase 00 实施计划](docs/plans/2026-08-25-phase-00-project-foundation.md)
- [Phase 00–18 分期 PRD](docs/prd/)

## 开发原则

- Java 21、终端优先、本地优先。
- 每期都形成可运行、可测试、可演示的最小闭环。
- 计划能力不等于已交付能力，以代码、测试和版本记录为准。
- 高风险能力必须经过安全策略、人工审批和审计。
- 不提交 API Key、用户数据、构建产物或本地运行状态。

下一阶段是 [Phase 01：流式终端对话](docs/prd/phase-01-terminal-chat.md)。模型 Provider、配置变量和 API Key 获取方式会在该阶段实现时同步写入 README 与 `.env.example`；现在无需配置。

## License

[MIT](LICENSE)
