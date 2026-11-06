# Phase 19：Team 自动选择与重规划验收

验证状态：`VERIFIED`（离线功能验收）。日期：2026-11-06。真实付费模型的决策质量未验证；本次未发布新版本，pom.xml 仍为 1.0.1。

需求：[Phase 19](../prd/phase-19-team-routing-and-replanning.md)；[方案](../specs/2026-11-06-phase-19-team-routing-and-replanning-design.md)；[计划](../plans/2026-11-06-phase-19-team-routing-and-replanning.md)。当前实施进度只在 [PROGRESS](../PROGRESS.md) 维护。

## 1. 用户现在能完成什么

开启 auto 后直接输入任务，模型会选择普通执行或 Team。Team 指规划者、执行者、审查者分工完成一个目标的协作模式。用户仍可 `/team <目标>` 手动指定，或用 `/team-mode manual` 关闭自动选择。

步骤首次执行失败后最多修正两次。修正耗尽时，编排器根据审查缺陷、工具记录与文件变化请求规划者判断是否换方案。完成步骤比例低于 50% 时可调整剩余计划；达到 50% 后只调整失败步骤及后继。整个任务最多换两次计划，已完成步骤不会重跑，已有文件不会自动回滚。取消、权限拒绝、执行/审查服务异常不能借助重规划继续尝试。

Team 的最终结果可在当前会话继续追问。退出后可查看安全输入历史；完整回复、Team 计划和执行状态不跨进程恢复。本次修复了验收时发现的输入历史初始化过早、重复追加及长历史索引问题。v1.1 的快照安全、后台任务租约和底层强制停止修复仍是独立规划。

## 2. 实测结果与证据

环境：Java Corretto 21.0.8、Maven Wrapper 3.9.9、Python 3；使用仓库现有依赖，无新增运行时依赖。构建前基线为 `690a4e17578369c62eef04b5b426e994b8b1d153`。

| 验证 | 实际结果 |
|---|---|
| `./mvnw test package` | 退出码 0；553 项，失败 0、错误 0、跳过 0；BUILD SUCCESS，17.458 秒 |
| `java -jar target/xhlcli-1.0.1.jar --help` | 退出码 0，包含 `--team-mode <manual|auto>` |
| `java -jar target/xhlcli-1.0.1.jar --version` | 退出码 0，`XhlCLI 1.0.1` |
| `python3 scripts/team_acceptance_demo.py` | 退出码 0；启动真实 JAR 两次，所有验收断言通过 |
| 独立代码审查 | 混合权限失败、无效依赖、只换编号、证据截断、遗漏审查原因等问题已修复并经回归覆盖 |
| 文档与差异 | 修改范围内相对链接、`git diff --check` 和敏感内容检查通过；敏感扫描命中仅为原有测试假令牌 |

本机完整构建日志在 `/tmp/xhlcli-phase19-verified-build.log`，终端验收摘要在 `/tmp/xhlcli-phase19-cli-demo.log`；临时日志不作为仓库必需文件。可重建证据为正式测试与 [验收脚本](../../scripts/team_acceptance_demo.py)。原始 JAR 指纹（SHA-256，即根据文件内容计算的校验值）：

```text
e17341e4b8312eb50f18bebb08b6873e1d3a8b8929bba73c021188f1038bc0d9
```

本次离线终端请求统计：分类 2 次、初始规划 2 次、重规划 1 次、执行 5 次、审查 5 次、普通对话 2 次。重启读取历史没有模型请求。脚本用临时用户目录、工作区及数据库，全部模型请求发到本机假服务，无真实 API 消费。

### 先失败、再修复的记录

