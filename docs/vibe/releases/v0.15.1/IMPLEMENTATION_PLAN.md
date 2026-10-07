# Release v0.15.1 Implementation Plan

<!-- Source: plans/2026-10-07-phase-17-runtime-and-multimodal.md -->

# Phase 17 实施计划：Runtime 与多模态

> **实施目标：** 按照 PRD 与技术设计规约，分两阶段交付 Phase 17：  
> - **Phase 17A（已完成，v0.15.0）：** 后台持久任务队列与 Localhost Runtime API；  
> - **Phase 17B（当前进行中，v0.15.1）：** 图片多模态上下文预处理与视觉防御护栏。  
> **前置门禁：** Human Approval Gate（技术设计与任务边界获用户批准）  
> **目标发布：** `v0.15.1`

---

## 1. 任务分解清单 (WBS)

### Phase 17A：后台持久任务与 Runtime API (100% 已交付)
- [x] **Task 1: 任务领域模型与状态机 (`com.xhlcli.runtime.task`)** (已完成)
- [x] **Task 2: SQLite 任务持久化与调度引擎 (`DurableTaskManager`)** (已完成)
- [x] **Task 3: 本地持久事件存储与游标读取 (`RuntimeThreadStore`)** (已完成)
- [x] **Task 4: 本地安全 Runtime API 服务 (`RuntimeApiServer`)** (已完成)
- [x] **Task 5: 终端指令、命令格式化与 Tab 智能补全** (已完成)
- [x] **Task 6: Phase 17A 端到端集成验收、Living Docs 与发布 (`v0.15.0`)** (已完成)

---

### Phase 17B：图片多模态上下文预处理 (100% 已交付)

### Task 7: 多模态消息模型与内容分片 (`ContentPart` + `ChatMessage`)
- [x] 创建 `ContentPart` record (`type`, `text`, `imageBase64`, `imageUrl`, `mimeType`)，提供 `text()`, `imageBase64()`, `imageUrl()`, `isText()`, `isImage()` 辅助方法；
- [x] 扩展 `ChatMessage` 增加 `List<ContentPart> contentParts` 字段，提供重载构造函数与 `hasContentParts()`, `hasImages()` 判定方法，保证既有 500 个单测全部兼容；
- [x] 编写测试 `ChatMessageMultimodalTest`。

### Task 8: 图片预处理与质量压缩引擎 (`ImageProcessor`)
- [x] 实现 `ImageProcessor`：
  - 支持 PNG, JPEG, GIF, WEBP 图片格式识别与读取；
  - 限制源文件不超过 50MB，处理后 Base64 严格受限在 5MB API 阈值内；
  - 检测透明 Alpha 通道，采用纯白背景合成平铺（Alpha Flatten），防止各模型 Provider 底色穿透重映射异常；
  - 尺寸缩放：长宽上限 `2000x2000` 等比双三次插值（Bicubic）平滑重采样；
  - 渐进式多档质量压缩：超限时依次尝试无损 PNG -> 0.85 -> 0.70 -> 0.55 -> 0.40 -> 0.25 JPEG 压缩；
  - 自动生成尺寸、原始路径与坐标映射比例元数据（`[Image: source: ..., original WxH, displayed at WxH, Multiply coordinates by X to map to original image]`）；
- [x] 编写测试 `ImageProcessorTest`（覆盖格式识别、白底平铺、等比缩放、JPEG 降级与元信息生成）。

### Task 9: 系统剪贴板抓取与平台优化 (`ClipboardImage`)
- [x] 实现 `ClipboardImage`：
  - macOS 专有优化：利用原生 `/usr/bin/osascript` 提取剪贴板 `«class PNGf»` 或 `«class TIFF»`，结合 `/usr/bin/sips` 落地为本地缓存文件（`~/.xhlcli/cache/clip-*.png`）；
  - 跨平台 Java AWT 剪贴板兜底处理，Headless 环境安全降级并提示清晰错误信息；
  - 实现 `describe(Path)` 生成人类可读图片尺寸与大小描述；
- [x] 编写测试 `ClipboardImageTest`。

### Task 10: 输入指令与引用解析器 (`ImageReferenceParser`)
- [x] 实现 `ImageReferenceParser`：
  - 正则模式匹配 `@image:<path>`（含空格）、`@image:path`（自动排除 CJK 全角标点）及 `@clipboard`；
  - 支持 `file://` URI 解析与宽容度 UTF-8 percent-decode；
  - 剥离输入文本中的图片宏 token，提取纯文本指令；
  - 注入提示词：“图片已作为图片附件附加。请直接观察本轮图片内容...如果当前图片与历史上下文冲突，以当前图片为准”；
  - 组装多模态 `ChatMessage`（文本说明 + 元数据 + 图片 ContentPart）；
- [x] 编写测试 `ImageReferenceParserTest`。

### Task 11: LLM 请求体序列化与视觉防御护栏
- [x] 扩展各 Provider 客户端请求体序列化：
  - `OpenAiClient` 与 `DeepSeekClient`：当 `msg.hasImages()` 时序列化为 `[{"type":"text", ...}, {"type":"image_url", ...}]`；
  - 视觉能力防线：检查当前模型的 `ModelCapabilities.supportsVision()`，若不支持视觉输入，则优雅过滤 Base64 数据并降级为纯文本提示（例如 `[图片未传递: 当前模型不支持视觉输入，建议使用 /model use 切换多模态模型]`），绝不触发 Provider 400 报错；
- [x] 扩展 `TokenBudget` 支持对图片 ContentPart 的估算（默认 1000 tokens）；
- [x] 编写测试 `MultimodalClientSerializationTest` 与 `TokenBudgetMultimodalTest`。

### Task 12: 端到端集成验收、Living Docs 与发布
- [x] 编写端到端验收套件 `MultimodalGoldenTest`：全链路验证输入包含 `@image:...` 与 `@clipboard` 时，消息解析 -> 图片预处理 -> 视觉模型请求序列化与非视觉模型降级；
- [x] 运行全量回归测试 `mvn clean test` 保持 100% 绿灯（522 项测试）；
- [x] 产出工程评测报告 `docs/engineering/multimodal-evaluation.md`；
- [x] 更新 Living Docs (`PROJECT.md`, `ROADMAP.md`, `TECH_DESIGN.md`, `CHANGELOG.md`, `AGENTS.md`, `README.md`)；
- [x] 升级版本为 `0.15.1-SNAPSHOT`，执行 Git 提交、打 Tag `v0.15.1` 并 Push。

---

## 2. 交付准则 (Definition of Done)
1. **输入语法兼收并蓄**：支持 `@image:<path>`、`@image:path`、`file://` 以及 `@clipboard` 抓图；
2. **零 400 视觉防线**：对不支持视觉的模型实施绝对优雅降级，杜绝 Base64 乱入文本接口引发报错；
3. **图像处理严谨可靠**：Alpha 通道白底合成、`2000x2000` 等比缩放、5MB Base64 兜底约束、坐标映射元信息注入；
4. **全量回归 100% 绿灯**：所有既有 500 项测试无任何回退，新增测试全部通过。
