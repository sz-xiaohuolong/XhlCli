# Phase 12：MCP 扩展体系架构设计规范

## 1. 架构目标与背景

Model Context Protocol (MCP) 是当前连接 AI Agent 与外部工具、数据源和上下文系统的开放标准协议。在 Phase 00~11 中，XhlCLI 已经建设了受控 ReAct 循环、9 个本地工具、安全围栏与人工审批 (HITL)、上下文预算与分层记忆、智能规划执行 (Plan-and-Execute)、受控并发执行 (Bounded Parallelism)、Multi-Agent 协作体系与多模型路由能力。

Phase 12 正式引入 **MCP 扩展体系（MCP Extensibility Subsystem）**，目标是使 XhlCLI 能以开放标准的方式接入外部 MCP Server 提供的工具与资源，并让外部能力无缝融入现有的工具调用、权限审批、脱敏审计、取消传播和上下文预算体系中，不形成独立旁路：
1. **统一标准协议握手与能力协商**：支持 JSON-RPC 2.0 规范与 MCP `2024-11-05` 协议版本，完成 `initialize` / `notifications/initialized` 握手与能力协商（tools, resources, notifications）。
2. **多 Transport 通道支持**：
   - **`StdioMcpTransport`**：支持基于子进程标准输入输出的 stdio 传输，严格隔离 stdout 协议帧与非协议日志输出，异步捕获 stderr 形成环形诊断日志缓冲。
   - **`StreamableHttpMcpTransport`**：支持基于 HTTP POST 与 Streamable HTTP / SSE 的流式网络传输，支持自定义 Header 与 Bearer Token 环境变量注入。
3. **两级配置合并与凭据安全解析**：
   - 合并用户级配置 `~/.xhlcli/mcp.json` 与项目级配置 `.xhlcli/mcp.json`（项目级同名覆盖用户级）。
   - `${VAR}` 仅从明确允许的系统环境与工作区 `.env` 解析；缺失时置为 `ERROR` 状态，严禁明文凭据泄露。
4. **动态工具命名空间与 Schema 清洗**：
   - MCP 工具统一命名空间：`mcp__{server}__{tool}`，天然符合 `[a-z][a-z0-9_]*` 标识符规范且绝不与内置工具冲突。
   - 鲁棒 Schema 清洗（Sanitization）：对非标准或残缺的 `inputSchema` 进行自动修复与降级。
   - 动态注册与热更新：监听 `notifications/tools/list_changed`，动态刷新 Agent / Plan / Team 可用工具列表。
5. **安全策略与审计无缝集成**：
   - 纳入 `ApprovalPolicy`：MCP 工具默认视为 `MEDIUM_RISK`（中危），敏感或写操作要求人工审批；支持配置可信只读服务器。
   - 纳入 `AuditLog`：执行记录自动脱敏，掩码 Header 与敏感启动参数。
6. **资源管理与显式引用（Resources）**：
   - 支持 `resources/list` 与 `resources/read`。
   - 用户可通过 `/mcp read <uri>` 或 prompt 显式引用外部资源，按上下文预算受控注入，杜绝自动全文污染。
7. **生命周期隔离与终端管理**：
   - 状态机：`DISABLED`、`STARTING`、`READY`、`ERROR`、`STOPPED`。
   - 启动预算：后台异步初始化，超时不阻塞 CLI 首屏交互；单 Server 故障完全隔离，不影响全局。
   - 交互指令：`/mcp list`、`/mcp status`、`/mcp tools`、`/mcp resources`、`/mcp read`、`/mcp restart`、`/mcp stop`、`/mcp start`、`/mcp logs`。
8. **协作式取消传播**：
   - 用户 Ctrl+C 或 Run 取消时，向 MCP 服务发送 `notifications/cancelled`，并丢弃终态后的迟到结果。

---

## 2. 核心架构与模块划分

系统新增 `com.xhlcli.mcp` 顶层包，划分以下子包与组件：

```text
com.xhlcli.mcp
├── model/                  # 领域数据模型 (Config, Status, ToolDefinition, Resource)
├── protocol/               # JSON-RPC 2.0 报文与 MCP 协议常数
├── transport/              # Stdio 与 Streamable HTTP 通道抽象
├── client/                 # McpClient 协议门面与能力协商
├── config/                 # 两级配置加载、合并与 ${VAR} 安全解析
├── tool/                   # McpToolAdapter 动态工具适配器与 Schema 清洗
├── manager/                # McpServerManager 生命周期编排与热更新广播
└── cli/                    # /mcp 交互命令族与状态渲染
```

### 2.1 领域模型设计 (`com.xhlcli.mcp.model`)

