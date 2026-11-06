# XhlCLI 桌面 MVP

Electron/React 界面通过私有 stdio 调用仓库现有 Java Agent。参考界面来自用户提供的 Codex 主聊天和设置截图；名称、模型能力与执行引擎保持 XhlCLI。

## 启动与本机打包

需要 macOS、Java 21、Node.js 22 或更新版本。当前实测 macOS 26.6.2 / Apple Silicon；Intel 和其他系统未验收。

在仓库根目录构建 Java，再进入桌面目录：

```bash
./mvnw verify
cd desktop
npm ci
npm start
```

`npm start` 先构建前端，再启动 Electron；首次安装需要下载 Electron 运行时。主进程优先通过 `/usr/libexec/java_home -v 21` 定位 Java，必要时设置 `XHLCLI_JAVA` 为 Java 可执行文件绝对路径。

本机 `.app` 构建：

```bash
npm run package
```

Apple Silicon 输出 `release/mac-arm64/XhlCLI.app`，可双击打开。包内包含 Java JAR，**仍需机器已安装 Java 21**。这是未签名的本机 MVP，没有公证、自动更新或公开安装包发布。构建产物不进入 Git。

## 使用

1. 打开项目，在设置中填写所用 Provider 的模型、服务地址和 API Key，保存并测试连接。
2. 在输入框描述任务。Enter 发送，Shift + Enter 换行；可添加文件、拖入文件或粘贴 PNG。
3. 对需要权限的工具允许本次或拒绝；执行期间可停止。工具详情显示真实参数、结果及命令输出。
4. 对产生文件修改的轮次查看变更，必要时通过原生确认撤销。后来修改同一文件会拒绝撤销；撤销期间请避免其他程序同时写这些文件。
5. 通过聊天菜单重命名、置顶、归档；左侧归档页可恢复。关闭窗口只是隐藏，应用菜单“退出 XhlCLI”才结束进程。

同一真实工作目录只允许一个任务执行。普通文本附件不会展开 `@image` 或 `@clipboard`；图片需要通过附件入口显式选择。单轮最多 8 个附件，文本最大 256 KB、图片最大 10 MB。图片在发送前校验模型视觉能力，历史图片沿用现有引擎的裁剪规则。

模型下拉框只列出有效配置的模型；自定义模型以保存的名称显示。Ollama 需明确保存模型或配置服务地址后才会出现，无配置时提示进入设置。

配置沿用 `~/.xhlcli/config.json`，优先级为进程环境变量 > 项目 `.env` > 全局配置。设置页面不返回保存的密钥；空密钥输入保留原值。会话与图片存于 `~/.xhlcli/desktop/`，快照使用现有 `~/.xhlcli/snapshots/`。快照沿用排除规则，额外排除 `.env`、`.env.*`、`.xhlcli` 和桌面发布目录；这些排除路径不能依赖本轮撤销恢复。

## 私有协议

后端入口：

```bash
java -cp target/xhlcli-1.0.1.jar com.xhlcli.desktop.DesktopMain
```

每行一个 JSON，请求 `{ "id": 1, "method": "state", "params": {} }`；响应为相同 `id` 的 `result` 或 `error`。事件为 `{ "event": "TextDelta", "sessionId": "…", "turnId": "…", "data": {} }`。stdout 仅发送协议，既有工具诊断转 stderr；不监听网络端口。

| 方法 | 主要参数 |
|---|---|
| `state` | 无；返回项目、会话与显示事件，隐藏模型内部历史 |
| `project.add` | `path` |
| `session.create` | `projectId`、可选 `model`（`provider:model`） |
| `session.update` | `sessionId` 与 `title/model/pinned/archived` |
| `turn.start` | `sessionId`、`input`、`attachments: [{path,type}]` |
| `turn.cancel` | `sessionId` |
| `approval.respond` | `sessionId`、`approvalId`、`allow` |
| `changes` / `undo` | `sessionId`、`turnId`；UI 的 undo 先经过原生确认 |
| `config.get` | 可选 `sessionId` 或 `projectId` |
| `config.save` | 上述上下文与 `provider/model/baseUrl/apiKey` |
| `config.test` | 上述上下文与已保存的 `model` |

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
