# XhlCLI 桌面 MVP

Electron/React 界面通过私有 stdio 调用仓库现有 Java Agent。参考界面来自用户提供的 Codex 主聊天和设置截图；名称、模型能力与执行引擎保持 XhlCLI。

## 给朋友使用

Apple 芯片 Mac 的分发包：`release/XhlCLI-0.1.0-mac-arm64.zip`。解压后把 `XhlCLI.app` 拖入“应用程序”，双击打开，在设置页填写朋友自己的 API Key 并测试连接。无需安装 Java、Node.js 或使用终端；压缩包附有[快速开始说明](distribution/快速开始.txt)，不包含开发者的配置、密钥或聊天。

支持范围声明为 macOS 13+ / arm64，当前实测 macOS 26.6.2。测试包使用本地 ad-hoc 签名，没有 Developer ID 身份签名、公证或自动更新；首次打开可能需要前往“系统设置 → 隐私与安全性 → 仍要打开”。单位管控设备可能禁止此操作。Intel、Windows 和旧版 macOS 未实机验收。特定项目的编译或运行仍可能需要该项目自身的工具依赖。

## 开发启动与构建分发包

构建机需要 Apple 芯片 Mac、Java 21、Node.js 22 或更新版本；这些是开发依赖，收件人不需要安装。

```bash
./mvnw verify
cd desktop
npm ci
npm start
```

开发模式通过 `/usr/libexec/java_home -v 21` 定位 Java，或使用 `XHLCLI_JAVA` 指定的可执行文件。打包模式固定使用包内运行环境，忽略系统 Java 配置；运行环境不完整时提示重新解压。

```bash
# 在仓库根目录先构建最新 Java JAR
./mvnw verify
cd desktop
npm run distribute
```

日常交付只保留两类产物：本机使用的 `release/bundled/mac-arm64/XhlCLI.app`，以及分享用的 `release/XhlCLI-<版本>-mac-arm64.zip`（附 `.sha256` 校验文件）。每次功能更新统一替换最新版，不再创建 skills、schedules 等功能专属副本。

`npm run package` 更新本机应用；`npm run distribute` 再生成同版 ZIP，成功后删除旧版本 ZIP。用于打包的临时解压目录无论成功或失败都会清理，不保留额外 `.app`。更新前先退出正在运行的本机应用。`scripts/runtime-lock.json` 固定 Eclipse Temurin 21.0.13+9 的 macOS aarch64 JRE、源码下载地址及 SHA-256；首次构建下载，后续校验缓存。运行环境保持官方签名，外层应用进行 ad-hoc 签名并通过完整性校验。

压缩包附带 Java 的许可文件、官方对应版本源码及中文说明。仅复制明确列出的应用资源；下载缓存、用户 home、项目和验收数据不进入包。生成物位于 Git 忽略目录，不创建 Tag 或 Release。

## 使用

1. 打开项目，在设置中填写所用 Provider 的模型、服务地址和 API Key，保存并测试连接。
2. 在输入框描述任务。Enter 发送，Shift + Enter 换行；可添加文件、拖入文件或粘贴 PNG。
3. 点击输入框下方的审批模式，在“按需审批”和“帮我审批”之间切换。默认按需允许本次或拒绝；帮我审批会自动批准当前聊天的工具调用（含命令执行），并继续当前等待中的审批。执行期间仍可停止，工具详情保留真实参数、结果及命令输出。
4. 对产生文件修改的轮次查看变更，必要时通过原生确认撤销。后来修改同一文件会拒绝撤销；撤销期间请避免其他程序同时写这些文件。
5. 通过聊天菜单重命名、置顶、归档；左侧归档页可恢复。关闭窗口只是隐藏，应用菜单“退出 XhlCLI”才结束进程。

审批模式按聊天保存，退出重开仍生效；新聊天默认按需审批。未发送的新聊天切换模式保留草稿与附件，发送时才保存会话。切回按需审批后，后续需审批的操作重新等待确认；已批准或已执行的操作不会撤回。自动审批不关闭底层路径与命令硬策略，已取消的任务不会因模式切换恢复。

同一真实工作目录只允许一个任务执行。普通文本附件不会展开 `@image` 或 `@clipboard`；图片需要通过附件入口显式选择。单轮最多 8 个附件，文本最大 256 KB、图片最大 10 MB。图片在发送前校验模型视觉能力，历史图片沿用现有引擎的裁剪规则。

模型下拉框只列出有效配置的模型；自定义模型以保存的名称显示。Ollama 需明确保存模型或配置服务地址后才会出现，无配置时提示进入设置。

