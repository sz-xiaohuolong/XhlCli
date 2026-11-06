# 审查验证记录

日期：2026-10-16，Asia/Shanghai。审查基线 `690a4e17578369c62eef04b5b426e994b8b1d153`，项目版本 `1.0.1`。本次未修改生产源码和原测试；已有用户 `AGENTS.md` 改动保留。

## 1. 基线测试

在 XhlCLI 根目录实际执行：

```bash
./mvnw test
```

结果：退出码 0，`BUILD SUCCESS`；525 项测试，失败 0、错误 0、跳过 0；耗时 16.160 秒，完成时间 `2026-10-16T16:57:54+08:00`。完整原始日志保存在证据索引指向的本机研究目录，末尾摘要见 [baseline-summary.log](baseline-summary.log)。

这次执行的是 `test`，没有把它写成重新运行 `clean package`、人工 CLI 验收或真实 Provider 验证。已有 fat JAR 仅用于离线探针的依赖；当前生产类优先从本次 Maven 编译的 `target/classes` 加载。

## 2. 定向离线复现

探针源码保存在本目录 `probes/`。它们是本次审查的复核材料，没有加入项目常规测试套件；修复时应将对应行为重建为正式回归测试。

| 探针 | 使用对象 | 复现结果 |
|---|---|---|
| `XhlReviewProbe.java` | 真实快照、SQLite manager、调度器 | R01 软链越界；R02 跨工作区领取；R03 活任务重新入队 |
| `XhlCancelProbe.java` | 真实 ReactAgent、manager；假模型 | R04 取消记录后仍发起模型请求 |
| `XhlCommandTimeoutProbe.java` | 真实 ExecuteCommandTool、调度器、临时 bash | R05 返回 TIMEOUT 后命令仍写文件 |
| `XhlAgentTimeoutProbe.java` | 真实 Agent、统一执行器、工具与默认线程池 | R05 超时命令和下一条串行命令发生重叠、迟到成功事件 |
| `XhlcliContextReview.java` | 真实 ChatLoop、摘要器；假 Agent/模型 | R06 手动 compact 清空；R07 摘要失败丢弃原始约束 |

并发复现通过闩锁或文件条件协调；命令循环的短 sleep 只用于检查放行文件，不以固定 sleep 推断线程已到某个阶段。每个探针只操作自建临时目录和数据库；shell 探针不访问网络或业务仓库。原始结果见 [review-probes.log](review-probes.log)、[context-probe.log](context-probe.log)。

### 重跑方法

先在项目根目录运行 `./mvnw test` 生成当前类。若没有 `target/xhlcli-1.0.1.jar`，可运行 `./mvnw package -DskipTests` 准备依赖包；本次未为此额外重跑打包。然后：

```bash
review_classes_dir=$(mktemp -d /tmp/xhlcli-review-classes.XXXXXX)
javac -encoding UTF-8 \
  -cp target/classes:target/xhlcli-1.0.1.jar \
  -d "$review_classes_dir" \
  docs/engineering/research-2026-10-16/probes/*.java
java -cp "$review_classes_dir":target/classes:target/xhlcli-1.0.1.jar XhlReviewProbe
java -cp "$review_classes_dir":target/classes:target/xhlcli-1.0.1.jar XhlCancelProbe
java -cp "$review_classes_dir":target/classes:target/xhlcli-1.0.1.jar XhlCommandTimeoutProbe
java -cp "$review_classes_dir":target/classes:target/xhlcli-1.0.1.jar XhlAgentTimeoutProbe
java -cp "$review_classes_dir":target/classes:target/xhlcli-1.0.1.jar XhlcliContextReview
```

Java 类名保留可编译标识符，解释和探针结果使用中文。

## 3. 发行物静态分析

REA MCP 未在本会话连接；按技能运行 `npx -y rea-agents@latest doctor --client codex --json`，实际工具版本为 `6.5.0`。诊断报告 Node 23.11.0 不在支持范围、原生引擎未配置及 MCP 注册缺失。本次没有修写配置或安装引擎；CLI JavaScript 分析实际执行并产生 Evidence，不能因 doctor 不健康就声称所有分析均不可用。

| 分析输入 | 实际结果 |
|---|---|
| Claude Code 2.1.301 安装树 | 可读包装器/安装器/声明分析；没有解析 Mach-O 核心 |
| Claude Code 2.1.19 完整历史 JS | 大文件 worker 内存不足，保留 incomplete 结果 |
| Claude Code 2.1.19 的 9 个精确函数切片 | 9 个 JS，8,476 AST 节点，0 parse failure |
| Qoder CLI 1.1.72 完整目录 | 两个大 bundle 内存不足，实际解析 2 个小 JS |
| Qoder CLI 1.1.72 的 9 个精确切片 | 9 个 JS，4,344 AST 节点，0 parse failure |

切片清单保留原始文件哈希、范围和派生文件哈希；本次还验证切片确实来自对应发行物。静态分析没有启动竞品、请求模型或证明全部动态路径可达。完整 Evidence、原始制品和来源材料保存在本机研究目录，详见 [evidence-index.json](evidence-index.json)。

## 4. 未覆盖范围

- 真实模型流式取消、计费、跨 Provider 长对话和摘要效果。
- 完整 CLI 手工交互竞争、非交互审批与所有终端平台。
- Windows/Linux 进程树终止和链接处理。
- 当前 Claude Code 原生控制流、Qoder IDE/新桌面端核心、服务端及 Wiki 独立组件内部。
- 未建议已经实施，也未验证任何未来产品路线的收益。

## 5. 文档与交付检查

交付前已完成以下检查：

- 按本文命令重新编译和运行 5 个探针，退出码均为 0，7 项问题得到一致复现；生产类从本次 `target/classes` 优先加载。
- 52 个持久原始证据文件存在，SHA-256 与索引一致。
- Claude 的 9 个切片与原始字节完全一致；Qoder 的 9 个切片、语法包装和哈希全部核对一致。
- 新增报告 14 条本地文件链接可定位，Markdown 代码围栏配对；未发现未完成段落。
- `git diff --check` 通过；已有 `AGENTS.md` 的中文规则改动保持原样，没有生产源码变更。

本次只更新研究入口与当前审查状态，不修改历史发布验证结论，不发布版本。Agent Reach 更新检查结果为 `v1.5.0`，工具报告已是最新版本。
