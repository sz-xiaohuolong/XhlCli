# Phase 12：MCP 生态扩展工程评测报告

> **评测日期：** 2026-09-21  
> **评测对象：** Model Context Protocol (MCP 2024-11-05) 协议层、Dual Transports (`stdio` / `http`)、配置合并与环境变量解析、Tool/Resource 适配器、`McpServerManager` 隔离生命周期与 `/mcp` 交互控制台  
> **基线测试：** 329 项单元与集成测试（100% 通过）

---

## 1. MCP 协议与传输层合规矩阵

Phase 12 遵循 MCP 规范（Protocol Version `2024-11-05`），通过 JSON-RPC 2.0 协议标准实现了全双工握手、工具发现、资源读取与通知监听。

| 评估维度 | StdioMcpTransport | StreamableHttpMcpTransport | 验收标准 / 规约达成 |
|---|---|---|---|
| **传输协议** | 子进程标准输入/输出 (UTF-8) | HTTP POST + SSE 流式响应 | ✅ JSON-RPC 2.0 全兼容 |
| **握手流程** | `initialize` -> `notifications/initialized` | `initialize` -> `notifications/initialized` | ✅ 协商 protocolVersion: 2024-11-05 |
| **杂质过滤** | 严格过滤子进程 stdout 非 JSON 行 | N/A (HTTP 结构化消息) | ✅ 忽略日志输出，仅捕获合法 JSON-RPC |
| **异常隔离** | 循环缓冲环 (Circular Buffer 64KB) 抓取 stderr | HTTP 状态码与响应体捕获 | ✅ 崩溃或退出时提取最后关键日志作为排错诊断 |
| **请求超时** | 单请求超时控制（默认 30s） | OkHttp read/write 超时（默认 30s） | ✅ 超时抛出 `McpException(TIMEOUT)` |
| **协作式取消** | `CancellationToken` 触发子进程销毁 / 管道断开 | OkHttp `Call.cancel()` 立即断开 Socket | ✅ 及时中断运行中阻塞操作 |
| **环境变量注入** | 继承进程环境 + 显式 `env` 覆盖注入 | 显式注入 `Authorization: Bearer <TOKEN>` 等 Header | ✅ 敏感凭证由 SecretRedactor 严格脱敏 |

---

## 2. 两级配置合并与环境变量严格解析验证

### 2.1 用户级与项目级配置合并矩阵

XhlCLI 按照安全沙箱要求实现了用户全局（`~/.xhlcli/mcp.json`）与项目工作区（`.xhlcli/mcp.json`）的双层解析：

```
+-----------------------------------+-----------------------------------+
| ~/.xhlcli/mcp.json (User Level)   | .xhlcli/mcp.json (Project Level)  |
+-----------------------------------+-----------------------------------+
| server-a (enabled=true, args=...) | server-a (args override, env=...) | --> 项目级同名服务器全量覆盖
| server-b (enabled=true)           | (未定义)                          | --> 用户级服务器被继承保留
| (未定义)                          | server-c (enabled=true)           | --> 项目专属服务器挂载
+-----------------------------------+-----------------------------------+
```

### 2.2 环境变量安全解析实测
- **语法支持**：支持 `${ENV_VAR}` 与 `${ENV_VAR:-default_value}` 标准插值；
- **严格性与隔离性（Fault Isolation）**：
  - 若配置引用未定义的 `${MISSING_TOKEN}` 且无默认值，`McpConfigLoader` 抛出结构化 `UnresolvedEnvException`；
  - `McpServerManager` 捕获该异常并将对应 Server 标记为 `McpServerStatus.ERROR`，附带可诊断错误原因；
  - **关键物证**：缺失变量**绝不引发进程崩溃**，亦不影响其他合法 MCP 服务器的加载与启动。

---

## 3. 工具转换、Schema 清洗与安全沙箱验证

### 3.1 命名空间与工具适配
- **命名规范**：遵循 `mcp__{server}__{tool}` 统一命名空间（例如 `mcp__math_server__add`）；
- **参数 Schema 清洗**：
  - MCP Server 声明的 `inputSchema` 自动移除 `$schema`、`definitions` 等内部冗余属性，规范为标准 JSON Schema `type: object` 结构；
  - 接入 `ToolSchemaValidator`，严格拦截缺失必填参数与类型违约调用。

### 3.2 多模态与内容映射
- **Text 结果**：自动合并为标准字符串；
- **Image 结果**：安全转换为结构化元数据文本 `[Image: mimeType=image/png, size=1234 bytes]`，防止在不支持图像的上下文中引发内存溢出或 Token 激增；
- **isError 映射**：当 `callTool` 返回 `isError: true` 时，映射为执行器错误并触发 Agent 容错修正逻辑。