- 初始路由、配置和重规划用例证明旧实现缺少这些行为。
- 补审查回归时五项预期失败：审查非法响应仍重试、同批拒绝可被换图、非法依赖被丢弃、只改无关任务仍重试、长自述挤掉证据。
- 进一步复现只换节点 ID 获得新重试；增加忽略编号、比较目标和依赖结构的校验后通过。
- 真实终端复现历史文件有内容但重启不显示、旧输入重复追加；修复加载时机和增量追加。1000 条历史复现索引为 -20，修复绝对索引后通过。
- 发现过编排器外层锁与并发步骤状态更新互相等待；移除外层锁并用独立运行标志防止重入，并发回归通过。

### 验收映射

| 验收项 | 正式证据与覆盖 |
|---|---|
| AC-19-01～02 | [ChatLoopTest](../../src/test/java/com/xhlcli/cli/ChatLoopTest.java)、[ChatConfigLoaderTest](../../src/test/java/com/xhlcli/config/ChatConfigLoaderTest.java)：手动兼容、自动两种模式、每轮执行一次、会话关闭与配置优先级 |
| AC-19-03 | [TeamRouterTest](../../src/test/java/com/xhlcli/team/TeamRouterTest.java)、ChatLoopTest：非法响应/网络失败回退、取消不启动执行器、分类无工具 |
| AC-19-04～06 | [TeamReplanningTest](../../src/test/java/com/xhlcli/team/TeamReplanningTest.java)：成果保留、前后半程边界、两次上限、STOP、非法图、重复 ID、循环、重命名图与无关变更 |
| AC-19-07 | TeamReplanningTest：权限拒绝及混合失败、审查异常、取消返回后不启动新图、旧并发批次必须先结束 |
| AC-19-08 | TeamReplanningTest：真实写文件留下的中间结果、文件指纹、长输出仍保留审查与文件证据、超过文件变化证据上限停止 |
| AC-19-09 | TeamRouterTest、ChatLoopTest：当前约束交接、真实 ReactAgent 接收 Team 结果、清空会话移除结果 |
| AC-19-10 | 真实 JAR 帮助与版本、离线终端脚本、[SafeHistoryTest](../../src/test/java/com/xhlcli/cli/terminal/SafeHistoryTest.java)：重启显示输入、增量追加、1000 条恢复后最近20条正确 |

## 3. 人工验收步骤

### 前置条件与启动

安装 Java 21 或以上版本；联网构建时可获取 Maven 依赖。真实模型体验需要按 [README](../../README.md) 配置可用的模型凭据；下面的离线验收只需额外安装 Python 3，不需要凭据。入口是终端，没有网页聊天页面。

```bash
cd /Users/daiyifei/Documents/code/agent-cli/xhlcli
./mvnw package -DskipTests
java -jar target/xhlcli-1.0.1.jar --help
java -jar target/xhlcli-1.0.1.jar --team-mode auto
```

在其他机器上将第一行换为实际仓库目录；工作区就是启动时所在目录。自动选择每轮增加一次模型请求。

### 真实模型交互

| 顺序 | 具体操作 | 预期结果 |
|---|---|---|
| 1 | 输入 `/team-mode` | 显示 `team-mode=auto` |
| 2 | 输入“用一句话解释 README 的作用” | 显示模式判断及原因，通常为普通模式；任务只执行一次 |
| 3 | 输入“请只读分析当前项目，分别检查配置模块、团队调度模块和测试缺口，最后汇总” | 模型判断是否进入 Team；选 Team 时展示规划、执行、审查、最终看板 |
| 4 | 输入 `/team-mode manual`，再输入“继续解释刚才的结论” | 显示 manual；普通输入不再先调用分类器，仍能获得本会话的 Team 总结 |
| 5 | 输入 `/team 只读分析 README 并给出一条改进建议` | 不经过分类，直接进入 Team；manual 不会禁用显式命令 |
| 6 | 输入 `/exit`，用同一启动命令重开，输入 `/history list 20` | 列出安全输入；不重播完整回答，不恢复运行中的 Team |
| 7 | 重开后输入 `/team-mode` | 因启动参数仍为 auto，显示 auto；上次会话内 manual 没有写全局配置 |

