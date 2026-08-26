# 安全策略

## 支持版本

| 版本 | 安全更新 |
|---|---|
| 0.2.x（待发布） | 支持 |
| 0.1.x | 支持 |
| 更早版本 | 不支持 |

项目尚处早期阶段。未发布的路线图能力不构成安全承诺。

## 私下报告漏洞

请通过 [GitHub Private Vulnerability Reporting](https://github.com/sz-xiaohuolong/XhlCli/security/advisories/new) 提交报告，不要创建公开 Issue。报告应包含：

- 受影响版本或提交；
- 可复现步骤与最小样例；
- 实际影响和可能的攻击路径；
- 已知缓解方式（如有）。

维护者会尽快确认报告、评估影响并协调修复与披露。在修复公开前，请避免发布漏洞细节、利用代码或真实凭据。

## 凭据泄漏

如果 API Key、Token 或私钥被提交，无论提交随后是否删除，都应立即在服务端撤销并重新签发；删除 Git 历史不能使已泄漏凭据重新安全。

DeepSeek Key 应保存在被 Git 忽略的项目 `.env` 或进程环境变量中。不要放入命令行参数、`~/.xhlcli/config.json`、Issue、聊天记录、截图或演示文件。怀疑泄漏时，应先在 DeepSeek 控制台撤销旧 Key，再创建新 Key 并更新本地配置。

`/config` 只输出 `configured` / `missing` 和配置来源，不输出凭据值。正常错误和 DEBUG 元数据都会经过脱敏；模型消息正文不会写入调试日志。

## 安全边界

Phase 01 只向用户配置的 DeepSeek-compatible HTTP 端点发送会话消息，不读取或修改本地项目文件，也不执行 Agent 工具。命令执行、文件修改、Git、MCP、RAG 和浏览器能力仍未交付，并将在对应阶段给出独立威胁模型与验证标准。
