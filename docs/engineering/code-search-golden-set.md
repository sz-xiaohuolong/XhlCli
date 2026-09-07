# 代码精确检索 (Phase 06) Golden Set 评测与性能基准报告

> 版本：v1.0  
> 日期：2026-09-07  
> 评测集文件：`src/test/resources/code-search/golden-set.json`  
> 评测执行测试：`src/test/java/com/xhlcli/tool/local/search/CodeSearchGoldenSetTest.java`

---

## 1. 评测目标与设计原则

在无外部向量数据库、不预先建立庞大静态索引的轻量环境下，为 Coding Agent 提供确定性、高精度的代码定位能力。
核心策略：
1. **优先候选文件定位 (`glob_files`)**
2. **精确符号与行号定位 (`grep_code`)**
3. **按建议范围读取 (`read_file` 配合 `suggested_reads`)**
4. **双引擎透明降级 (`RipgrepCodeSearchEngine` 优先，无环境依赖时平滑降级至 `JavaCodeSearchEngine`)**

---

## 2. 评测集覆盖范围 (7 类场景)

评测集严格覆盖真实工程开发中常见的 7 类代码定位任务：

| 任务 ID | 评测类别 | 检索目标 | 检索条件 (Pattern & Glob) | 预期结果特征 |
| :--- | :--- | :--- | :--- | :--- |
| `class-definition` | 类定义定位 | `RipgrepCodeSearchEngine` | `public final class RipgrepCodeSearchEngine`, `**/RipgrepCodeSearchEngine.java` | 命中类声明，定位行号并生成 `suggested_reads` |
| `interface-implementation` | 接口实现定位 | `CodeSearchEngine` 接口实现类 | `implements CodeSearchEngine`, `**/JavaCodeSearchEngine.java` | 命中具体实现类，准确定位声明行 |
| `method-invocation` | 关键方法调用定位 | `executor.execute` 调用点 | `executor.execute(call`, `**/ReactAgent.java` | 准确定位 Agent 主循环内的工具调用行 |
| `config-key` | 配置与环境变量定位 | `DEEPSEEK_API_KEY` | `DEEPSEEK_API_KEY`, `**/ChatBootstrap.java` | 命中启动时对环境变量的检查提示 |
| `test-case` | 测试类定位 | `AgentSafetyIntegrationTest` | `class AgentSafetyIntegrationTest`, `**/AgentSafetyIntegrationTest.java` | 命中测试类声明 |
| `command-entrypoint` | 程序主入口定位 | CLI `main` 函数 | `public static void main(String[] args)`, `**/Main.java` | 准确定位应用程序入口 |
| `bootstrap-tools` | 默认工具注册定位 | `EchoTool` 注册代码 | `EchoTool`, `**/ChatBootstrap.java` | 准确定位工具链初始化代码 |
| `non-existent-symbol` | 不存在符号 (负向安全用例) | `NonExistentMagicSymbolXYZ_404` | `NonExistentMagicSymbolXYZ_404`, `**/*.java` | 匹配结果必须为空，不伪造任何结果，且提供建议 |

---

## 3. 双引擎评测结果对比

在 macOS 与 CI 环境下的评测执行数据如下：

| 运行模式 | 测试用例数 | 通过数 | 准确率 (Accuracy) | 平均总耗时 (8 个用例全流程 grep + read) | 适用环境 |
| :--- | :---: | :---: | :---: | :---: | :--- |
| **Ripgrep 引擎 (`rg`)** | 8 | 8 | **100%** | **~44 ms** | 安装有 `rg` 的 Linux / macOS 开发机与 CI 环境 |
| **Java 降级引擎 (Fallback)** | 8 | 8 | **100%** | **~88 ms** | 任何未安装 `rg` 的纯 Java 运行环境（零外部依赖） |

### 核心结论：
1. **语义完全一致**：纯 Java 降级实现在全部正向与负向测试用例中的命中结果、上下文行、文件行号与 `suggested_reads` 生成与 `rg` 表现完全一致。
2. **预算严格受控**：两套引擎均严格遵守字符预算（`max_chars` 限制）与单文件匹配上限（`head_limit`），超限时一致输出 `partial: true` 与缩小搜索建议。
3. **极致性能表现**：无论是 `rg` 还是纯 Java 扫描，全流程耗时均在 100 毫秒以内，满足 PRD 要求中“常见精确查询在 1 秒内开始返回结果”的非功能指标。