### 3.3 安全策略与 HITL 确认
- **默认风险等级**：所有 `mcp__*` 动态工具在 `ApprovalPolicy` 中默认评定为 `RiskLevel.MEDIUM_RISK`，在交互终端中执行写操作时触发人机确认；
- **只读白名单机制**：当服务器显式配置 `trustedReadOnly: true` 时，降级为 `LOW_RISK` 无感放行；
- **脱敏审计**：工具调用参数与输出全量接入 `AuditLog`，任何敏感 Header 或 Token 均由 `StreamingSecretRedactor` 脱敏。

---

## 4. 生命周期管理与 3 秒启动预算实测

### 4.1 异步启动与超时兜底
- `McpServerManager.startServersAsync(Duration timeout)` 采用 Java 21 异步并行拉起所有已启用的 MCP 服务；
- 启动预算上限硬性设定为 3 秒（`DEFAULT_STARTUP_BUDGET = Duration.ofSeconds(3)`）；
- 若服务器在 3 秒内未完成握手与 `tools/list` 响应，自动取消握手流程并标记为 `McpServerStatus.ERROR ("Startup timed out after 3s")`，绝不阻塞 CLI 进入主交互 Loop。

### 4.2 动态通知与工具热更新
- 监听协议通知 `notifications/tools/list_changed`；
- 当服务端动态增删工具时，触发 `McpServerManager` 重新拉取工具列表，并通过 `ToolRegistry.updateDynamicTools()` 线程安全更新；
- 联动 `ReactAgent`、`PlanExecuteAgent`、`TeamOrchestrator` 与 `SubAgent`，模型在下一轮次对话即刻感知最新工具定义。

---

## 5. 契约测试与验证物证 (Verification Evidence)

### 5.1 自动化测试物证
```
[INFO] Running com.xhlcli.mcp.protocol.JsonRpcProtocolTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.007 s -- in com.xhlcli.mcp.protocol.JsonRpcProtocolTest
[INFO] Running com.xhlcli.mcp.transport.StdioMcpTransportTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.170 s -- in com.xhlcli.mcp.transport.StdioMcpTransportTest
[INFO] Running com.xhlcli.mcp.transport.StreamableHttpMcpTransportTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.237 s -- in com.xhlcli.mcp.transport.StreamableHttpMcpTransportTest
[INFO] Running com.xhlcli.mcp.client.McpClientTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.003 s -- in com.xhlcli.mcp.client.McpClientTest
[INFO] Running com.xhlcli.mcp.config.McpConfigLoaderTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.013 s -- in com.xhlcli.mcp.config.McpConfigLoaderTest
[INFO] Running com.xhlcli.mcp.tool.McpToolAdapterTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.005 s -- in com.xhlcli.mcp.tool.McpToolAdapterTest
[INFO] Running com.xhlcli.mcp.manager.McpServerManagerTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.006 s -- in com.xhlcli.mcp.manager.McpServerManagerTest
[INFO] Running com.xhlcli.mcp.McpIntegrationTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.019 s -- in com.xhlcli.mcp.McpIntegrationTest
[INFO] Running com.xhlcli.cli.McpCliIntegrationTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.079 s -- in com.xhlcli.cli.McpCliIntegrationTest
```
**全量回归验证：**
```
[INFO] Results:
[INFO] Tests run: 329, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### 5.2 终端 `/mcp` 命令实测物证
在 `McpCliIntegrationTest` 中执行终端命令族输出物证：
```
=== /mcp list ===
MCP Servers:
  • math-service [READY] (stdio: node /tools/math.js) - 2 tools, 0 resources

=== /mcp tools ===
MCP Tools:
  • mcp__math-service__add: Add two numbers
  • mcp__math-service__multiply: Multiply two numbers

=== /mcp status math-service ===
Server: math-service
Status: READY
Transport: STDIO
Tools: 2
Resources: 0
```

---

## 6. 验收结论

Phase 12 达到 PRD 与 DoD 全部要求：
- ✅ 完整实现 JSON-RPC 2.0 与 MCP 协议握手、版本协商与通知；
- ✅ 交付高性能 `StdioMcpTransport` 与 `StreamableHttpMcpTransport`；
- ✅ 实现全局与项目配置合并及安全环境变量替换（缺失变量故障隔离）；
- ✅ 实现工具动态命名空间注入（`mcp__{server}__{tool}`）与模型运行时感知；
- ✅ 实现资源管理（`resources/list`、`resources/read` 与 `/mcp read` 指令）；
- ✅ 落地 3 秒启动预算、进程隔离与 `notifications/tools/list_changed` 动态刷新；
- ✅ 交付 `/mcp` 终端命令族与人机审核安全沙箱；
- ✅ 全量 329 项自动化测试 100% 绿灯，构建与打包完全正常。
