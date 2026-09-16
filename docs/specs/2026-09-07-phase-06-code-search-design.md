# Phase 06: 代码精确检索 技术设计

> 版本：v1.0
> 日期：2026-09-07
> 对应 PRD: `docs/prd/phase-06-code-search.md`

## 1. 目标与定位

将“实时探索代码库”确立为 Agent 的默认代码理解路径：
`glob_files` 找候选文件 -> `grep_code` 定位符号/行号 -> `read_file` 定向范围读取。
在无额外向量模型或本地后台索引服务的前提下，通过 `ripgrep` 优先与 Java fail-soft 降级双引擎，提供亚秒级、确定性、带预算裁剪的精确代码定位能力。

## 2. 核心架构与设计

### 2.1 搜索流水线与策略强化
1. **Agent System Prompt 指引 (FR-06-06)**：
   - 强化 Agent 在面对代码结构定位、方法调用、配置项查询时，必须优先使用本地精确搜索工具。
   - 禁止在查询本地代码库时误触发外部网络搜索。
   - 严格遵循 `grep` 返回的 `suggested_reads`（offset/limit）定向读取代码，禁止大文件盲目全量读取。

2. **双引擎与降级机制 (FR-06-04)**：
   - 优先执行本地 `rg` 命令（通过 JSON 输出流解析，支持 `--max-filesize 2M`，忽略默认构建目录与隐藏目录）。
   - 本机无 `rg` 或发生进程异常或设置 `xhlcli.search.disable.rg=true` 时，无缝回退到 `JavaCodeSearchEngine`。
   - 保证两套引擎在输出格式、排序规则、字符预算（`max_chars`）、单文件截断（`head_limit`）以及 `suggested_reads` 生成逻辑上完全一致。

3. **结果预算与截断提示 (FR-06-05)**：
   - 达到 `max_results`、`head_limit` 或 `max_chars` 限制时，显式标记 `partial: true` 并给出缩小建议（如加宽限定 glob 或提供更具体的 pattern）。

4. **交互式终端验证指令 (FR-06)**：
   - 在 `ChatLoop` 中新增 `/search-text <pattern>`（别名 `/search`）指令，允许用户或开发者在交互终端中直接调用代码搜索引擎，无需经过大模型即可秒级验证代码搜索结果。

5. **确定性 Golden Set 评测集扩充 (FR-06-08)**：
   - 将 Golden Set 扩充至覆盖 7 类典型工程代码定位任务：
     1. 类与接口定义定位
     2. 接口实现类定位
     3. 核心方法调用定位
     4. 关键配置键/环境变量引用定位
     5. 测试类与测试方法定位
     6. 程序入口与主函数定位
     7. 不存在符号的边界查询（确保不伪造虚假匹配）
   - 增加双引擎对比测试：验证 `rg` 与 `Java` 降级实现的正确率均为 100%，并输出性能基线。

## 3. 涉及模块与文件变更
- `src/main/java/com/xhlcli/tool/local/search/`：引擎优化、无结果时提示建议
- `src/main/java/com/xhlcli/cli/ChatCommandParser.java` & `ChatLoop.java`：新增 `/search-text` 命令
- `src/main/java/com/xhlcli/cli/ChatBootstrap.java`：注入更新后的代码搜索系统规则 Prompt
- `src/test/resources/code-search/golden-set.json`：扩充至 7 类全覆盖 Golden Set
- `src/test/java/com/xhlcli/tool/local/search/CodeSearchGoldenSetTest.java`：增加双引擎回归与不存在符号断言
- `docs/engineering/code-search-golden-set.md`：生成 Golden Set 评测与双引擎性能对比报告