```java
public enum McpTransportType {
    STDIO,
    HTTP
}

public enum McpServerStatus {
    DISABLED,
    STARTING,
    READY,
    ERROR,
    STOPPED
}

public record McpServerConfig(
        String name,
        McpTransportType transportType,
        String command,
        List<String> args,
        Map<String, String> env,
        String url,
        Map<String, String> headers,
        boolean disabled,
        boolean trustedReadOnly,
        String sourcePath
) {}

public record McpToolDefinition(
        String name,
        String description,
        JsonNode inputSchema
) {}

public record McpResource(
        String uri,
        String name,
        String description,
        String mimeType
) {}

public record McpResourceContent(
        String uri,
        String mimeType,
        String text,
        byte[] blob
) {}

public record McpContent(
        String type,        // "text", "image", "resource"
        String text,
        String data,        // base64 for image
        String mimeType,
        McpResource resource
) {}

public record McpCallResult(
        List<McpContent> contents,
        boolean isError
) {}
```

---

## 3. JSON-RPC 2.0 与 MCP 协议规范 (`com.xhlcli.mcp.protocol`)

### 3.1 协议常数
- 协议版本：`2024-11-05`
- 客户端信息：`name: "xhlcli", version: "0.10.0"`
- 方法定义：
  - `initialize`
  - `notifications/initialized`
  - `tools/list`
  - `tools/call`
  - `resources/list`
  - `resources/read`
  - `notifications/tools/list_changed`
  - `notifications/resources/list_changed`
  - `notifications/cancelled`

### 3.2 报文模型
- `JsonRpcRequest(String jsonrpc, Object id, String method, JsonNode params)`
- `JsonRpcResponse(String jsonrpc, Object id, JsonNode result, JsonRpcError error)`
- `JsonRpcNotification(String jsonrpc, String method, JsonNode params)`
- `JsonRpcError(int code, String message, JsonNode data)`

---

## 4. Transport 通道层架构 (`com.xhlcli.mcp.transport`)

### 4.1 `McpTransport` 接口
```java
public interface McpTransport extends AutoCloseable {
    void start() throws IOException;
    CompletableFuture<JsonRpcResponse> sendRequest(JsonRpcRequest request, CancellationToken cancellationToken);
    void sendNotification(JsonRpcNotification notification) throws IOException;
    void setNotificationListener(Consumer<JsonRpcNotification> listener);
    boolean isAlive();
    List<String> getRecentLogs();
}
```

### 4.2 `StdioMcpTransport` 设计
- **子进程派生**：通过 `ProcessBuilder` 启动外部进程，工作目录设为项目目录或受控目录，注入脱敏后的环境变量。
- **Stdin/Stdout 协议流**：
  - 发送请求：写入 UTF-8 JSON 文本并追加换行符 `\n`，立即 `flush()`。
  - 接收响应：单独的后台 Reader 线程逐行读取 stdout。
  - **Stdout 防污染防护**：若行不是合法的 JSON-RPC 报文（部分外部 server 会向 stdout 输出非协议 print 日志），将该行沉淀至 ring buffer 并记入 warning 日志，防止 JSON 反序列化崩溃。
- **Stderr 诊断流**：单独线程异步消费 stderr，写入大小为 100 行的固定环形缓冲区（`CircularFifoBuffer`），供 `/mcp logs <server>` 调阅与故障排查。
- **异步响应关联**：维护并发映射 `ConcurrentHashMap<Object, CompletableFuture<JsonRpcResponse>>`，按 `id` 关联响应并触发 complete。

### 4.3 `StreamableHttpMcpTransport` 设计
- 基于 `OkHttpClient`，复用连接池与超时设置。
- 发送 HTTP POST 请求到指定 `url`，携带 Header（支持解析后的 Bearer Token）。
- 返回标准 JSON-RPC 响应，或解析 NDJSON / SSE 流。

---

## 5. 动态工具注册与 Schema 清洗 (`com.xhlcli.mcp.tool`)

### 5.1 命名空间与适配器
- 工具适配器 `McpToolAdapter` 实现核心 `com.xhlcli.tool.Tool` 接口。
- **命名空间**：`mcp__{serverName}__{toolName}`（双下划线分隔，确保在 `ToolRegistry` 的正则 `^[a-z][a-z0-9_]*$` 下完全合法，并杜绝与内置工具命名冲突）。
- **Schema 清洗**：
  - 若 `inputSchema` 为空或非 object，自动修正为 `{ "type": "object", "properties": {} }`；
  - 清除不兼容的 JSON Schema 特性（如非法 `$ref`、无效外链）；
  - 保障模型端获取合规的函数定义。

### 5.2 返回值映射与图片元数据处理
- 工具调用执行结果包含一个或多个 `McpContent`：
  - `type: "text"` $\rightarrow$ 提取 `text`；
  - `type: "image"` $\rightarrow$ 转换为文本元数据摘要：`[Image: mimeType=<mime>, size=<len> bytes]`（遵守非目标约束与文本模型兼容性要求，杜绝非法 Payload 导致大模型崩溃）；
  - `type: "resource"` $\rightarrow$ 提取 `[Resource: <uri>]` 摘要。
