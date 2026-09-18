# Phase 11：多模型路由与能力声明工程评测报告

> **评测日期：** 2026-09-18  
> **评测对象：** 多 Provider 适配层、`ModelCapabilities` 声明、`LlmProviderRegistry` 注册工厂、`/model` 交互控制台与契约测试套件  
> **基线测试：** 305 项单元与集成测试（100% 通过）

---

## 1. Provider 能力矩阵与特征声明

Phase 11 解耦了 Agent 核心与单一模型实现的硬编码依赖，通过 `ModelCapabilities` 显式声明模型能力，支持根据能力进行上层功能激活与安全护栏守卫。

| Provider | 模型标识符 | 上下文窗口 | 函数/工具调用 | 视觉/多模态 | Prompt 缓存 | 思考链要求 | 流式 Wire 格式 |
|---|---|---|---|---|---|---|---|
| **DeepSeek** | `deepseek:deepseek-chat`<br>`deepseek:deepseek-coder` | 128,000 | ✅ 支持 | ❌ 不支持 | ✅ deepseek-prefix | ❌ 否 | OpenAI SSE |
| **DeepSeek** | `deepseek:deepseek-reasoner` | 128,000 | ✅ 支持 | ❌ 不支持 | ✅ deepseek-prefix | ✅ 是 | OpenAI SSE |
| **OpenAI** | `openai:gpt-4o` | 128,000 | ✅ 支持 | ✅ 支持 | ❌ 否 | ❌ 否 | OpenAI SSE |
| **OpenAI** | `openai:gpt-4o-mini` | 128,000 | ✅ 支持 | ✅ 支持 | ❌ 否 | ❌ 否 | OpenAI SSE |
| **Anthropic** | `anthropic:claude-3-5-sonnet-20241022` | 200,000 | ✅ 支持 | ✅ 支持 | ✅ anthropic-ephemeral | ❌ 否 | Anthropic Messages SSE |
| **Anthropic** | `anthropic:claude-3-5-haiku-20241022` | 200,000 | ✅ 支持 | ✅ 支持 | ✅ anthropic-ephemeral | ❌ 否 | Anthropic Messages SSE |
| **Ollama** | `ollama:qwen2.5-coder`<br>`ollama:llama3.1` | 32,768 | ✅ 支持 | ❌ 不支持 | ❌ 否 | ❌ 否 | Ollama NDJSON |
| **Text-Only** | 自定义/小模型规格 | 自定义 (如 8k/16k) | ❌ 不支持 | ❌ 不支持 | ❌ 否 | ❌ 否 | 任意兼容格式 |

---

## 2. 跨 Provider 协议差异与适配验证

### 2.1 Wire Format 协议差异处理

```
+---------------------+-------------------------------+-----------------------------------+
| 协议维度            | OpenAI-compatible 族          | Anthropic Claude 原生协议         |
+---------------------+-------------------------------+-----------------------------------+
| 接口地址            | POST /v1/chat/completions     | POST /v1/messages                 |
| 鉴权与协议版本      | Bearer <API_KEY>              | x-api-key: <KEY>                  |
|                     |                               | anthropic-version: 2023-06-01     |
| 系统提示词位置      | messages[role=system]         | 顶级 "system": "..." 字符串       |
| 工具声明定义        | tools[].function.parameters   | tools[].input_schema              |
| 流式 Tool Call 传输 | delta.tool_calls[].arguments  | content_block_delta (type:        |
|                     |                               | input_json_delta)                 |
| 工具返回结果格式    | role=tool, tool_call_id=...   | role=user, content=[{type:        |
|                     |                               | tool_result, tool_use_id=...}]    |
| 用量信息字段        | usage.prompt_tokens           | message_start (input_tokens)      |
|                     | usage.completion_tokens       | message_delta (output_tokens)     |
+---------------------+-------------------------------+-----------------------------------+
```

### 2.2 本地 Ollama 原生协议适配
- 采用 NDJSON（每行一个自包含 JSON 对象）进行流式解析，而非传统 SSE 规范中的 `data: ...` 前缀；
- 服务直连无需 API Key，凭据校验时自动放行；
- 从尾部 chunk 中的 `prompt_eval_count` 与 `eval_count` 精确提取 Token 用量。

---

## 3. 契约测试与验证物证 (Contract Test Evidence)

通过 `LlmProviderContractTest`、`AnthropicClaudeClientTest`、`OllamaClientTest` 等契约测试套件，在同一组业务输入与 Tool Schema 下完成了对各 Provider 的完全等价行为验证：

