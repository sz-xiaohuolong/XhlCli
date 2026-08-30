# 授权参考实现采用地图

> 文档性质：工程实施依据，不属于产品 PRD
> 更新日期：2026-08-25
> 授权状态：项目所有者已确认拥有修改和公开再发布授权

## 1. 目的

本文件把 XhlCLI 的阶段需求映射到授权参考仓库的历史提交、源码模块、测试资产和迁移策略，避免开发过程中重复设计成熟机制，也避免把最终版本一次性导入造成阶段失真。

参考仓库本地路径：`../paicli`。该路径只用于开发时读取，不作为 XhlCLI 的构建依赖。

## 2. 迁移规则

1. 不复制参考仓库 `.git`、构建产物、真实配置、日志、演示业务和无关网站资源。
2. 每期先读取历史提交的最小实现，再读取最终版本对应模块，吸收后续已修复问题。
3. 源包名统一从 `com.paicli` 迁移到 `com.xhlcli`，产品名和用户目录统一为 XhlCLI / `.xhlcli` / `~/.xhlcli`。
4. Java 编译基线从 17 升级到 21；不引入需要 `--enable-preview` 的 API。
5. 只迁移本期需要的依赖。后期库不得提前进入 Phase 00–01。
6. 测试与实现一起迁移；先确认测试在 XhlCLI 下失败或缺失，再完成最小迁移使其通过。
7. 参考实现中与 XhlCLI PRD 冲突的行为，以 XhlCLI PRD 为准，并记录差异。
8. 公开发布前按书面授权处理原版权、NOTICE 和再分发要求。

## 3. 历史基线

参考仓库共有 45 个提交，最早可用基线 `e2b8df4` 包含 ReAct、CLI、GLM Client 和 Tool Registry。其后提交基本保留了功能演进顺序，因此可作为阶段研究线索，但 XhlCLI 的阶段划分以自身 PRD 为准。

## 4. 分期映射

| XhlCLI 阶段 | 首要参考提交 | 重点模块/文件 | 迁移说明 |
|---|---|---|---|
| 00 项目基线 | `e2b8df4` 的 `pom.xml` | Maven 构建、Shade 入口、`.gitignore` | 只采用构建思路，不迁移 Agent 业务；升级 Java 21 并增加测试/CI |
| 01 终端对话 | `e2b8df4`、`530bb9c`、`f49d33c`、最终 `llm/DeepSeekClient` | `cli/Main`、`llm/AbstractOpenAiCompatibleClient`、`llm/DeepSeekClient` | 采用请求结构、SSE 累积、Bearer 鉴权与 DeepSeek HTTP/1.1 兼容点；新增安全配置、取消、错误分类、受控重试和测试边界，不引入 Tool Call |
| 02 ReAct | `e2b8df4`、`f49d33c`、`a6fa3a8`、`b7ee842` | `agent/Agent`、`agent/AgentBudget`、`llm/LlmClient`、`llm/AbstractOpenAiCompatibleClient`、`tool/ToolRegistry` | 已交付：保留最小循环、结构化消息和流式 Tool Call 的采用意图；以 XhlCLI 自有协议补上最大迭代、超时、取消、重复检测、事件和受控演示工具 |
| 03 本地工具 | `e2b8df4`、`72a7e90`、`c69be83` | `tool/ToolRegistry`、代码搜索工具 | 拆分最终巨型 Registry，按工具职责迁移，保留统一注册入口 |
| 04 安全审批 | `75e6642`、`f90d9f5` | `hitl/*`、`policy/*` | 迁移审批和围栏，按 PRD 保持硬策略优先、非交互默认拒绝 |
| 05 上下文记忆 | `d16c54e`、`72a7e90`、`96bc8b2` | `memory/*`、`context/*`、项目记忆加载 | 分开会话压缩、长期记忆和项目规则，删除自动长期记忆路径 |
| 06 精确搜索 | `72a7e90`、`c69be83` | `tool/CodeSearchEngine*`、`CodeSearchGoldenSetTest` | `rg` 优先、Java 降级、结果预算与建议读取 |
| 07 代码库 RAG | `3ff4ebc` | `rag/*` | 迁移切分、Embedding、SQLite 向量存储和增量索引；精确搜索仍为默认 |
| 08 计划执行 | `a3101c4`–`bfdaf43` | `plan/*`、`PlanExecuteAgent`、审阅解析 | 先串行 DAG，再引入重新规划和范围限制 |
| 09 并行执行 | `039ecdc` | Agent/Plan/SubAgent 的批量执行入口 | 统一到单一有界调度器，保持结果顺序 |
| 10 Multi-Agent | `9243724` | `AgentOrchestrator`、`SubAgent`、角色消息 | 保留 Planner/Worker/Reviewer，强化交接包与审查证据 |
| 11 多模型 | `f49d33c`、`2cbff45`、`b7ee842` | `llm/*Client`、`LlmClientFactory` | 先迁移两个 Provider 契约，再逐个接入其余实现 |
| 12 MCP | `89296e9`、`5de88fb` | `mcp/*` | 先 stdio/HTTP/Tools，再 Resources/通知/取消；OAuth 等不提前声明 |
| 13 Web 浏览器 | `fcd2d3a`、`08be113`、`bfc3ad9` | `web/*`、`browser/*` | 轻量 Web 优先，浏览器作为复杂页面降级并接统一安全策略 |
| 14 Skill Prompt | `b946269`、`bceb60b` | `skill/*`、`prompt/*`、resources | 迁移分层组装和按需 Skill，不迁移无关站点经验作为首期内容 |
| 15 终端产品化 | `837b8bf`–`c206b18`、`d399899` | `render/*`、`tui/*`、JLine CLI | 优先行内 Renderer 和 Plain 降级，全屏 TUI 不作为 DoD |
| 16 诊断快照 | `bceb60b` | `lsp/*`、`snapshot/*` | 将诊断与快照拆成两个可独立验收子能力 |
| 17 Runtime 多模态 | `bceb60b` | `runtime/*`、`image/*` | 先持久任务与 API，再图片输入；共享 Run 事件和权限层 |
| 18 开源发行 | 最终仓库测试与文档 | 测试矩阵、构建配置、示例 | 不迁移品牌站点；新建 XhlCLI 自有基准、发行和贡献文档 |