配置沿用 `~/.xhlcli/config.json`，优先级为进程环境变量 > 项目 `.env` > 全局配置。设置页面不返回保存的密钥；空密钥输入保留原值。会话与图片存于 `~/.xhlcli/desktop/`，快照使用现有 `~/.xhlcli/snapshots/`。快照沿用排除规则，额外排除 `.env`、`.env.*`、`.xhlcli` 和桌面发布目录；这些排除路径不能依赖本轮撤销恢复。

## 斜杠指令与升级续聊

输入 `/` 打开候选菜单；继续输入筛选，上下键选择，Enter 或 Tab 补全，Escape 收起，补齐参数后发送。支持子命令补全与 `/技能名 任务`。

| 指令 | 用法与作用 |
|---|---|
| `/plan <目标>` | 生成计划，按当前审批模式确认后执行；过程可展开查看 |
| `/team <目标>` | 调用既有规划、执行和审查团队，结果可继续追问 |
| `/mcp` | `list/status/tools/resources/read/start/stop/restart/logs/reload`；如 `/mcp status 服务名`、`/mcp read 资源URI` |
| `/skill` | `list/show/enable/disable/reload/run`；如 `/skill run 技能名 任务`，也可直接 `/技能名 任务` |
| `/memory` | `list/search/recall/delete/clear`；`clear` 清空全局与当前项目长期记忆，保留聊天 |
| `/save [--global] <内容>` | 保存项目或全局长期记忆；聊天本身自动保存，无需执行此命令 |
| `/model` | `list/status/use`；如 `/model use deepseek:deepseek-chat`，仅可选已配置模型 |
| `/context` | 查看历史消息数量、估算 Token、模型和工作目录 |
| `/help` | 查看上述指令帮助 |

本地管理指令不依赖模型配置；Plan、Team 和技能执行需要模型。未知或尚未接入的命令直接报错。斜杠指令暂不接受附件，发送失败时保留草稿与附件。

MCP 启停在本次应用运行期间对同一项目生效；重开应用按 MCP 配置初始化。技能开关沿用桌面技能设置。模型和审批模式按聊天保存。

会话、显示记录与模型上下文保存在 `~/.xhlcli/desktop/state.json`，图片位于同一用户数据目录；它们不在 `.app` 内。保留 `~/.xhlcli/` 和原项目路径，替换应用后可从侧栏打开原聊天继续。本次已验证旧版写入、新版读取并携带旧上下文续聊；这不代表未来任意数据格式变更均无需迁移。退出时未完成的任务会标记中断，不自动重跑。终端 CLI 的输入历史与桌面完整聊天存储并非同一个恢复入口。

## 输入框与推理强度

点击输入框左下的审批模式，在弹出菜单中查看两种模式的说明并切换；收起时只保留当前模式名称。模型选择使用右下圆角按钮，点击打开推理面板，再点击模型名称切换已配置模型。切换不会清空草稿。

推理强度可通过滑块、档位按钮或键盘调整，右上重置按钮恢复提供方默认。设置按聊天保存，重启保留，从下一轮任务生效；切换模型会恢复默认强度。DeepSeek Flash / V4 Flash / V4 Pro 开放关闭、低、高、最高；已接入的 OpenAI 推理模型开放低、中、高。仅显示后端明确支持的档位，未接入的模型显示说明；不会把未知模型的能力当作已支持。

“默认”不额外发送强度参数，遵循提供方默认。DeepSeek 强度通过 `thinking` 与 `reasoning_effort` 传递，工具续轮所需的推理上下文作为内部历史保存，不显示到聊天正文。真实模型的响应时间和用量由提供方决定。

## 技能管理

点击左侧功能栏的“技能”，可按名称或描述搜索，筛选个人、项目、内置来源，并用每行右侧的开关启用或停用。项目选择器控制当前查看的技能版本；同名技能优先项目，其次个人，最后内置。

开关按技能名称保存在 `~/.xhlcli/desktop/skills.json`，适用于此设备的桌面聊天，重启后保留。下一轮任务会重新读取开关：停用技能不再进入可用索引，`load_skill` 也会拒绝加载；运行中任务和已存在的聊天内容保持原样。该桌面设置不改变独立 CLI 的内存开关。

个人技能放在 `~/.xhlcli/skills/<目录名>/SKILL.md`，项目技能放在 `<项目>/.xhlcli/skills/<目录名>/SKILL.md`。页面的“打开技能文件夹”会打开个人目录，放入文件夹后点击刷新。损坏技能显示原因并禁止启用；保存失败时开关保持原值，不删除技能文件。

## 私有协议

后端入口：

```bash
java -cp target/xhlcli-1.0.1.jar com.xhlcli.desktop.DesktopMain
```

每行一个 JSON，请求 `{ "id": 1, "method": "state", "params": {} }`；响应为相同 `id` 的 `result` 或 `error`。事件为 `{ "event": "TextDelta", "sessionId": "…", "turnId": "…", "data": {} }`。stdout 仅发送协议，既有工具诊断转 stderr；不监听网络端口。

