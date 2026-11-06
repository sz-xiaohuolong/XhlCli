# XhlCLI

XhlCLI 是一个使用 Java 21 构建的自主编程终端 Coding Agent。项目遵循本地优先、极简确定性与高弹性工程理念。现已完整交付全量 18 个演进阶段并正式发布 **`v1.0.0`**，提供现代化静态官网、交互式终端演练与一键安装脚本。

> **GitHub 仓库**: [https://github.com/sz-xiaohuolong/XhlCli](https://github.com/sz-xiaohuolong/XhlCli)  
> **一键安装**: `curl -fsSL https://raw.githubusercontent.com/sz-xiaohuolong/XhlCli/main/install.sh | bash`  
> **静态官网**: 源码位于 `website/` 目录，支持 Vercel 零配置秒级导入部署

![XhlCLI v1.0.0 终端多智能体协作与架构演示](docs/assets/xhlcli-v1.0.0-demo.png)

## 当前能力

- 支持 OpenAI, Anthropic Claude, DeepSeek, Ollama 等多模型 Provider 协议路由；
- 图片多模态上下文预处理与视觉防御护栏（`@image:<path>`, `@clipboard`，Alpha Flatten 白底合成防穿透、2000x2000 等比缩放、5MB 阈值压缩、坐标换算元信息注入、非视觉模型防 400 纯文本降级）；
- 后台持久任务队列与 Localhost 安全 Runtime API（SQLite 事务原子调度、孤儿租约自愈、127.0.0.1 严格绑定与 API Key 拦截、游标 SSE 事件流）；
- Side-History 隔离快照与版本回滚（JGit 纯 Java 隔离存储、宿主 Git 零污染、`revert_turn` 自愈工具与 `/snapshot` `/restore`）；
- 终端产品化与交互治理（JLine 4 动态状态栏、WCWidth 精准对齐、Markdown 表格与代码框排版、Git Diff 语法着色、SafeHistory 历史治理与 Tab 智能补全）；
- Skill 与 Prompt 分层治理（8 层确定性装配、安全规则不可变、按需动态加载）；
- Web 检索与浏览器沙箱（SSRF 安全围栏、多搜索引擎统一抽象、5MB 受限正文抽取、CDP 浏览器控制与敏感页审批）；
- MCP 生态扩展（JSON-RPC 2.0、Stdio/SSE 传输、两级配置合并、命名空间隔离）；
- Multi-Agent 专职协作架构（Planner / Worker 池 / Reviewer 最小上下文交接与审查打回熔断）；
- 有界并发执行调度与 DAG 规划执行（Bounded Parallelism、Kahn 分层批次）；
- 嵌入式 SQLite AST 代码库检索与 RAG 语义索引；
- 受控 ReAct 循环、9 个本地工具、安全围栏与人工审批 (HITL)；
- Java 21 可执行 JAR、522 项离线自动测试和 CI 配置。

## 环境要求

- JDK 21；
- 可访问 DeepSeek API；
- 一个 DeepSeek API Key，可在 [DeepSeek API Keys](https://platform.deepseek.com/api_keys) 创建。

仓库自带 Maven Wrapper，无需单独安装 Maven。

## 配置 API Key

XhlCLI 支持三种灵活且安全的 API Key 配置方式，优先级为：**进程环境变量 > 项目 `.env` > 全局配置 `~/.xhlcli/config.json`**。

### 方式 1：全局用户配置（最推荐，对齐 Claude Code）
在用户家目录的 `~/.xhlcli/config.json` 中配置（若文件不存在可直接创建）：

```json
{
  "apiKey": "replace_with_your_deepseek_api_key",
  "openaiApiKey": "sk-...",
  "anthropicApiKey": "sk-ant-..."
}
```

或者使用 Provider 嵌套映射风格：
```json
{
  "providers": {
    "deepseek": {
      "apiKey": "replace_with_your_deepseek_api_key"
    },
    "openai": {
      "apiKey": "sk-..."
    }
  }
}
```

建议限制配置文件权限：`chmod 600 ~/.xhlcli/config.json`。

### 方式 2：项目工作区 `.env`
在具体工程仓库根目录执行：

```bash
cp .env.example .env
chmod 600 .env
```

编辑 `.env`：
```dotenv
DEEPSEEK_API_KEY=replace_with_your_deepseek_api_key
```

### 方式 3：进程环境变量
```bash
export DEEPSEEK_API_KEY=replace_with_your_deepseek_api_key
```

> **安全提示**：不要把 Key 粘贴到 Issue、公开聊天记录、截图或终端录屏中；不要通过命令行 flag 传 Key。交互中使用 `/config` 只会显示非敏感配置和 Key 的来源（例如 `configured (USER_CONFIG)`），不会显示 Key 明文。

默认配置：

| 配置 | 默认值 |
|---|---|
| Provider | DeepSeek |
| Model | `deepseek-v4-flash` |
| Base URL | `https://api.deepseek.com` |
| Connect timeout | 30 秒 |
| Read timeout | 300 秒 |
| Request timeout | 600 秒 |
| Agent maximum iterations | 10 |
| Agent overall timeout | 600 秒 |
| Log level | `WARN` |
| Team 自动选择 | `manual`（默认关闭） |

配置解析优先级：命令行参数 > 进程环境变量 (`ENVIRONMENT`) > 项目 `.env` (`DOT_ENV`) > `~/.xhlcli/config.json` (`USER_CONFIG`) > 默认值。

## 构建与运行

```bash
./mvnw clean package -DskipTests
java -jar target/xhlcli-1.0.1.jar
```

如果 macOS 同时安装了多个 JDK：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./mvnw clean package -DskipTests
```

元信息命令：

```bash
java -jar target/xhlcli-1.0.1.jar --help
java -jar target/xhlcli-1.0.1.jar --version
```

可用启动参数：`--team-mode`（`manual` 或 `auto`）、`--model`、`--base-url`、`--connect-timeout`、`--read-timeout`、`--request-timeout`、`--max-iterations`、`--agent-timeout`、`--log-level`。`--max-iterations` 取值为 1–100，`--agent-timeout` 取值为 1–3600 秒。交互中使用 `/config` 只会显示非敏感配置和 Key 的配置状态，不会显示 Key 值。

## 对话命令

| 命令 | 作用 |
|---|---|
| `/help` | 显示帮助信息 |
| `/config` | 显示非敏感配置与来源 |
| `/clear` | 清空当前进程内的会话历史 |
| `/plan [goal]` | 触发 DAG 拓扑分层规划与受控执行 |
| `/team <goal>` | 手动启动 Team，由规划者、执行者、审查者分工完成任务 |
| `/team-mode [auto\|manual]` | 查询或切换当前会话的自动团队选择，不写全局配置 |
| `/model [list\|use\|status]` | 模型与 Provider 动态切换管理 |
| `/mcp [list\|status\|tools\|resources\|read\|restart]` | MCP 外部工具与资源协议管理 |
| `/browser [status\|connect\|disconnect\|tabs]` | 浏览器沙箱与 CDP 宿主连接控制台 |
| `/skill [list\|show\|enable\|disable\|reload]` | 可复用工程技能与工作流管理 |
| `/prompt [show\|export]` | 查看或脱敏导出分层系统提示词与审计信息 |
| `/history [list\|clear]` | 查看安全历史记录或清空持久化历史 |
| `/snapshot [status\|clean]` | Side-Git 隔离快照状态、历史列表与重置 |
| `/restore [N]` | 恢复回滚至指定轮次开始前状态（默认 N=1） |
| `/task [list\|add\|log\|cancel]` | 后台持久任务队列与执行状态管理 |
| `/exit` | 安全退出 |
| Ctrl+C | 取消正在生成的响应 |

## 自动选择 Team 与失败重规划

Team 是多个专职 Agent 分工执行同一个目标的模式。保持默认手动行为；启用后，每轮普通输入由无工具权限的模型判断走普通模式还是 Team，并显示原因：

```bash
java -jar target/xhlcli-1.0.1.jar --team-mode auto
```

也可用 `XHLCLI_TEAM_MODE=auto` 或全局配置 `"teamMode": "auto"`。启动配置优先级同上。会话中用 `/team-mode manual` 关闭，用 `/team <目标>` 随时手动启动。自动选择每轮增加一次模型请求；失败或格式不合法时回退普通模式，取消后不继续执行。

Team 中一个步骤最多修正重试两次。仍未通过审查时，规划者根据失败原因、工具记录和文件变化判断是否需要换方案：完成步骤比例低于 50% 可调整剩余计划，达到 50% 后只调整失败分支；整个任务最多换两次计划。已完成成果保留，权限拒绝或证据不完整时停止自动换图。只改步骤编号不能获得新的重试次数。

可复现的离线终端验收（需 Python 3；无需真实 Key）：

```bash
python3 scripts/team_acceptance_demo.py
```

[人工验收、验证证据与业务流程](docs/engineering/team-routing-and-replanning-verification.md)。重启后的 `/history` 仅显示安全输入历史，完整聊天、Team 计划和执行状态不跨进程恢复。

## 故障排查

- `DeepSeek API Key is missing`：确认 `.env` 位于运行命令所在的仓库根目录，且键名为 `DEEPSEEK_API_KEY`。
- `AUTHENTICATION`：在 DeepSeek 控制台检查 Key 状态和账户权限；如有泄漏可能，立即轮换。
- `RATE_LIMIT`：等待后重试，并检查账户额度与限流状态。
- `NETWORK` / `TIMEOUT`：检查网络和 Base URL，必要时提高对应 timeout。
- `INVALID_RESPONSE`：重试；若持续出现，可临时设置 `XHLCLI_LOG_LEVEL=DEBUG` 查看已脱敏的协议元数据。

普通错误不会打印堆栈；调试日志不会记录消息正文、Authorization 或完整 Key。

## 文档导航

本项目使用 `vibe-workflow` 管理需求、实施与验证，文档沿用原有分类。每份正文只保留一个权威位置，版本通过项目地图关联。

- [项目定位、文档地图与版本追溯](docs/PROJECT.md)
- [当前进度与下一步](docs/PROGRESS.md)
- [当前技术架构](docs/TECH_DESIGN.md)、[产品总览](docs/PRD.md)、[路线图](docs/ROADMAP.md)
- 分类正文：[需求](docs/prd/)、[设计](docs/specs/)、[实施计划](docs/plans/)、[研究与验收](docs/engineering/)
- 当前 v1.1：[需求](docs/prd/v1.1-trusted-task-lifecycle.md)、[拟议设计](docs/specs/2026-10-16-v1.1-trusted-task-lifecycle-design.md)、[实施计划](docs/plans/2026-10-16-v1.1-trusted-task-lifecycle.md)、[验收矩阵](docs/engineering/v1.1-trusted-task-lifecycle-verification.md)
- [Agent 工作约定](AGENTS.md)、[安全策略](SECURITY.md)

## 当前验收状态

Phase 02 已通过 127 项离线自动测试（比 Phase 01 增加 77 项）、MockWebServer Tool Call 协议测试、可执行 JAR、`--help` / `--version` 冒烟和 Java 21 字节码门禁。按用户明确决定，本期免除人工演示和录屏；`0.3.0-SNAPSHOT` 没有 Phase 02 版本标签。Phase 01 的真实 DeepSeek 五轮会话、Ctrl+C 恢复和 `v0.2.0` 发布记录保持不变。

## License

[MIT](LICENSE)
