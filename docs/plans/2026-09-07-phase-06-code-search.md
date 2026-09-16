# Phase 06: 代码精确检索 实施计划

> 日期：2026-09-07
> 对应技术设计：`docs/specs/2026-09-07-phase-06-code-search-design.md`

## 1. 任务分解

### Task 1: 扩充与完善 Golden Set 评测基准
- 在 `src/test/resources/code-search/golden-set.json` 中补齐 7 类场景用例（类定义、接口实现、方法调用、配置键、测试、入口、不存在符号）。
- 在 `CodeSearchGoldenSetTest.java` 中支持对“不存在符号”的断言（验证 matches 为空，不伪造结果）。
- 实现双引擎对比测试：分别在 `rg` 启用与 `xhlcli.search.disable.rg=true`（Java 降级）模式下运行 Golden Set，断言准确率均达到 100%。

### Task 2: 完善 GrepCodeTool 与无结果引导提示
- 当搜索无结果时，返回更友好的改进建议（建议放宽大小写敏感度、更换 pattern 或检查 glob 路径）。
- 确保非法正则时明确返回参数语法错误，不被静默吞掉或当作空结果。

### Task 3: CLI 新增 `/search-text` 人工调试与验证指令
- 更新 `ChatCommand.java` 与 `ChatCommandParser.java`，增加 `SEARCH_TEXT` 命令识别（支持 `/search-text` 和别名 `/search`）。
- 在 `ChatLoop.java` 中挂载执行逻辑，直接调用项目内 `GrepCodeTool` 执行搜索并将格式化高亮/摘要结果直接输出到终端。

### Task 4: 强化 Agent 搜索策略 System Prompt
- 更新 `ChatBootstrap.java` 中的系统提示词，明确声明 `glob_files -> grep_code -> read_file` 流水线及禁止联网假设的规则。

### Task 5: 验证、文档交付与 Tag 发布
- 运行全量测试，生成双引擎评测与性能报告 `docs/engineering/code-search-golden-set.md`。
- 更新 `CHANGELOG.md`、`AGENTS.md`（标记 Phase 06 完成，更新版本至 `0.4.0-SNAPSHOT`）。
- 提交代码，发布并推送 Git Tag `v0.4.0`，确保 GitHub Actions CI 顺利通过。
