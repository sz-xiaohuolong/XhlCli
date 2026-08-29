# Phase 03 本地工具集 (Local Tools) 技术设计

> 日期：2026-08-29
> 状态：已完成实施与全量自动化测试验证
> 对应需求：`docs/prd/phase-03-local-tools.md`

## 1. 目标与边界

Phase 03 在 Phase 02 ReAct Agent 的通用工具执行与结构化协议之上，为 XhlCLI 提供第一套真正的本地开发工具集（Local Tools），使模型能够探索工作区、阅读代码、修改文件并执行受控命令：

1. **工作区安全边界** (`WorkspacePathResolver`)：强制所有文件与目录路径限制在当前项目根目录内，阻断 `..` 路径穿越、绝对路径逃逸与非工作区访问。
2. **只读文件探索**：
   - `list_dir`：列出指定目录下的文件与子目录（带 `[D] `/`[F] ` 前缀，自动过滤 `.git`、`target`、`node_modules`、`.xhlcli` 等构建与隐藏目录）。
   - `read_file`：支持全文件读取或 1-indexed 按行 `offset`/`limit` 分页读取（默认 200 行，上限 2000 行），输出带行号和截断提示。
   - `glob_files`：支持按照 glob 表达式递归匹配项目文件名或相对路径，自动忽略构建与版本控制目录。
3. **代码搜索能力**：
   - `JavaCodeSearchEngine`：纯 Java 跨平台降级引擎，支持大小写敏感/不敏感、正则、行上下文、`head_limit` 和 2MB 大小/二进制文件跳过。
   - `RipgrepCodeSearchEngine`：优先调用本地 `rg --json` 快速搜索，超时或不可用时无缝降级到 Java 引擎。
   - `grep_code`：统一搜索工具，包含字符预算截断控制与精准的 `suggested_reads` 行号推荐。
4. **受控写入与补丁**：
   - `write_file`：受控全文件写入（单文件上限 5MB），自动创建缺失父目录。
   - `apply_patch`：精确单处替换（必须在文件中唯一匹配，0 处或多处匹配时安全报错拒绝）。
5. **版本控制与命令执行**：
   - `git_diff`：在项目工作区执行 `git diff`，无暂存修改时返回友好提示。
   - `execute_command`：在项目工作区执行短时 Shell 命令（默认 60 秒，上限 300 秒超时，输出字符上限 8000 截断），接入 `CancellationToken` 支持随时中断。

## 2. 架构与依赖关系

```text
com.xhlcli.tool.local
├── WorkspacePathResolver (工作区路径安全解析)
├── ListDirTool (list_dir)
├── ReadFileTool (read_file)
├── WriteFileTool (write_file)
├── ApplyPatchTool (apply_patch)
├── GitDiffTool (git_diff)
├── ExecuteCommandTool (execute_command)
├── GlobFilesTool (glob_files)
└── search
    ├── CodeSearchEngine (接口)
    ├── CodeSearchRequest (记录)
    ├── CodeSearchResult (记录)
    ├── GrepMatch (记录)
    ├── ContextLine (记录)
    ├── JavaCodeSearchEngine (纯 Java 搜索实现)
    ├── RipgrepCodeSearchEngine (Ripgrep 包装与降级)
    └── GrepCodeTool (grep_code)
```

## 3. 参考实现采用策略

| 参考提交/文件 | 采用内容 | XhlCLI 演进与设计 |
|---|---|---|
| `e2b8df4:src/main/java/com/paicli/tool/ToolRegistry.java` | 文件读写、执行命令、glob 与 grep 的最小意图 | 彻底拆分巨型单体类，每个工具作为独立 `Tool` 实现，支持依赖注入 |
| `72a7e90:src/main/java/com/paicli/tool/JavaCodeSearchEngine.java` | 递归文件遍历、二进制过滤、行上下文与预算限制 | 统一不可变 Record 协议，严格路径安全约束 |
| `c69be83:src/main/java/com/paicli/tool/RipgrepCodeSearchEngine.java` | `rg --json` 事件流式解析与降级机制 | 保持纯净进程通信，支持系统属性禁用与 8 秒超时保护 |
| `c69be83:src/test/resources/code-search/golden-set.json` | 搜索与读取联动 Golden Set 评测集 | 构建针对 XhlCLI 自身架构的 Golden Set 自动化验证测试 |

## 4. 验证结果

- 所有 8 个本地工具及 2 个搜索引擎均具备完整单元测试。
- `CodeSearchGoldenSetTest` 评测集验证端到端代码定位与 `suggested_reads` 读取联动。
- `LocalToolsCodingLoopTest` 验证 `ReactAgent` 驱动下的多步写代码、读代码、打补丁与命令测试循环。
- 全项目 155 项自动化测试 100% 通过。
