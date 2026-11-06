# 桌面定时任务验收

日期：2026-11-06。范围为[桌面需求中的定时任务增量](../prd/desktop-codex-ui.md#定时任务增量2026-11-06)，实施分工见[计划](../plans/2026-11-06-desktop-schedules.md)。本次完成本地代码、文档、测试及 Apple 芯片 Mac 应用包；未提交、推送或发布版本，保留工作区中其他会话的改动。

## 关键设计

- 调度层只负责时间和入队，实际执行、取消、恢复与结果保存复用 `DurableTaskManager`；每项目一个 worker，同一计划不并发。
- 桌面独立 `schedules.db` 保存每日计划、执行关联和原 task 表；推进日程与创建 task 在同一事务提交，自动执行的计划/时间组合唯一。
- 用户明确选择错过跳过：启动跳过逾期计划；正常运行每秒检查，超过 5 秒未检查视为中断并跳过逾期。暂停恢复、创建、编辑从未来开始，手动运行不改日程。已入队但未开始的 task 属于既有队列恢复，不是重新补建错过的计划。
- 每日时间与固定 IANA 时区一起保存，夏令时缺失时间顺延、重复时间仅执行一次。没有云调度或系统唤醒。
- 后台仅搜索和读取资料，不继承聊天自动审批，不开放写文件、Shell 或 MCP；进度和最终结果写库前脱敏。计划和结果不进入聊天历史。
- 界面沿用现有浅色体系，侧栏列表含新建、搜索和暂停分组，详情支持立即运行、暂停恢复、编辑、停止、确认删除及最近 100 次记录。

## 验收矩阵

| 条件 | 实际证据 | 结果 |
|---|---|---|
| AC-S01 保存与持久化 | Java 真实 DesktopService 集成；Electron 新建后退出重开，计划、模型、时间、记录保持 | 通过 |
| AC-S02 到点、去重、跳过 | 固定 Clock 验证 DST、跨日、短暂休眠和重启跳过；2026-11-06 13:59 UTC 真实时钟自动触发一次，`manual=false`、结果 `OK` | 通过 |
| AC-S03 暂停与手动 | 单测及真实 UI 暂停/恢复；立即运行前后 `nextRun` 不变；暂停计划仍可手动运行 | 通过 |
| AC-S04 task 与权限 | 真实流式运行后停止，状态 canceled、部分结果保留；测试模型请求 write_file 后任务 failed、项目未产生文件 | 通过 |
| AC-S05 输入与操作边界 | 未绑定项目、无效时区拒绝；无效编辑保持原计划；UI 错误后保留名称和输入；活跃任务不能重复运行或删除 | 通过 |
| AC-S06 页面 | 实际 Electron 空态、表单、列表、结果、错误、搜索结构和删除确认；1360×900 与 900×700 截图审查 | 通过（下述范围） |
| AC-S07 恢复与脱敏 | 失联 running 标失败而 enqueued 恢复；目录运行中失联保留取消入口；另一 Provider 虚拟密钥不出现在运行中 API 和取消后的库结果 | 通过 |

## 自动检查与审查

| 检查 | 命令或方式 | 结果 |
|---|---|---|
| Java 全量 | `./mvnw verify` | 656 项，0 失败、0 错误、0 跳过；JAR 构建成功 |
| 本功能回归 | `ScheduledTaskServiceTest` 8 项、`DesktopSchedulesTest` 2 项 | 包含在以上全量结果 |
| 桌面基础检查 | `cd desktop && npm test` | 7 项通过；其中桥接、运行时及其他会话的命令测试沿用已有入口 |
| React 构建 | `cd desktop && npm run build` | 通过 |
| 代码独立审查 | 只读源码与临时库复现、修复后复核 | 原三个 P1 和一个 P2 已修复，未发现新的阻断问题 |
| 视觉检查 | detector 一次结果 `[]`；独立 reviewer 打开四张最终截图 | `ship`；未作原截图逐像素一致性声明 |
| 视觉文档核对 | 独立 documenter 对比组件、截图与 DESIGN.md | 属于普通扩展，保留既有设计规范；选中态/错误色文案的既有漂移只报告、不扩大修复 |
| 应用打包 | `node scripts/prepare.mjs`；`npx electron-builder --mac dir --arm64 -c.directories.output=release/schedules` | 内置 Java 21，macOS arm64 `.app` 生成 |
| 应用签名 | `codesign --verify --deep --strict desktop/release/schedules/mac-arm64/XhlCLI.app` | 通过，本地 ad-hoc 签名 |
| 成品运行 | 实际 `.app`、隔离 home、无效系统 Java 配置 | `app.isPackaged=true`，内置 Java 启动，旧计划和结果可见，立即运行返回 `OK` |

审查期间补充红→绿回归：关闭读游标后取消；关闭游标后恢复队列，避免 SQLite 跨连接读锁；进度回调持久化前脱敏全部已知 Provider 凭据；目录运行中失联时保留任务所有权和取消入口。

UI 验收使用项目既有 `scripts/desktop_acceptance_fixture.py` 的本地流式模型，未使用真实账号。原生文件选择器打开已观察到，但自动化项目预置通过真实 `project.add` 协议完成；没有把该预置计为文件选择器端到端通过。真实时钟触发没有修改系统时间；重启跳过另通过修改隔离数据库的 `next_run` 模拟过去计划，并验证运行记录不增加。

本机原始日志位于 `/tmp/xhlcli-schedule-verify-final.log`、`/tmp/xhlcli-schedule-ui.log`、`/tmp/xhlcli-schedule-auto.log`、`/tmp/xhlcli-schedule-package.log`；测试配置和数据位于 `/tmp/xhlcli-schedules-qa-20261106`，不包含真实凭据。

## 界面证据

- [空态](assets/desktop-schedules/empty.png)
- [创建表单](assets/desktop-schedules/form.png)
- [任务详情与结果](assets/desktop-schedules/detail.png)
- [900×700 窄窗口与真实定时结果](assets/desktop-schedules/narrow.png)

截图来自本机实际 Electron 渲染，结果文案是本地测试模型输出，不能据此声称真实新闻搜索质量已验收。

## 人工验收

1. 打开新包，在原有项目配置一个可用模型。点击左侧时钟 → 新建任务，填写“每日 AI 新闻”、提示词“搜索过去 24 小时的 AI 新闻，整理 5 条，附来源链接和发布时间”，选择 `08:00` 与期望时区（例如 `Asia/Shanghai`）。核对保存后的下次时间。
2. 点击立即运行，检查来源链接是否真实、时间是否在约定范围、正文是否符合要求。执行中停止一次，确认最终为“已取消”，可以再次运行。暂停后确认计划进入已暂停分组；恢复后下次时间为未来。
3. 将计划改为接下来一两分钟，保持应用运行，确认只新增一次“定时运行”记录。随后将另一个计划设为接下来一两分钟，退出应用、等时间过去后重开，确认没有补跑且下次时间为未来。
4. 重启检查计划和历史；试一次无效时区，确认输入仍在；删除时先点保留，再确认删除。缩到最小窗口或收起侧栏，检查表单和结果可操作。

仅在本机 macOS Apple 芯片验证；没有验证其他硬件、真实休眠事件或真实新闻质量。休眠跳过逻辑由可控 Clock 的调度中断测试覆盖。应用未做公证，跨机器首次启动沿用现有分发说明。

## 本地产物

- 应用：`desktop/release/schedules/mac-arm64/XhlCLI.app`
- 压缩包：`desktop/release/schedules/XhlCLI-0.1.0-schedules-mac-arm64.zip`，包含应用、中文说明、Java 许可与对应官方源码。
- SHA-256：`eaa4e6c5c1e6a0006e943099937af6460fc38057b310dd6313c406903078f841`。

为避免覆盖其他会话的包，本次输出使用独立 `release/schedules/`。没有替换用户正在运行的已安装应用，也没有把其他会话修改提交到 Git。