- 若 `isError: true`，映射为 `ToolOutput.failure(message)`。

---

## 6. 配置合并与环境解析 (`com.xhlcli.mcp.config`)

### 6.1 配置文件规范
支持 `~/.xhlcli/mcp.json` 与 `<projectDir>/.xhlcli/mcp.json`：
```json
{
  "mcpServers": {
    "fetch": {
      "command": "uvx",
      "args": ["mcp-server-fetch"],
      "env": {
        "CUSTOM_VAR": "${MY_CUSTOM_VAR}"
      },
      "trustedReadOnly": true
    },
    "github": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-github"],
      "env": {
        "GITHUB_PERSONAL_ACCESS_TOKEN": "${GITHUB_TOKEN}"
      }
    },
    "weather": {
      "url": "https://api.weather.com/mcp",
      "headers": {
        "Authorization": "Bearer ${WEATHER_API_KEY}"
      }
    }
  }
}
```

### 6.2 合并规则与安全解析
1. **项目优先覆盖**：若同名 Server 同时存在于用户级与项目级，项目级配置完全覆盖用户级配置，并记录 `sourcePath`。
2. **`${VAR}` 严格受控解析**：仅从系统环境变量和当前项目 `.env` 中提取。若某个占位符未能解析，该 Server **不予启动**，标记为 `ERROR` 并在状态中展示缺失的变量名，**绝不阻塞其他正常 Server 的加载**。
3. **脱敏保护**：所有外部展示（日志、`/mcp status`、`/config`、审计日志）均掩码脱敏敏感 Header 与带 Key 的命令行参数。

---

## 7. 生命周期、审批安全与终端交互 (`com.xhlcli.mcp.manager`)

### 7.1 `McpServerManager`
- 管理所有注册 Server 实例的生命周期；
- **启动预算（Startup Budget）**：CLI 启动时，异步拉起各 Server，设置总启动门禁预算（默认 3000ms）。超时未完成的 Server 自动在后台继续启动，CLI 无阻塞直接进入终端交互；
- **通知监听与热更新**：当 Server 发送 `notifications/tools/list_changed` 时，异步重新拉取 `tools/list`，并通知注册表动态更新，正在执行中的旧 Run 保持原有工具快照，新 Run 自动获得新工具集。

### 7.2 安全与审计集成
- 扩展 `ApprovalPolicy`：
  - 所有以 `mcp__` 开头的工具，若对应 Server 未开启 `trustedReadOnly`，默认分配为 `RiskLevel.MEDIUM_RISK`（需人工审批）；若涉及外部写操作或命令，要求单步确认；
  - 若开启 `trustedReadOnly`，分配为 `RiskLevel.READ_ONLY`（自动放行）。
- 扩展 `AuditLog`：记录 `mcp_call` 事件，记录目标 Server、工具名与耗时，参数中的凭据自动脱敏。

### 7.3 终端命令扩展 (`ChatCommand.MCP`)
新增 `/mcp` 指令族：
- `/mcp list`：查看所有 Server、状态、传输模式、工具数量与错误；
- `/mcp status <server>`：查看指定 Server 的详细握手信息、PID/URL 与能力；
- `/mcp tools [server]`：列出 MCP 工具清单；
- `/mcp resources [server]`：列出可用 Resource 资源 URI 与说明；
- `/mcp read <uri>`：读取指定 Resource 资源内容并展示；
- `/mcp restart <server>`：手动热重启指定 Server；
- `/mcp stop <server>`：停止指定 Server；
- `/mcp start <server>`：启动已停止的 Server；
- `/mcp logs <server>`：查看指定 Server 最近的诊断日志。

---

## 8. 验收基准与非目标

### 8.1 验收标准 (DoD)
1. **Transport 覆盖**：完成 Stdio 与 Streamable HTTP 双 Transport 契约测试。
2. **工具调用闭环**：MCP 工具完整穿透 ReAct Agent、审批流与脱敏审计日志。
3. **资源读取支持**：用户可通过命令与上下文引用规范读取 Resource。
4. **故障隔离**：单 Server 超时、崩溃或非法 stdout 输出不影响系统其他功能。
5. **取消传播**：Run 取消时发送取消帧并忽略迟到结果。
6. **自动化测试**：全套新增专项测试通过，全量自动化测试保持 100% 绿灯。

### 8.2 非目标 (Non-Goals)
- 本期不实现 OAuth 网页授权流（使用 Bearer / Token 环境变量替代）；
- 本期不实现 Server 崩溃无限自动退避重启（提供手动 `/mcp restart`）；
- 本期不实现 Client 侧 Sampling（大模型二次调用 Server 回调）；
- 严禁将所有 Resource 全文静默预加载至系统上下文。
