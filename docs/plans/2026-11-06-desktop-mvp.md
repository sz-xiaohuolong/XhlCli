# 桌面 MVP 实施计划

依据：[需求](../prd/desktop-codex-ui.md)、[设计](../specs/2026-11-06-desktop-mvp.md)。当前状态由 [PROGRESS](../PROGRESS.md) 维护，实际结果与人工步骤由[验收记录](../engineering/desktop-mvp-verification.md)维护。

| 任务 | 交付与范围 | 依赖 | 验证 |
|---|---|---|---|
| D1 | Java 桌面适配：独立会话、历史恢复、流式事件、审批取消、现有配置工具技能记忆 | 现有 Java 核心 | JUnit 与本地模型完整链路 |
| D2 | Side-History 差异查看与保守撤销 | D1 | 新增/修改/删除、用户冲突、Git 索引保护 |
| D3 | Electron 主进程、预加载桥、截图风格 React 主界面及设置 | D1 | Node 桥接测试、构建、桌面启动 |
| D4 | agent-desktop 真实 GUI 验收、文档与人工步骤 | D2、D3 | 当前构建的截图与交互证据 |
| D5 | 全量回归、代码审查、提交推送及远程 SHA 确认 | D4 | mvnw verify、npm test/build、git diff --check |

文件边界：新增 `desktop/`、`src/main/java/com/xhlcli/desktop/` 与对应测试；ReactAgent 只增加受校验的历史恢复接缝；必要时改进与桌面直接相关的现有接口。不改写既有 CLI 流程和其他任务成果。

构建使用 `./mvnw verify`、`cd desktop && npm ci && npm run build`。桌面运行和打包入口在 desktop/package.json 定义；若检查失败，保留失败证据后修复相应接缝。回退为本次提交前源码，用户桌面数据保留，禁止删除真实会话库。


## 计划确认与正文修复增量（2026-11-06）

范围依据：用户要求“帮我审批”不能替代计划同意，且修复 Markdown 展示。复用 Java 21、Electron 45.2.3、React 19.3.1、react-markdown 10.1 / remark-gfm 4 和现有构建命令，不增加依赖。实现顺序：定位审批与正文来源 → 用回归复现 → 分离计划决定及前端卡片 → 保留最终正文结构与真实终态 → 原生桌面交互和成品恢复验收。文件职责见当前技术设计，实际状态与证据统一见 [PROGRESS](../PROGRESS.md) 和 [验收记录](../engineering/desktop-mvp-verification.md#计划确认与-markdown-修复2026-11-06)。
