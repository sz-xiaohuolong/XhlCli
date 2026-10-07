# Release v1.0.1 Implementation Plan

<!-- Source: plans/2026-10-07-v1.0.1-user-config-api-key.md -->

# v1.0.1 用户配置支持 API Key 实施计划

> 日期：2026-10-07  
> 状态：已完成 (Delivered)  
> 关联设计：`docs/specs/2026-10-07-v1.0.1-user-config-api-key-design.md`

## 实施任务清单

- [x] **Task 1: 重构 `ChatConfigLoader` 与 `UserConfig` 模型**
  - [x] 扩展 `UserConfig` record，支持 `apiKey`, `deepseekApiKey`, `openaiApiKey`, `anthropicApiKey`, `providers` 等字段与别名。
  - [x] 添加 `ProviderUserConfig` 结构，支持嵌套 Provider 配置。
  - [x] 移除 `containsSecretField` 凭据拦截限制。
  - [x] 在 `firstNonBlankOrMissing` 中引入 `userConfig.getEffectiveApiKey()`，来源赋为 `ConfigSource.USER_CONFIG`。
  - [x] 提供公共静态方法 `ChatConfigLoader.readUserEnv(Path path)`，方便启动类导出全局用户配置环境变量映射。

- [x] **Task 2: 在 `ChatBootstrap` 中集成全局凭据映射**
  - [x] 读取 `userHome/.xhlcli/config.json` 中的 `userEnv`。
  - [x] 按照 `userEnv` (底) -> `dotEnv` -> `environment` (顶) 合并生成 `mergedEnv` 并注入 `LlmProviderRegistry`。
  - [x] 调整缺少 Key 时的错误提示文案，将 `~/.xhlcli/config.json` 作为首要持久化推荐方式。

- [x] **Task 3: 单元与集成测试验证**
  - [x] 修改 `ChatConfigLoaderTest`：重构原有的 `rejectsSecretsInUserJsonConfig` 为 `loadsApiKeyFromUserJsonConfig`。
  - [x] 增加测试用例覆盖优先级级联：`ENVIRONMENT > DOT_ENV > USER_CONFIG`。
  - [x] 增加测试用例覆盖多 Provider Key 解析与 `providers` 嵌套对象解析。
  - [x] 运行全量回归测试套件，确保全部绿灯通过（525 项测试 100% 绿灯）。

- [x] **Task 4: 文档同步与版本更新**
  - [x] 更新 `README.md` 与 `SECURITY.md` 中的配置说明。
  - [x] 更新 `docs/TECH_DESIGN.md`。
  - [x] 更新 `CHANGELOG.md` 记录 `v1.0.1` 补丁变更。
  - [x] 将 `pom.xml` 版本更新为 `1.0.1`。