| 方法 | 主要参数 |
|---|---|
| `state` | 无；返回项目、会话与显示事件，隐藏模型内部历史 |
| `skills.list` / `skills.set` | 可选 `projectId/sessionId`；设置另需 `name` 与布尔 `enabled`，返回当前有效技能列表 |
| `project.add` | `path` |
| `session.create` | `projectId`、可选 `model`（`provider:model`）、`approvalMode`（默认 `on-request`，可选 `auto`） |
| `session.update` | `sessionId` 与 `title/model/pinned/archived/approvalMode` |
| `turn.start` | `sessionId`、`input`、`attachments: [{path,type}]` |
| `turn.cancel` | `sessionId` |
| `approval.respond` | `sessionId`、`approvalId`、`allow` |
| `changes` / `undo` | `sessionId`、`turnId`；UI 的 undo 先经过原生确认 |
| `commands.list` | 可选 `sessionId` 或 `projectId`；返回 `command/description/usage/requiresModel` 列表 |
| `config.get` | 可选 `sessionId` 或 `projectId` |
| `config.save` | 上述上下文与 `provider/model/baseUrl/apiKey` |
| `config.test` | 上述上下文与已保存的 `model` |

`approvalMode` 只接受 `on-request` 或 `auto`，非法值拒绝且不修改设置；旧会话缺失或未知值恢复为 `on-request`。保存成功后才继续等待中的审批，保存失败保留原模式；自动批准产生 `approval.automatic` 事件（含 `id/request`），原人工卡片随后收到 `approval.resolved`。

命令经 `turn.start` 执行，结果与普通轮次一起保存。`command.progress` 事件的 `data.text` 提供计划/团队过程，`session.model` 的 `data.model` 同步模型选择器；计划以 `plan.execute` 使用现有审批协议。

协议只供本桌面使用。渲染进程禁用 Node 集成，通过有限预加载接口调用；主进程检查来源、主帧与方法白名单，并限制导航、权限和外部打开。

## 开发验证

```bash
npm test
npm run build
```

Java 回归在仓库根目录执行 `./mvnw verify`。不使用真实账号的完整验收可运行：

```bash
python3 scripts/desktop_acceptance_fixture.py --root /tmp/xhlcli-desktop-demo --port 18791
```

该脚本要求目录不存在，只创建隔离 home、project 与本地 HTTP 测试模型。另一个终端进入 `desktop/`：

```bash
env -u DEEPSEEK_API_KEY -u DEEPSEEK_BASE_URL \
  XHLCLI_DESKTOP_TEST_HOME=/tmp/xhlcli-desktop-demo/home npm start
```

打开 `/tmp/xhlcli-desktop-demo/project`，发送“我的标记是 maple”“验收写入”“验收命令”“验收停止”“验收续聊”。模型响应为测试脚本生成，文件操作、审批、取消、历史和快照走真实引擎。结束后退出测试应用并停止脚本。完整证据及人工步骤见[验收记录](../docs/engineering/desktop-mvp-verification.md)。


## 定时任务

左侧时钟图标 → 新建任务，填写名称和任务内容，选择已添加的项目、模型、每日时间和时区。例如每天 `08:00` 搜索过去 24 小时的 AI 新闻，整理 5 条并附来源链接；时区保存后固定。保存后可立即运行以检查结果，详情支持暂停/恢复、编辑、停止执行、确认删除及最近 100 次记录。

应用运行时才调度；关闭窗口只是隐藏，菜单退出才停止。电脑休眠或退出错过的计划会跳过，恢复后等待下一次计划时间，不补跑。暂停不取消正在执行的任务；手动运行不改变下一次计划。后台可搜索网页、读取文件，但不能写文件、执行 Shell 或 MCP，也不继承聊天的“帮我审批”。实际新闻质量与来源取决于配置模型和联网工具。

| 私有协议 | 参数 / 结果 |
|---|---|
| `schedules.list` | 无；返回 `schedules`、本机 `zone`、调度 `error` |
| `schedules.save` | 可选 `id`；`title/prompt/projectId/model/time/zone`，返回计划 |
| `schedules.runs` | `id`；返回最近 100 次状态、时间、结果和错误 |
| `schedules.toggle` | `id`、布尔 `enabled` |
| `schedules.run` | `id`；立即提交一次 task |
| `schedules.cancel` | `id/taskId`；停止该次执行 |
| `schedules.delete` | `id`；删除计划和执行记录，活跃时拒绝 |

数据保存在 `~/.xhlcli/desktop/schedules.db`，与 CLI task 库和聊天历史分开。实现、验证范围及人工检查见[定时任务验收](../docs/engineering/desktop-schedules-verification.md)。