模型分类结果有随机性；第 3 步未必固定选择 Team。下面的离线脚本用于确定性验收自动 Team 和失败重规划。

### 可复现的失败重规划验收

退出交互式程序后运行：

```bash
python3 scripts/team_acceptance_demo.py
```

脚本自动执行普通问候、自动 Team、故意失败三次、换为适配方案并通过、关闭自动选择、追问成果、手动 Team、退出重启并查历史。预期打印两行“通过”及上述请求统计，退出码为 0。它验证真实命令到执行流程的连接，不评价真实模型的方案质量。

需要单独检查权限、取消、后半程限制和长历史时：

```bash
./mvnw -Dtest=TeamReplanningTest,TeamRouterTest,ChatLoopTest,SafeHistoryTest test
```

预期全绿。不要依靠让真实模型随机失败来证明这些边界。

## 4. 一次输入从发送到重新打开的完整路径

1. **读取输入。** `JLineTerminalSession` 是终端输入适配器；首次读取前绑定 `SafeHistory` 并加载安全输入文件。每次有效输入增量写入 `~/.xhlcli/history`，疑似凭据等内容按原规则过滤。保存输入不表示任务成功。
2. **解析与选择。** `ChatCommandParser` 区分命令和自然语言。`ChatLoop` 处理 `/team-mode` 和 `/team`；普通消息在 auto 下交给 `TeamRouter`。路由器即“执行模式选择器”，无工具权限，只根据当前请求、必要约束和最近正文返回模式及理由。分类失败回退普通执行，用户取消就结束。
3. **建立执行边界。** `ChatLoop` 为选择与执行共用一个取消令牌，即传递“用户要求停止”信号的对象；确定模式后建立已有的执行前快照。普通输入交给 `ReactAgent`，也就是按思考、调用工具、观察结果循环工作的单 Agent。
4. **团队分工。** `TeamOrchestrator` 是调度负责人。Planner（规划者）生成步骤与依赖；Worker（执行者）借用现有工具完成步骤；Reviewer（审查者）检查结果和工具证据。依赖图 DAG 指“没有循环依赖的任务图”，用于决定先后顺序及哪些步骤可以并行。
5. **修正与换计划。** 步骤先做有限修正。耗尽后等待旧批次结束，核对权限与证据，再评估是否换计划。`TeamWorkspaceSnapshot` 提供真实文件变化指纹；`TeamPlanValidator` 验证依赖、成果保留与调整范围。新图无效、证据不完整或任何不可恢复失败都会停止换图。
6. **显示与追问。** Team 输出最终看板与重规划原因，`ChatLoop` 将结果通过 `ReactAgent.recordExternalTurn()` 写入进程内对话，下一轮能追问。结束后沿用已有异步执行后快照；快照是文件变化恢复机制，不是聊天消息数据库。
7. **退出后重新打开。** 新进程重新读取启动配置与安全输入历史，`/history` 可显示保存的输入。完整回复、运行状态与 Team 计划不会自动重建。已有项目记忆可保存总结，但不等同于完整会话恢复；因此这里没有“刷新网页后整段聊天重新显示”的能力。

## 5. 本次边界与远程同步

自动 Team 只复用现有团队架构，没有增加任意递归创建子 Agent。50% 是步骤数量比例，不代表耗时。Reviewer 仍是无工具的模型审查，结构检查不能证明新方案一定覆盖所有业务要求；真实模型效果仍需要实际任务评估。

文件证据最多扫描 20000 个文件、256 MB、5 秒，变化最多 100 项；目录排除规则见实现。证据超限时停止自动重规划，保留已有文件。取消检查避免启动新图，但不声称本次修复了 v1.1 中所有底层任务强制停止问题。

功能与文档提交 `2cbd7e0c13b13d7ab8cc5c861896c20b956030e8` 已于 2026-11-06 推送到 `origin/main`。`git ls-remote origin refs/heads/main` 返回同一 SHA，远程核对通过。本记录随后以文档提交补录；不打 Tag、不发布新版本。