```
[INFO] Running com.xhlcli.llm.LlmProviderContractTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.125 s -- in com.xhlcli.llm.LlmProviderContractTest
[INFO] Running com.xhlcli.llm.AnthropicClaudeClientTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.048 s -- in com.xhlcli.llm.AnthropicClaudeClientTest
[INFO] Running com.xhlcli.llm.OllamaClientTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.021 s -- in com.xhlcli.llm.OllamaClientTest
[INFO] Running com.xhlcli.llm.LlmProviderRegistryTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.003 s -- in com.xhlcli.llm.LlmProviderRegistryTest
[INFO] Running com.xhlcli.llm.ModelSwitchingIntegrationTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.042 s -- in com.xhlcli.llm.ModelSwitchingIntegrationTest
```

### 3.1 契约验证覆盖范围
1. **文本流式输出一致性**：各 Provider 在逐字/逐分片输出时，均保序触发 `StreamListener.onTextDelta()`，并在流结束时返回完整 `ChatResponse`；
2. **多步工具调用累积一致性**：流式返回 Tool Call 时，各 Provider 无论分片切片方式如何，均正确累积为完整合法的 `ToolCall(id, name, argumentsJson)`；
3. **HTTP 异常分类一致性**：各 Provider 的 401/403 均映射为 `AUTHENTICATION`，429 映射为可重试的 `RATE_LIMIT`，5xx/529 映射为可重试的 `SERVER`；
4. **凭据脱敏一致性**：请求失败或调试日志中严禁暴露 API Key，各 Provider 统一经过 `SecretRedactor` 过滤；
5. **取消响应一致性**：外部触发 `CancellationToken.cancel()` 时，各 Provider 立即阻断连接并抛出 `CANCELLED`。

---

## 4. 运行时切换与预算安全联动实测

### 4.1 终端 `/model` 命令实测物证
在 `ModelSwitchingIntegrationTest` 中执行全交互回路：
```
=========================================================================================
🤖 可用模型与 Provider 矩阵：
-----------------------------------------------------------------------------------------
[✓] deepseek:deepseek-chat              Provider: deepseek   Window: 128k  Tools: Yes  Status: Configured
[ ] deepseek:deepseek-coder             Provider: deepseek   Window: 128k  Tools: Yes  Status: Configured
[ ] deepseek:deepseek-reasoner          Provider: deepseek   Window: 128k  Tools: Yes  Status: Configured
[ ] openai:gpt-4o                       Provider: openai     Window: 128k  Tools: Yes  Status: Missing Key
[ ] openai:gpt-4o-mini                  Provider: openai     Window: 128k  Tools: Yes  Status: Missing Key
[ ] anthropic:claude-3-5-sonnet-20241022 Provider: anthropic  Window: 200k  Tools: Yes  Status: Missing Key
[ ] anthropic:claude-3-5-haiku-20241022  Provider: anthropic  Window: 200k  Tools: Yes  Status: Missing Key
[ ] ollama:qwen2.5-coder                Provider: ollama     Window: 32k   Tools: Yes  Status: Local
[ ] ollama:llama3.1                     Provider: ollama     Window: 32k   Tools: Yes  Status: Local
=========================================================================================
```
- 输入 `/model use ollama`：模型瞬间切换为 `ollama:qwen2.5-coder`，`TokenBudget` 上下文总窗口从 128k 动态重算为 32k，分层保留配额自适应收敛；
- 状态同步：`ReactAgent`、`PlanExecuteAgent`、`TeamOrchestrator` 与 `ConversationHistoryCompactor` 中的 Client 实例全量同步更新。

### 4.2 能力护栏（Guardrails）安全拦截实测
- 当切换至不支持工具的模型（`supportsTools == false`）且当前任务挂载了工具列表时：
  - `ReactAgent.run()`：在启动时提前拦截，输出明确友好提示，返回 `MODEL_UNSUPPORTED_TOOLS` 失败状态，**未向大模型发送任何不兼容请求**（`streamCalled == false`）；
  - `PlanExecuteAgent.run()`：提前拦截并返回 `MODEL_UNSUPPORTED_TOOLS`；
  - `TeamOrchestrator.run()`：提前拦截并提示 Multi-Agent 需具备工具能力。

---

## 5. 验收结论

Phase 11 达到 PRD 与 DoD 全部要求：
- ✅ 解耦模型与 Agent 核心，实现稳定 `LlmClient` 与 `ModelCapabilities`；
- ✅ 交付 DeepSeek、OpenAI、Anthropic Claude、Ollama 四大 Provider；
- ✅ 封装原生 Messages 协议与 NDJSON 流式差异；
- ✅ 支持运行时 `/model list`、`/model use`、`/model status` 无缝切换与预算重算；
- ✅ 全量 305 项自动化测试 100% 绿灯通过，构建打包无告警。
