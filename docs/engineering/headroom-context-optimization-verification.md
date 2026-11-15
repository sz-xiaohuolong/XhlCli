# Headroom 工具正文压缩：使用与验收

2026-11-15，源码实现、独立审查、全量 Java 测试、真实 Headroom 与 CLI JAR 联调已通过。能力的产品默认值仍为关闭；用户随后授权本机配置与真实 API 对照，当前项目已开启，Headroom 已由 macOS 后台托管。真实 DeepSeek 三组对照及实际 Token 结果见[第 8 节](#8-本机自动配置与真实-deepseek-api-对照2026-11-15)。多任务回答质量、实际账单金额及本次桌面 GUI 操作尚未验证；已安装应用和分发包未更新。

需求：[REQ-H01～H05 / AC-H01～H11](../prd/headroom-context-optimization.md)；设计：[实现方案](../specs/2026-11-15-headroom-context-optimization.md)；任务：[H01～H06](../plans/2026-11-15-headroom-context-optimization.md)；当前交付状态与 Git 同步见 [PROGRESS](../PROGRESS.md)。

## 1. 解决的问题与实际流程

多轮命令、搜索和外部工具会产生很长的输出；Agent 后续每次请求都会携带这些历史，增加上下文用量，原历史超过预算时还会停止。本次在发送前尝试无损压缩可处理的工具正文，给原本超预算的请求一次缩短后继续的机会。

1. 用户启动本机 Headroom，通过项目 `.env` 开启，随后在 CLI 或桌面普通聊天中提交任务。
2. 模型要求调用工具，Agent 执行并保留原始观察结果。
3. 下一次请求模型前，Agent 复制历史，只抽取符合条件的工具 `summary` 正文。系统规则、用户消息、推理、图片、真实调用参数和 `data` 不发送给 Headroom。
4. Headroom 返回无损压缩候选。Java 校验消息结构及调用关联，只采用正文更短且本地 Token 估算减少的结果；服务失败或响应不合法就使用原文。
5. Agent 检查取消和运行超时，再用请求副本检查预算、加入选中的长期记忆、复核包含工具定义的总预算，交给原 Provider 客户端。Provider 是实际请求模型的服务商适配，原有流式输出与凭据配置继续生效。
6. 用户得到模型结果，通过 `/context` 查看最近压缩尝试。原历史不被替换；桌面只额外保存无正文的报告元数据。

Plan、Team、后台任务和 Runtime API 不在本次接入范围。无损压缩不能恢复先前已被工具字符上限截掉的内容，也不保证每份正文都能缩短。

## 2. 已执行的验证

本机使用 Corretto Java 21.0.8、Node.js 23.11.0、Python 3.14.7（验收脚本）、uv 0.10.11；Headroom 通过 uv 共享工具环境使用 Python 3.13 安装，无项目虚拟环境。Java 依赖未增加，复用 OkHttp 5.5.0、Jackson 2.22.2、JUnit 5.10.2。

上游源码固定为 `headroomlabs-ai/headroom@5023c84e527e28281dd73d1ba04184fe738aac0f`；安装包及 `/health` 均核实为 `0.44.0`。这是本次兼容性基线，不表示未来任意版本均兼容。

| 检查 | 实际结果与范围 |
|---|---|
| `XHLCLI_HEADROOM_TEST_URL=http://127.0.0.1:18787 ./mvnw test` | 731 项，0 失败、0 错误、0 跳过；包含真实服务用例 |
| `./mvnw package -DskipTests` | 成功生成可运行的 `target/xhlcli-1.0.1.jar` |
| `cd desktop && npm test` | 19 项通过；前端源代码之后未变，复用此有效结果 |
| `HeadroomContextOptimizerTest` | 34 项，覆盖正文、隐私字段、保护名单、无变化、结构破坏、错误、体积、重定向、重试、JSON 边界、高精度值及取消 |
| `ReactAgentHeadroomTest` | 7 项，覆盖预算前压缩、工具后续轮次、回退、工具定义预算、取消及整体超时 |
| `HeadroomSettingsTest` | 16 项，覆盖默认关闭、回环限制及配置校验 |
| `DesktopHeadroomTest` | 3 项，真实 Java 桌面服务轮次、模拟 HTTP；包括保存重启、旧聊天兼容、模型切换、关闭优先及非法配置 |
| `ChatLoopTest` | 整类 15 项通过，包含新增 `/context` 报告用例 |
| `HeadroomLiveTest` | 真实 Headroom + Java 优化器 + ReactAgent，模型为模拟实现；原历史超预算，压缩后继续，历史原文保留 |
| `python3 scripts/headroom_acceptance.py --headroom-url http://127.0.0.1:18787` | 真实 JAR 执行 `cat fixture.txt`，真实 Headroom、本地 SSE 模拟模型；项目 `.env` 开启、诊断、进程 false 优先、原文恢复、非法配置退出且不请求模型全部通过 |

真实服务样本为 40 行长路径搜索结果：原正文 3099 → 1539 字符；加实际命令执行前缀后 3122 → 1562 字符。Java Agent 样本消息 Token 估算 1346 → 723，40 条证据均保留，无 CCR 标记。CCR 是通过额外工具回取被移除原文的机制，本次不使用。重复 INFO 日志和另一份格式化 JSON 样本没有变化，因此不能推广固定压缩比例。

本节初次验收是指定样本的字符与本地 Token 估算，不是服务商账单。单次耗时受启动预热、机器和内容影响，不作为性能保证。本节模型由本地服务或函数模拟，没有请求真实 Provider；后续真实 API 验证单独记录于第 8 节。

### 审查中修复的问题

- HTTP 客户端可能自动重放请求：连接断开及 `503 Retry-After: 0` 均先复现，再用禁重试和一次性请求体修复；独立探针确认只发送一次。
- 未知 JSON 可能被当作旧纯文本发送，尾随内容可能被忽略：完整解析输入/响应，未知结构和整体非法 JSON 跳过，非法响应回退；新增 7 项用例先出现 6 项预期失败，再通过。
- 重写观察 JSON 可能损失高精度 `data`：测试先复现 `12345678901234567890.123456789` 被舍入，局部读取器改用 `BigDecimal`，顶层和嵌套负数均保持；共享 JSON 配置不变。只保证字段和值，不要求 JSON 空白、数字字面格式逐字节相同。

独立审查在修复后复核通过，无剩余 Critical / Important。原始日志暂存于本机 `/tmp/xhlcli-headroom-*.log`，可由上表命令和仓库测试重现；本文件保存可追溯的结果与边界，不依赖临时日志长期存在。

## 3. 验收条件对应

| AC | 证据 | 判断 |
|---|---|---|
| H01 | 配置测试、桌面关闭测试、真实 JAR 关闭场景 | 通过：默认关闭且不请求 Headroom |
| H02 | 配置 16 项、两入口测试、JAR 非法地址及进程优先场景 | 通过：中文报错且不回显输入凭据 |
| H03 | 优化器保护测试、精度测试、真实服务及 JAR 40 条证据 | 通过表列范围：只改变可接受正文，原历史保留 |
| H04 | 受保护工具、未知 JSON、消息破坏及 CCR 响应测试 | 通过：跳过或整体回退 |
| H05 | Agent 预算与内循环测试、真实服务超预算历史样本 | 通过：先压缩，再检查消息、记忆及工具定义预算 |
| H06 | HTTP 失败/超时/畸形/体积/禁重试测试、Agent 回退测试 | 通过：预算允许用原文，否则停止 |
| H07 | HTTP 取消、Agent 取消和运行超时测试 | 通过：压缩期间停止，不进入模型 |
| H08 | CLI 诊断单测与真实 JAR、桌面 Java 完整轮次 | 通过；本次未操作桌面 GUI |
| H09 | 桌面 Java 保存/重启/旧记录/模型切换测试 | 通过；真实 GUI 重启观察待人工执行 |
| H10 | 固定 0.44.0 服务、Java 集成和真实 JAR 验收 | 通过真实服务接口兼容性；真实模型未测 |
| H11 | JAR 开关关闭恢复原文；下列启动与人工步骤 | 自动化通过；桌面实际操作待人工执行 |

## 4. 启动条件与服务

CLI 需要 Java 21。桌面开发启动还需要 Node.js 22+；已经安装桌面依赖时复用，无需再次 `npm ci`。真实模型验收需要已有有效模型配置；下列离线脚本使用隔离临时工作区和模拟 SSE 服务，不读取真实 Key 或用户配置。

本机已经安装共享 Headroom 0.44.0；无需重复安装。在其他开发机首次准备时执行：

```bash
HEADROOM_OFFLINE=1 HEADROOM_BEACON=off DO_NOT_TRACK=1 \
uv tool install --python 3.13 'headroom-ai[proxy]==0.44.0'
```

本机现已按第 8 节配置后台服务，无需额外启动。以下命令供其他环境或撤销后台托管后手动运行，终端需保持开启。使用默认 8787；初次验收使用 18787：

```bash
HEADROOM_OFFLINE=1 HEADROOM_BEACON=off HEADROOM_DISABLE_KOMPRESS=1 DO_NOT_TRACK=1 \
/Users/daiyifei/.local/bin/headroom proxy \
  --port 8787 --lossless --no-cache --no-rate-limit --stateless \
  --no-subscription-tracking
```

`HEADROOM_OFFLINE` 禁止服务主动访问外部网络；其他参数关闭本次不需要的缓存、统计和状态保存。Java 每次请求仍显式设置 `mode=lossless`、`protect_recent=0`、`compress_user_messages=false`，不依赖全局服务默认值。启动日志或健康页中的其他通用代理字段不代表本次启用它们。

另一个终端执行 `curl -fsS http://127.0.0.1:8787/health`，预期 `ready=true`、`version=0.44.0`。初次 18787 验收服务已关闭；当前 8787 由后台服务提供，保留共享安装。

## 5. CLI 人工验收

### 5.1 先重现确定的离线结果

```bash
cd /Users/daiyifei/Documents/code/agent-cli/xhlcli
./mvnw package -DskipTests
python3 scripts/headroom_acceptance.py --headroom-url http://127.0.0.1:8787
```

预期两行“通过”：40 条证据保留且正文缩短；开启、诊断、关闭恢复、非法地址全部通过，最后明确模型为本地 SSE 模拟。脚本自动创建并清理测试工作区，不修改项目 `.env` 或真实用户配置。这组步骤已经由 Agent 实际执行，人工可重复。

### 5.2 用自己的真实模型验收（待用户执行）

先在第二个终端创建独立样本目录。模型继续使用已有全局配置，或在下面新生成的 `.env` 内填写自己的 Provider 配置，不覆盖原项目配置：

```bash
headroom_demo_dir="$(mktemp -d /tmp/xhlcli-headroom-manual.XXXXXX)"
python3 - "$headroom_demo_dir" <<'PY'
from pathlib import Path
import sys
root = Path(sys.argv[1])
root.joinpath('fixture.txt').write_text('\n'.join(
    f'src/very/long/package/path/Example.java:{i}: return HEADROOM_KEEP_EVIDENCE_{i};'
    for i in range(40)) + '\n', encoding='utf-8')
root.joinpath('.env').write_text('XHLCLI_HEADROOM_ENABLED=true\n'
    'XHLCLI_HEADROOM_URL=http://127.0.0.1:8787\n'
    'XHLCLI_HEADROOM_TIMEOUT_MS=3000\n', encoding='utf-8')
print(root)
PY
cd "$headroom_demo_dir"
java -jar /Users/daiyifei/Documents/code/agent-cli/xhlcli/target/xhlcli-1.0.1.jar --team-mode manual
```

1. 输入 `/context`。预期显示 Headroom 已开启、尚无优化记录。
2. 输入：“请只调用 execute_command 执行 cat fixture.txt，保留并核对 40 条 HEADROOM_KEEP_EVIDENCE，不要使用 read_file，不要再次调用工具，完成后报告条目数和首尾编号。”确认本次命令审批。
3. 预期工具输出包含 0～39 的全部条目；回答应基于 40 条证据。再输入 `/context`，预期最近尝试已压缩、条数为 1、估算下降，并提示不代表账单。若模型没有按指示执行该工具，不能用这轮判断压缩能力；先核对工具记录。任意真实模型的回答内容仍需人工核对。
4. 在服务终端按 `Ctrl+C` 停止服务，回 CLI 重复第 2 步。预算足够时预期正常回答，`/context` 显示已回退原文、连接失败；超预算停止属于预期保护，不代表回退失效。
5. 输入 `/exit`，在同一终端运行下列命令。即使服务关闭，预期仍能完成任务，`/context` 显示已关闭：

```bash
XHLCLI_HEADROOM_ENABLED=false \
java -jar /Users/daiyifei/Documents/code/agent-cli/xhlcli/target/xhlcli-1.0.1.jar --team-mode manual
```

进程变量优先于 `.env`；启用时 URL 只接受本机回环 HTTP/HTTPS 根地址，超时范围 100～30000 ms。关闭时不校验 URL 和超时。普通无工具聊天、短正文、读取源码或技能时可能显示跳过，这是正常结果。

## 6. 桌面人工验收（待用户执行）

确认第 4 或第 8 节的服务健康，保留刚才样本路径。在仓库根目录执行：

```bash
./mvnw package -DskipTests
cd desktop
# 仅首次缺少依赖时执行 npm ci
npm start
```

1. 打开刚才打印的临时项目路径，选择已配置的模型，在普通聊天发送第 5.2 节任务，批准命令。预期工具保留原输出，回答正常。
2. 输入 `/context`，预期当前模型正确，Headroom 已开启，最近尝试已压缩且估算下降。
3. 通过应用菜单彻底“退出 XhlCLI”，重新 `npm start`，打开同一聊天并输入 `/context`。预期报告仍在，原工具历史仍是完整输出。关闭窗口仅隐藏应用，不能据此认定完成重启。
4. 切换到另一个已配置模型，输入 `/context`，预期展示当前模型；无旧报告的聊天应显示尚无记录而非报错。
5. 将临时项目 `.env` 的开关改为 `false`，发起下一轮相同任务。预期继续完成，`/context` 显示已关闭。桌面每轮读取配置；如果启动进程设置了同名环境变量，应先清除它或彻底退出后以 `false` 重开。

这次桌面 Java 服务自动化已执行，真实 Electron 点击、真实模型质量和上述 GUI 重启由用户人工确认。请使用当前源码的开发启动，本机旧 `.app` 和分享 ZIP 不包含本次改动。

## 7. 关键设计与代码入口

| 入口 | 关键处理 |
|---|---|
| `context/HeadroomSettings.java` | 默认关闭、三项配置、回环和超时校验；错误不回显原输入 |
| `context/ContextOptimizer.java` | 统一接口、不可变结果与关闭实现；两入口共享装配 |
| `context/HeadroomContextOptimizer.java` | 抽取正文、合成调用、单次 HTTP、严格响应检查、精确数值及更短结果回填 |
| `agent/ReactAgent.java` | 预算前优化请求副本，压缩期间停止检查，随后沿用记忆和最终预算流程 |
| `context/ContextOptimizationReport.java`、`cli/ChatLoop.java` | 固定状态、原因与中文 `/context`，清空历史同时清报告 |
| `desktop/DesktopAgent.java`、`DesktopService.java`、`DesktopCommands.java` | 创建优化器，保存无正文报告，兼容旧记录并展示当前模型 |

上述路径位于 `src/main/java/com/xhlcli/`。选择独立压缩接口，是因为把 Provider 地址改成全量代理无法解决 Java 在发请求前就因预算拒绝的问题，也会让流式、推理、凭据和回取机制耦合。本次保留原 Provider 请求，只给它一份已校验的消息副本。

没有数据库迁移或压缩历史恢复操作。关闭开关即可恢复原路径；结束人工验收后在服务终端按 `Ctrl+C`，临时样本目录可按打印路径自行删除，共享 Headroom 安装保留供后续复用。

## 8. 本机自动配置与真实 DeepSeek API 对照（2026-11-15）

用户授权后，已将当前 xhlcli 项目 `.env` 的三项 Headroom 配置设为 `true`、`http://127.0.0.1:8787`、`3000`；其他配置保持，原文件备份在 `/Users/daiyifei/.xhlcli/backups/headroom/20261115T115419Z/project.env`。真实 CLI 已读取当前配置，`/context` 显示已开启，退出码为 0。

Headroom 交给 macOS 的 LaunchAgent 托管：`/Users/daiyifei/Library/LaunchAgents/com.xhlcli.headroom.plist`，名称 `com.xhlcli.headroom`，只监听 `127.0.0.1:8787`。配置为登录时启动、异常退出自动重启；当前运行状态和 `/health` 均已核实，未重启电脑验证登录过程。工作目录为 `/Users/daiyifei/.local/share/xhlcli/headroom`，日志位于 `/Users/daiyifei/Library/Logs/XhlCLI/headroom.stdout.log` 和 `headroom.stderr.log`。沿用无损、无缓存、无状态、离线、关闭遥测及订阅统计的参数，不再需要保留一个服务终端。

### 测试怎样保证可比

沿用现有 DeepSeek 凭据，官方 HTTPS `/models` 确认 `deepseek-flash` 可用。对照执行真实 `ExecuteCommandTool` 的 `cat fixture.txt`，取得 40 条合成搜索结果；三组均使用真实 `ReactAgent`、现有 `DeepSeekClient` 和 SSE 流式解析，开启组额外经过真实本机 Headroom 与 Java 压缩器。

为排除模型自主选工具和不同历史造成的干扰，三组恢复相同的工具调用历史，工具已实际执行，测试只让模型读取观察并返回全部编号、数量和总和。相同工具定义、系统和用户消息、模型及参数，逐字段核对后唯一差异是工具观察的 `summary`。空正文组也保留工具消息和所有控制字段，用于测出不包含工具正文时的请求用量。

测试固定非思考模式、温度 0、最多输出 256 Token，并要求返回 usage。测试拦截器只统一这些请求参数及采集响应，真实请求仍由现有客户端发送；没有使用模型替身。每组恰好一次真实 API 请求，总计三次；凭据未进入请求正文、结果文件或版本控制。

### API 实际返回的结果

| 指标 | 关闭压缩 | 开启压缩 | 空工具正文对照 |
|---|---:|---:|---:|
| 工具正文字符 | 3122 | 1562 | 0 |
| API `prompt_tokens` | 1321 | 814 | 190 |
| API `completion_tokens` | 91 | 91 | 13 |
| API 缓存命中 Token | 0 | 0 | 0 |
| 模型识别的编号 | 完整 0～39 | 完整 0～39 | 空数组 |
| 模型数量 / 总和 | 40 / 780 | 40 / 780 | 0 / 0 |

请求输入实际减少 **507 Token（38.38%）**。用空正文组 190 Token 作对照，工具正文带来的输入增量为 `1321−190=1131` → `814−190=624`，减少 **44.83%**。这是三组 API 总输入之差推导的正文增量，不是服务商提供的单条工具 Token 字段。

两组均保留全部 40 条证据，模型返回全部正确编号；Agent 原始历史不变，三组运行均为 `COMPLETED`。本地估算 1427 → 804，与服务商实际 1321 → 814 不同，结论采用服务商 `usage`。缓存命中均为 0，本次减少来自工具正文变化。三组累计使用 2520 个输入及输出 Token；未核对账户扣款或计算金额。

原始用量、响应 ID、答案、优化报告及三组不含凭据的请求正文保存在 [JSON 实测记录](headroom-real-api-2026-11-15.json)。本次只证明这个 40 行搜索结果样本在该模型上确实减少输入 Token，并保持这项核对任务正确；不推导所有工具、文本、模型的固定压缩比例或普遍回答质量。

### 以后如何一条命令重测

本机服务运行、项目开关开启、Java 21 和当前 JAR 可用时，在仓库根目录执行：

```bash
python3 scripts/headroom_real_api_test.py
```

脚本会使用现有配置发起三次真实付费 API 请求，打印用量并保存无凭据报告。不修改真实历史或用户凭据；样本与记忆目录单独创建。`--project <目录>` 可指定项目，`--output <目录>` 可指定结果位置。只检查 Java 探针编译且不请求模型时运行 `python3 scripts/headroom_real_api_test.py --compile-only`。探针源码见 `scripts/headroom/HeadroomRealApiProbe.java`；本次先实际执行同一份源码，保存进仓库后核对相同，并验证运行入口编译通过，没有为验证入口重复消耗模型额度。

### 关闭及恢复

只关当前项目功能，将 `.env` 的 `XHLCLI_HEADROOM_ENABLED` 改为 `false`；CLI 重启、桌面下一轮生效。原 `.env` 备份含模型凭据，只用于本机恢复，不提交。

同时停止后台服务并取消下次登录启动：

```bash
launchctl bootout gui/501 /Users/daiyifei/Library/LaunchAgents/com.xhlcli.headroom.plist
mv /Users/daiyifei/Library/LaunchAgents/com.xhlcli.headroom.plist /Users/daiyifei/.local/share/xhlcli/headroom/com.xhlcli.headroom.plist.disabled
```

恢复托管时将该文件移回原处，再执行 `launchctl bootstrap gui/501 /Users/daiyifei/Library/LaunchAgents/com.xhlcli.headroom.plist`。这些是本机用户 501 的命令。当前按用户本次开启要求保留服务运行；没有重新安装、打包或替换旧桌面应用。
