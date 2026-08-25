# 贡献指南

感谢参与 XhlCLI。请让每次改动保持范围清晰、可运行、可测试，并准确区分已交付能力和路线图能力。

## 开发环境

- JDK 21；
- Git；
- macOS、Linux 或 Windows；
- 无需单独安装 Maven，使用仓库内的 Maven Wrapper。

```bash
git clone https://github.com/sz-xiaohuolong/XhlCli.git
cd XhlCli
./mvnw clean verify
java -jar target/xhlcli-0.1.0-SNAPSHOT.jar --help
```

## 贡献流程

1. 先阅读 `AGENTS.md`、当前阶段子 PRD、技术设计和实施计划。
2. 从最新主分支创建单一目的的特性分支。
3. 行为变更先添加能证明需求的失败测试，再实现最小代码。
4. 运行 `./mvnw clean verify`，并手工验证受影响的 CLI 路径。
5. 同步更新相关 PRD、技术设计、README、CHANGELOG 或配置示例。
6. 提交 Pull Request，说明范围、验证证据、风险和未完成事项。

推荐提交前缀：`docs:`、`test:`、`feat:`、`fix:`、`refactor:`、`build:`、`ci:`、`chore:`。

## Pull Request 要求

- 一个 PR 解决一个明确问题，避免混入无关格式化。
- 不提前创建后续阶段空包或用占位逻辑冒充实现。
- 测试不得依赖真实 API Key、网络、用户主目录或全局 Git 配置。
- 新增 Provider 或外部服务时，提供不含秘密的 `.env.example` 和配置说明。
- 修改架构边界、持久化格式或安全策略时，补充设计记录和回归测试。

## 凭据与数据

不要提交 `.env`、API Key、Token、私钥、日志、模型对话、用户代码索引或 `~/.xhlcli` 数据。若凭据意外进入提交，请立即撤销凭据并按 [安全策略](SECURITY.md) 私下报告。

参与本项目即表示同意遵守 [行为准则](CODE_OF_CONDUCT.md)。
