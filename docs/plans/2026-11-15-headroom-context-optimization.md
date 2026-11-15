# Headroom 工具正文压缩实施计划

需求：[REQ-H01～H05 / AC-H01～H11](../prd/headroom-context-optimization.md)。方案：[2026-11-15 设计](../specs/2026-11-15-headroom-context-optimization.md)。实际状态见 [PROGRESS](../PROGRESS.md)，实际结果见 [验收](../engineering/headroom-context-optimization-verification.md)。

## 1. 前置依赖与共同约定

已有用户实施授权，当前稳定提交 `c263c33`，本次原地工作，不改版本、不创建 Release。已有未提交的 .gitignore、AGENTS、PROJECT、RESEARCH、GLOSSARY、Phase 20 草稿需保留；只提交本任务改动。复用 Java 21、OkHttp、Jackson、JUnit 和 Maven Wrapper，无 Java 依赖变更。真实 Headroom 可用性需在 TASK-H05 现场核验，不能用模拟服务替代兼容性结论。

验证命令：局部 `./mvnw -q -Dtest=<相关测试> test`；最终 `./mvnw test`、`./mvnw package -DskipTests`。运行 Java 使用本机 OpenJDK 21+。不新增前端界面，仍需验证桌面 Java 入口及 `/context` 的实际行为。

## 2. 文件范围

| 操作 | 路径 | 职责 |
|---|---|---|
| 新增 | `src/main/java/com/xhlcli/context/` 的配置、接口、报告和 Headroom 实现 | 本机 HTTP、候选检查与回退 |
| 修改 | `agent/ReactAgent.java` | 预算前优化、最终检查、报告和取消 |
| 修改 | `cli/ChatBootstrap.java`、`cli/ChatLoop.java` | 装配与 `/context` |
| 修改 | `desktop/DesktopAgent.java`、`DesktopService.java`、`DesktopCommands.java` | 桌面装配和诊断元数据 |
| 新增/修改 | 对应 `context/`、`agent/`、`cli/`、`desktop/` 测试 | 行为和集成回归 |
| 修改 | `.env.example`、README、PROJECT、PROGRESS、TECH_DESIGN | 配置、导航与成立的当前事实 |
| 新增 | 本需求、方案、计划及 engineering 验收 | 单一正文与证据 |
| 新增（如需） | `scripts/` 的 Headroom 真实服务检查入口 | 可复现验证，不携带真实凭据 |

上述 Java 包路径均位于 `src/main/java/com/xhlcli/`；Plan、Team、后台与 Runtime 不装配优化器。

## 3. 任务及依赖

| 任务 | 交付与步骤 | 前置 | 验证与预期 |
|---|---|---|---|
| TASK-H01 | 保存需求、方案、计划；核对栈和接口 | 用户授权、当前源码 | 文档职责与编号对应，无阻塞决定 |
| TASK-H02 | 先写配置、正文保护、HTTP 失败和取消用例；实现最小压缩器 | H01 | 先观察失败，再局部通过；原文不污染诊断 |
| TASK-H03 | 先写预算恢复、内循环、历史保留、取消用例；接入 ReactAgent | H02 | 原先超限的可压缩请求能继续，仍超限不调用模型 |
| TASK-H04 | 先写 CLI、桌面装配与诊断用例；接配置和 `/context` | H03 | 两入口生效，重启可读，默认行为保留 |
| TASK-H05 | 启动真实 Headroom，核验真实接口和完整链路；审查 diff、运行检查 | H04，真实服务可启动 | 区分模拟 Provider 和真实 Headroom，记录不足与收益口径 |
| TASK-H06 | 同步当前设计与进度，人工步骤、业务和代码讲解；提交推送并核对引用 | H05 | 可照做验收，只提交本任务文件，远程引用一致 |

计划启动时可开始 H01；其他任务按依赖和 PROGRESS 的真实结果推进。每项任务恢复方式均为关闭开关或恢复本任务代码，不丢弃用户既有修改。行为故障先加复现测试，再修复。

## 4. 需求与验证对应

| AC | 场景与检查入口 | 任务 |
|---|---|---|
| H01～H02 | 配置默认值、拒绝外部地址和非法参数；两个入口 | H02 / H04 |
| H03～H04 | 真实 HTTP 序列化、正文压缩、消息关联、受保护字段和回取标记 | H02 |
| H05 | ReactAgent 原历史超预算、压缩可恢复；最终含 schema 仍超预算 | H03 |
| H06～H07 | HTTP 错误、超时、畸形、体积、取消与运行超时 | H02 / H03 |
| H08～H09 | CLI /context、桌面完整轮次与重启、模型信息 | H04 |
| H10～H11 | 真实服务联调、启动说明、关闭恢复 | H05 / H06 |

实际检查结果只在 engineering 验收记录，不在本表复制。

## 5. 交付与恢复

按项目既有约定提交并推送当前远程分支，不打 Tag、不发布版本、不替换本机已安装应用。先审查变更与测试证据，确认没有混入用户修改，再提交推送并核对远程 SHA。任何检查失败先修复或如实记录影响；不把真实模型未测试写成模型质量通过。

关闭开关后原始历史可直接续聊。Headroom 由操作者关闭；不自动修改服务环境或清理用户缓存。没有数据库迁移和压缩历史恢复步骤。

## 6. 里程碑与计划变化

H01 完成后进入实现；H02～H04 形成两个入口的可用闭环；H05 的实际验证支持交付判断；H06 完成文档、远程同步和用户说明。实际日期、偏差及阻碍统一记录在 PROGRESS 与验收；需求变化写回 PRD，技术变化写回方案。
