# Phase 11：多模型路由与能力声明设计规范

## 1. 架构目标与背景

在智能编码助手系统演进过程中，硬编码单一模型 Provider 会导致系统与特定供应商深度绑定，无法满足用户对价格、速度、上下文容量（如 200k+ 长文本）以及本地私密运行（Ollama）的多元化诉求。同时，不同模型的能力差异显著（部分模型不支持函数调用、部分模型支持图片输入、各家 Prompt Caching 机制与协议 Wire 格式大相径庭）。

Phase 11 正式引入 **多模型路由与能力声明解耦架构**：
1. **能力元信息声明 (`ModelCapabilities`)**：统一显式声明各 Provider / 模型的上下文窗口大小、工具调用支持、多模态视觉支持、Prompt Cache 模式以及思考链 (Reasoning) 要求，使上层 Agent 仅依赖统一语义，调用前自动执行能力护栏守卫。
2. **多协议适配层（封装显著协议差异）**：
   - **OpenAI-Compatible 族 (`AbstractOpenAiCompatibleClient`, `DeepSeekClient`, `OpenAiClient`)**：标准 `/v1/chat/completions`，基于 SSE `choices[0].delta.tool_calls` 参数分片累积。
   - **Anthropic Claude 原生协议 (`AnthropicClaudeClient`, `AnthropicSseParser`)**：原生 `/v1/messages` 协议，顶级 `system` 拆分注入、`input_schema` 工具定义、`tool_use` 与 `tool_result` 内容块转换、多事件类型 SSE (`message_start`, `content_block_delta` 等) 独立解析。
   - **本地私有 Ollama 协议 (`OllamaClient`)**：原生 `/api/chat` NDJSON 逐行流式解析，免 API Key 本地隐私优先支持。
3. **Provider 注册中心与模型工厂 (`LlmProviderRegistry`, `ModelDescriptor`)**：统一管理模型标识、别名匹配与凭据探测（环境变量与 `.env`），支持按需实例化。
4. **运行时无缝切换与上下文预算联动**：支持 `/model list`、`/model use <model>`、`/model status`，切换后动态联动重算 `TokenBudget` / `ContextAssembler` 上下文预算并提供超额预警。
5. **安全护栏与优雅降级**：当模型不支持工具（`supportsTools == false`）时，`ReactAgent`、`PlanExecuteAgent`、`TeamOrchestrator` 提前拦截拦截并告警，严禁向不支持工具的模型发起结构化请求。

---

## 2. 核心领域模型与接口设计

### 2.1 模型能力声明 (`ModelCapabilities`)
```java
public record ModelCapabilities(
        int maxContextWindow,
        boolean supportsTools,
        boolean supportsImageInput,
        boolean supportsPromptCaching,
        String promptCacheMode,
        boolean requiresReasoningEffort
)
```
- **预设工厂**：
  - `deepseekDefault()`: 128k 窗口，支持 Tools，支持 DeepSeek 前缀缓存 (`deepseek-prefix`)。
  - `openAiDefault()`: 128k 窗口，支持 Tools 与 Vision。
  - `claudeDefault()`: 200k 窗口，支持 Tools、Vision 与 Ephemeral 缓存。
  - `ollamaDefault()`: 32k 窗口，支持本地 Tools。
  - `textOnly(int window)`: 纯文本模型，`supportsTools = false`。

### 2.2 统一 Client 接口扩展 (`LlmClient`)
```java
public interface LlmClient extends AutoCloseable {
    ChatResponse stream(
            List<ChatMessage> messages,
            List<ToolDefinition> tools,
            StreamListener listener,
            CancellationToken cancellationToken) throws LlmException;

    default String providerName() { return "unknown"; }
    default String modelName() { return "unknown"; }
    default ModelCapabilities capabilities() { return ModelCapabilities.openAiDefault(); }
}
```

---

## 3. 协议适配器与 Wire Format 对照

| 协议维度 | OpenAI-Compatible (`DeepSeek` / `OpenAI`) | Anthropic Claude (`AnthropicClaudeClient`) | 本地 Ollama (`OllamaClient`) |
|---|---|---|---|
| **端点路径** | `/v1/chat/completions` | `/v1/messages` | `/api/chat` |
| **鉴权头** | `Authorization: Bearer <key>` | `x-api-key: <key>`<br>`anthropic-version: 2023-06-01` | 无需 Key / 本地直连 |
| **System 消息** | 放置于 `messages` 列表中的 `role: "system"` | 提取至请求顶层 `"system": "..."` 参数 | 放置于 `messages` 列表中的 `role: "system"` |
| **工具定义参数** | `tools[].function.parameters` | `tools[].input_schema` | `tools[].function.parameters` |
| **Tool Call 结构** | `assistant` 消息的 `tool_calls[]` | `assistant` 消息的 `content` 数组块 `type: "tool_use"` | `assistant` 消息的 `tool_calls[]` |
| **Tool Result 结构** | `role: "tool"`, `tool_call_id: "..."` | `role: "user"`, `content: [{"type":"tool_result", ...}]` | `role: "tool"`, `content: "..."` |
| **流式传输格式** | SSE (`data: { ... }`, `[DONE]`) | 原生多事件 SSE (`event: content_block_delta` 等) | 每行独立 JSON (NDJSON) |
| **用量统计获取** | 末尾 chunk 的 `usage` 节点 | `message_start` (输入) + `message_delta` (输出) | `done: true` chunk 的 `prompt_eval_count` / `eval_count` |

---

## 4. 运行时切换与预算安全联动

1. **指令交互**：
   - `/model` / `/model list`: 输出已注册 Provider、模型标识、窗口大小、工具支持状态及当前选中标记 `[✓]`。
   - `/model status`: 脱敏展示当前活动模型的 Provider、Model、窗口、工具与缓存元数据。
   - `/model use <query>`: 支持通过全量 ID (如 `anthropic:claude-3-5-sonnet-20241022`)、模型名 (`gpt-4o`)、Provider 名 (`deepseek`, `ollama`) 或别名 (`claude`, `4o-mini`) 进行模糊识别与切换。
2. **状态传播**：
   - 切换成功后，自动调用 `ra.setClient(newClient)`、`planAgent.setClient(newClient)`、`teamOrchestrator.setClient(newClient)` 与 `compactor.setClient(newClient)`。
3. **上下文重算与警示**：
   - 自动联动 `TokenBudget.updateContextWindow(newClient.capabilities().maxContextWindow())` 重新分配各层级配额。
   - 若当前会话历史 Token 估算超过新模型可用空间，输出黄色警告建议用户执行 `/compact` 或 `/clear`。
4. **能力护栏守卫**：
   - 若当前模型 `capabilities().supportsTools() == false` 且任务包含工具定义，Agent、Plan、Team 模式执行前立即拒绝启动，返回 `MODEL_UNSUPPORTED_TOOLS` 错误并提示切换模型，不向服务端发出任何请求。