### 4.1 Phase 02 已交付采用记录

Phase 02 的采用依据固定为下列 Git 对象；读取时使用 `git -C ../paicli show <commit>:<path>`，不读取或修改参考工作树中的未提交状态。

| 固定对象 | 采用的意图 | XhlCLI 主动差异 |
|---|---|---|
| `e2b8df4:src/main/java/com/paicli/agent/Agent.java` | 最小 ReAct 循环、assistant Tool Call 入历史、Observation 回灌和无调用完成 | 拆分到 `ReactAgent`、`RunLifecycle`、`RunEvent` 与 `ToolExecutor`；不直接写终端 |
| `e2b8df4:src/main/java/com/paicli/llm/GLMClient.java` | OpenAI-compatible message、Tool Call 和 `tool_call_id` 线索 | 使用不可变顶层 `com.xhlcli.model` 协议，并保持 Phase 01 的 Provider 边界与取消/错误映射 |
| `e2b8df4:src/main/java/com/paicli/tool/ToolRegistry.java` | 注册、定义导出和按名称查找的最小意图 | 重名失败、受限 JSON Schema 校验、结构化 `ToolResult`；仅 `echo_text` 和 `current_time`，不迁移真实本地工具 |
| `f49d33c:src/main/java/com/paicli/llm/LlmClient.java`、`src/test/java/com/paicli/agent/AgentMessageTest.java`、`src/test/java/com/paicli/tool/ToolRegistryTest.java` | Provider-neutral LLM 边界和消息/注册测试线索 | 融合既有流式接口；XhlCLI 自建确定性契约测试，不引入参考实现的后期职责 |
| `a6fa3a8:src/main/java/com/paicli/agent/AgentBudget.java`、`src/test/java/com/paicli/agent/AgentBudgetTest.java` | 最大轮次和连续调用停滞检测 | `RunLimits` 加入 1–100 迭代边界；重复指纹同时比较规范化参数和结果，并连续三轮才限制 |
| `b7ee842:src/main/java/com/paicli/agent/Agent.java`、`src/main/java/com/paicli/llm/AbstractOpenAiCompatibleClient.java`、`src/main/java/com/paicli/tool/ToolRegistry.java`、`src/test/java/com/paicli/agent/AgentMessageTest.java`、`src/test/java/com/paicli/agent/AgentStreamRendererTest.java`、`src/test/java/com/paicli/tool/ToolRegistryTest.java` | 最终消息顺序、流式 Tool Call 碎片累积和 Registry 职责分离的对照 | 不迁移最终巨型类、Memory/RAG/Skill/LSP/图片/并行/MCP 或参考渲染；增加整体超时、first-wins 终态门、RunEvent 脱敏和 Plain 输出 |

### 4.2 Phase 03 已交付采用记录

Phase 03 的采用依据固定为下列 Git 对象：

