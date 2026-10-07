# Release v1.0.1 Project Brief

# Release v1.0.1 Project Brief

## 1. 目标与问题陈述
在 v1.0.0 之前，出于凭据防御策略，XhlCLI 禁止在 ~/.xhlcli/config.json 中配置 API Key。
为对齐 Claude Code 等主流智能终端 CLI，本版本支持在全局用户配置 config.json 中持久化配置 API Key，提升开发者在跨项目目录工作时的体验。

## 2. 需求范围与验收标准
- 解除 ~/.xhlcli/config.json 中凭据拦截限制
- 支持顶层 apiKey、deepseekApiKey、openaiApiKey、anthropicApiKey 及 providers 嵌套映射
- 确立优先级：ENVIRONMENT > DOT_ENV > USER_CONFIG
- 运行时 providerRegistry 自动识别全局凭据
- 525 项测试 100% 绿灯通过

