# Phase 17B: 图片多模态上下文预处理与视觉防御护栏验收评测报告

## 1. 评测概述

本报告记录 XhlCLI Phase 17B（图片多模态上下文预处理与视觉防御护栏）的自动化测试与真实端到端集成评测物证。全部测试基于 Java 21、AWT/ImageIO 图像渲染引擎、Mock WebServer 与真实 LLM 请求体序列化管线执行，参考本地 `../paicli` 核心设计规约落地，完全无 Python 等外部重型依赖。

- **评测时间**：2026-10-07
- **评测环境**：macOS (Apple Silicon), OpenJDK 21
- **测试结果**：522/522 通过（通过率 100%），覆盖 137 个测试类，无一失败与回退。

---

## 2. 评测矩阵与指标

| 评测维度 | 验证项 | 验证指标 | 状态 |
| :--- | :--- | :--- | :--- |
| **多模态消息模型** | `ContentPart` 与 `ChatMessage` | 支持 `text`, `imageBase64`, `imageUrl`；保持既有单测 100% 兼容 | ✅ PASS |
| **Alpha Flatten 防穿透** | 透明通道纯白背景合成 | `Graphics2D` 预填充白色背景，杜绝不同 Provider 底色反转与穿透 | ✅ PASS |
| **等比缩放引擎** | Bicubic 平滑重采样 | 长宽受限 `2000x2000` 内，等比缩小并记录显示宽高比 | ✅ PASS |
| **API 字节阈值兜底** | 5MB Base64 阈值多档压缩 | 超限依次采用 PNG -> 0.85 -> 0.70 -> 0.55 -> 0.40 -> 0.25 JPEG 压缩 | ✅ PASS |
| **坐标映射元数据** | 模型定位比例换算注入 | 自动生成 `[Image: source: ..., original WxH, displayed at WxH, Multiply coordinates by X]` | ✅ PASS |
| **剪贴板原生抓图** | macOS `osascript` + `sips` 与 AWT | 原生快速转存至 `~/.xhlcli/cache/`，支持图片尺寸与字节解析；Headless 优雅降级 | ✅ PASS |
| **输入宏与引用解析** | `@image:<path>`, `@image:path`, `@clipboard` | 正则隔离 CJK 全角标点、支持路径含空格与 `file://` 解码，注入视觉观察强提示 | ✅ PASS |
| **视觉模型序列化** | OpenAI / Anthropic 序列化 | OpenAI 输出 `image_url` block，Anthropic 输出 `image` base64 source block | ✅ PASS |
| **视觉防御护栏** | 非视觉模型（DeepSeek 等）防 400 | 检测 `!supportsVision` 时优雅剥离 Base64，降级为纯文本提示，杜绝 400 Bad Request | ✅ PASS |
| **历史图片修剪** | 长对话 Token 膨胀防御 | `pruneHistoricalImagePayloads` 在新轮次开始前剥离历史图片 Base64，保留元数据 | ✅ PASS |
| **Token 预算评估** | 多模态预算核算 | `TokenBudget` 每张图片估算 1000 tokens，精准控制滑动窗口 | ✅ PASS |
| **全量回归验收** | 全套 522 项单元与集成测试 | 522/522 绿灯，零回退、零崩溃 | ✅ PASS |

---

## 3. 详细测试物证

### 3.1 多模态核心模块测试明细

```text
[INFO] Running com.xhlcli.model.ChatMessageMultimodalTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in com.xhlcli.model.ChatMessageMultimodalTest
[INFO] Running com.xhlcli.image.ImageProcessorTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in com.xhlcli.image.ImageProcessorTest
[INFO] Running com.xhlcli.image.ClipboardImageTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in com.xhlcli.image.ClipboardImageTest
[INFO] Running com.xhlcli.image.ImageReferenceParserTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in com.xhlcli.image.ImageReferenceParserTest
[INFO] Running com.xhlcli.context.TokenBudgetMultimodalTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in com.xhlcli.context.TokenBudgetMultimodalTest
[INFO] Running com.xhlcli.llm.MultimodalClientSerializationTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in com.xhlcli.llm.MultimodalClientSerializationTest
[INFO] Running com.xhlcli.image.MultimodalGoldenTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in com.xhlcli.image.MultimodalGoldenTest
```

### 3.2 端到端集成金标物证 (`MultimodalGoldenTest`)

- **用例 1: `goldenEndToEndVisionWorkflowWithOpenAi`**
  1. 用户输入带引用宏：`请帮我重构这个模块的架构设计：@image:<path>`；
  2. `ImageReferenceParser` 识别半透明测试 PNG，完成白底合成、等比缩放并组装 `ChatMessage`，包含图片 Base64 与坐标换算元数据；
  3. `TokenBudget` 核算 Token 增加 1000 tokens 图片配额；
  4. `OpenAiClient` 识别视觉能力，将消息构建为 OpenAI 标准 `content` 数组，包含 `type: text` 与 `type: image_url`（`data:image/png;base64,...`）。
- **用例 2: `goldenEndToEndVisionDefenseWorkflowWithDeepSeek`**
  1. 输入相同的带图指令，目标模型切换为纯文本的 `DeepSeekClient`（`supportsVision = false`）；
  2. `buildRequest` 触发视觉防御护栏，剥离图片 Base64 并将其平铺降级为纯文本提示：`[当前 provider/model 不支持图片附件，已省略 1 张...]`；
  3. 拦截后发送的请求体为标量字符串 `"content": "..."`，绝对不包含 `image_url`，杜绝接口 400 Bad Request 崩溃。
- **用例 3: `goldenHistoricalImagePayloadPruning`**
  1. 模拟多轮长会话：第一轮输入包含大图附件，Token 占用超过 1000 tokens；
  2. 进入后续交互轮次后触发 `pruneHistoricalImagePayloads`，剥离历史轮次的大图 payload，保留 Image source 元数据说明；
  3. 修剪后 Token 消耗从 1000+ 骤降至 300 以下，成功消除长对话 Base64 累积膨胀隐患。

---

## 4. 全量回归测试汇总

```text
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
...
[INFO] Results:
[INFO] 
[INFO] Tests run: 522, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  17.719 s
[INFO] Finished at: 2026-10-07T11:30:34+08:00
[INFO] ------------------------------------------------------------------------
```

---

## 5. 结论

Phase 17B 图片多模态上下文预处理与视觉防御护栏按设计规约 100% 达成所有验收准则：
1. **输入语法兼收并蓄**：完美支持路径宏、空格路径、CJK 标点隔离、URI 解码与剪贴板实时取图；
2. **零 400 视觉防线**：对不支持视觉的模型实施绝对优雅降级，杜绝 Base64 乱入文本接口引发报错；
3. **图像处理严谨可靠**：Alpha 通道白底合成、`2000x2000` 等比缩放、5MB Base64 兜底约束、坐标映射元信息注入；
4. **长会话内存与 Token 控制**：历史轮次大图 Payload 自动修剪与单图 1000 tokens 精准预算；
5. **既有生态零破坏**：全量 522 个自动化测试 100% 绿灯。