| 固定对象 | 采用的意图 | XhlCLI 主动差异 |
|---|---|---|
| `e2b8df4:src/main/java/com/paicli/tool/ToolRegistry.java` | `readFile`、`writeFile`、`listDir`、`executeCommand` | 彻底拆分巨型单体类，每个工具作为独立 `Tool` 实现，统一注入 `WorkspacePathResolver` 确保路径安全 |
| `72a7e90:src/main/java/com/paicli/tool/JavaCodeSearchEngine.java` | 纯 Java 代码搜索、文件树遍历、二进制跳过、行号与上下文提取 | 领域对象统一抽象为不可变 Record，严格校验工作区边界 |
| `c69be83:src/main/java/com/paicli/tool/RipgrepCodeSearchEngine.java` | `rg --json` 进程流式解析、8 秒超时、自动降级 Java 引擎 | 增加 `xhlcli.search.disable.rg` 属性控制，统一资源回收与异常处理 |
| `c69be83:src/test/resources/code-search/golden-set.json` | 搜索与读取联动 Golden Set 评测基准 | 构建针对 XhlCLI 源码架构的真实 Golden Set 用例集合 |

### 4.3 Phase 04 已交付采用记录

Phase 04 的采用依据固定为下列 Git 对象：

| 固定对象 | 采用的意图 | XhlCLI 主动差异 |
|---|---|---|
| `75e6642:src/main/java/com/paicli/policy/PathGuard.java` | 路径围栏与符号链接向父级展开解析 | 统一不可变异常 `PolicyException`，支持 Path 与 String 接口，针对多平台真实路径标准化 |
| `75e6642:src/main/java/com/paicli/policy/CommandGuard.java` | 命令 Fast-fail 破坏性黑名单规则 | 拓展组合参数与多标志正则匹配（如 `rm -r -f /`），提供清晰原因说明 |
| `75e6642:src/main/java/com/paicli/policy/AuditLog.java` | 每日 JSONL 审计落盘与凭据脱敏 | 统一配置路径 `~/.xhlcli/audit`，不可变 `AuditEntry` 记录，Fail-safe 错误容忍 |
| `f90d9f5:src/main/java/com/paicli/hitl/*` | 人工审批策略、请求展示、决策模型与终端处理器 | 基于 CJK/Emoji 列宽精确对齐，与 `DefaultToolExecutor` 严格编排，与 `ChatLoop` /clear 联动清理临时授权 |

XhlCLI 的验证资产为自有 `com.xhlcli` 测试：工具/消息/事件/流式协议、成功与失败恢复、限制、取消、超时、重复、空响应、重复调用 ID 和终态不变量均不依赖真实 Key、网络、用户目录或参考仓库测试运行。发布时仍按本文件第 2 节的书面授权与法律要求处理。

## 5. 不直接迁移的内容

- `target/`、`output/`、IDE 配置和本地环境文件。
- `landing/`、`poster/`、`ppt/` 等品牌宣传资产。
- `demo/` 中与 XhlCLI 阶段验收无关的业务修改。
- 微信通道及其凭据、账号存储和守护进程逻辑；它不在 XhlCLI 主路线。
- 仅存在于 Roadmap、尚未交付或无法测试的占位能力。
- 参考仓库中的品牌名称、Banner、包名、配置目录和作者身份信息；依法必须保留的版权声明除外。

## 6. 关键重构点

### 6.1 拆分超大入口

最终 `Main.java`、`Agent.java` 和 `ToolRegistry.java` 体量较大。XhlCLI 不在迁移前进行全面重写，而是在模块进入对应阶段时把职责提取到稳定边界：命令解析、启动装配、Run 循环、工具定义、权限包装和渲染事件。

### 6.2 先行为一致，再局部优化

迁移顺序为：测试资产 → 最小实现 → 包名/配置适配 → Java 21 → XhlCLI 差异。不要同时进行大规模重命名、协议改造和功能新增。

### 6.3 保护用户已有变更

参考仓库当前工作树包含用户未提交修改，迁移时只从固定 Git 对象读取历史文件，或显式读取已确认的最终文件；不得重置、清理或覆盖参考仓库工作树。

## 7. 每期采用检查单

- [ ] 已固定参考提交和最终对照文件。
- [ ] 已阅读当期 PRD、参考文档、源码和测试。
- [ ] 已列出需要迁移与明确排除的文件。
- [ ] 已确认授权声明处理方式。
- [ ] 已先建立或迁移失败测试。
- [ ] 已迁移最小实现并完成包名/品牌/配置替换。
- [ ] 已保留阶段要求的验收证据；手工示例仅在 DoD 要求且未明确豁免时执行。
- [ ] 已记录相对参考实现的主动差异。
- [ ] 已同步 README、CHANGELOG 和阶段状态。
- [ ] 已提交真实、可解释的 Git 变更。

## 8. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-08-25 | 建立历史提交、模块、测试与 XhlCLI 阶段的采用地图 |
| v1.1 | 2026-08-27 | 固定 Phase 02 的参考 Git 对象、测试对照和 XhlCLI 主动差异 |
| v1.2 | 2026-08-29 | 固定 Phase 03 的参考 Git 对象、本地工具集与代码搜索采用记录 |
| v1.3 | 2026-08-30 | 固定 Phase 04 的参考 Git 对象、硬策略防护、HITL 审批与审计记录 |
