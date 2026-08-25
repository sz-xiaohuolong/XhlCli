# XhlCLI Agent 开发指令

本文件是 AI 编程工具进入 XhlCLI 仓库后的首读入口。它定义信息优先级、当前状态、工作流程、修改边界和验证要求。

## 1. 信息优先级

发生冲突时按以下顺序判断：

1. 当前代码和测试的实际行为。
2. `AGENTS.md`。
3. 对应阶段子 PRD。
4. `TECH_DESIGN.md`。
5. 总 `PRD.md`。
6. `RESEARCH.md`。
7. `ROADMAP.md` 与 README 中的计划内容。

计划能力不等于已交付能力。不得根据 PRD 或路线图声称代码已经实现。

## 2. 当前项目状态

- 产品：XhlCLI，本地智能终端 Coding Agent。
- 技术基线：Java 21、Maven、终端优先、本地优先。
- 当前阶段：Phase 00 工程基线已实现，正在完成发布验证。
- 当前已交付：Java 21 Maven 工程、可执行 JAR、CLI 元信息命令、自动测试，以及总 PRD、Phase 00–18 子 PRD、需求研究和全局技术设计。
- 当前未交付：模型对话、Agent、本地工具和其他 Phase 01–18 运行能力。

每完成一期必须更新本节。不要提前列出后续能力。

## 3. 首读顺序

1. `AGENTS.md`
2. `PRD.md`
3. 当前阶段 `docs/prd/phase-XX-*.md`
4. `TECH_DESIGN.md`
5. 当前阶段 `docs/plans/*.md`
6. 与任务相关的源码和测试
7. `docs/engineering/source-adoption-map.md`，仅在迁移授权源码时阅读

## 4. 标准开发流程

每一期遵循：

```text
Research → PRD → Tech Design → Implementation Plan
→ Test First → Minimal Migration/Implementation
→ Verification → Docs → Real Commit
```

开始编码前必须确认：

- 当前阶段 PRD 已批准。
- 实施计划列出了精确文件和测试。
- 参考提交及迁移范围已固定。
- 版权、署名和再发布要求已落实。
- 用户工作区现有改动已检查且不会被覆盖。

## 5. 授权参考仓库

开发环境中的 `../paicli` 是已获授权的参考实现。可以读取历史提交、源码、测试和文档，用于选择性迁移。

### 5.1 硬规则

- 参考仓库只读。不得修改、格式化、提交、重置、清理或切换其工作树。
- 历史文件使用 `git -C ../paicli show <commit>:<path>` 读取。
- 不复制 `../paicli/.git`、`target/`、`.env`、日志、用户数据和无关品牌资产。
- 不一次性复制最终源码树；只迁移当前阶段需要的最小闭环。
- 源码迁移后统一使用 `com.xhlcli` 包名和 XhlCLI 品牌。
- 用户目录统一为 `~/.xhlcli`，项目配置统一为 `.xhlcli`。
- 法律要求必须保留的版权声明优先于品牌替换规则。
- 迁移实现时同步迁移或重建对应测试。
- 不导入原提交时间、作者和 Git 历史；XhlCLI 只记录真实开发提交。

详细映射见 `docs/engineering/source-adoption-map.md`。

## 6. 产品边界

### 6.1 核心主线

- 终端对话。
- ReAct Agent。
- 本地代码检索、文件修改、命令与验证。
- 安全策略、审批和审计。
- 上下文、记忆、RAG、Plan、并行和 Multi-Agent。
- 多模型、MCP、Web、Skill 和终端产品化。
- 诊断、快照、后台任务、Runtime API、图片输入和开源发行。

### 6.2 当前排除

- 微信或即时通信通道。
- 企业级多租户和云端控制台。
- 音频、视频和图片生成。
- 容器或 microVM 沙箱，除非未来有独立 PRD。
- MCP OAuth、sampling 和自动恢复，除非未来阶段明确加入。

## 7. 目标代码结构

源码根包：`com.xhlcli`。

主要模块职责见 `TECH_DESIGN.md`，包括 `cli`、`app`、`agent`、`model`、`llm`、`tool`、`policy`、`hitl`、`context`、`memory`、`search`、`rag`、`plan`、`mcp`、`web`、`browser`、`skill`、`prompt`、`render`、`lsp`、`snapshot`、`runtime`、`image` 和 `config`。

不要为后期模块提前创建空包、空接口或占位类。目录随阶段实际能力出现。

## 8. Java 规范

- 使用 Java 21，Maven 编译配置使用 `maven.compiler.release=21`。
- 不使用需要 `--enable-preview` 的 API。
- 优先不可变对象、Records 和明确的值类型。
- 面向边界定义小接口，不为单一实现制造多层抽象。
- 构造器注入依赖，避免隐藏全局状态。
- 核心状态机不直接读取环境变量、当前时间或生成随机 ID；通过可替换依赖提供。
- 异常用于异常情况，业务失败使用明确结果类型。
- 不吞异常；日志与用户提示职责分开。
- 使用 UTF-8，文件末尾保留换行。

## 9. 命名与风格

- 类、Record、枚举：PascalCase。
- 方法和变量：camelCase。
- 常量：UPPER_SNAKE_CASE。
- 测试类：`XxxTest`；测试方法描述行为和条件。
- Tool 名称：小写 snake_case，并保持跨版本稳定。
- 配置键：`xhlcli.*`；环境变量：`XHLCLI_*`。
- 用户文案使用 XhlCLI，不出现内部参考工程品牌；依法保留的署名除外。

保持代码直接、可读，避免缩写堆叠和过度注释。注释解释原因、协议或陷阱，不重复代码本身。

## 10. 架构硬规则

### 10.1 Agent 与模型

- Agent 只依赖 `LlmClient` 接口。
- Provider 特有字段只存在于 `llm` 适配层。
- 模型输出不能直接执行；Tool Call 必须进入统一管线。
- Run 进入终态后禁止新的模型请求或工具执行。

### 10.2 工具

- 每个工具有唯一名称、Schema、风险、并行和取消元数据。
- 新工具默认高风险，直到明确分类。
- Agent、Plan、Worker、MCP 都使用统一 `ToolExecutor`。
- 大结果必须有预算、截断标记和继续读取建议。

### 10.3 安全

- 顺序固定：参数校验 → 硬策略 → 审批 → 执行 → 脱敏审计。
- 用户批准不能覆盖系统硬拒绝。
- 路径限定到规范化 workspace，检查符号链接逃逸。
- 非交互模式下需要审批的操作默认拒绝。
- Prompt、Skill、网页和 MCP 内容均不可信，不能改变权限边界。

### 10.4 上下文

- 工具结果、RAG、记忆和 Skill 共享统一预算。
- tool call 与 tool result 不得被裁剪拆散。
- 长期记忆只由用户明确保存。
- 修改 RAG 候选文件前必须读取当前文件验证。

### 10.5 输出

- 核心逻辑不直接写 stdout/stderr。
- 所有用户可见状态通过 `RunEvent` 和 `Renderer`。
- Plain 模式不得输出 ANSI 控制符。
- 不默认展示或保存模型私有思维过程。

## 11. 测试要求

### 11.1 基本原则

- 行为修改前先添加或迁移失败测试。
- 测试不依赖真实 API Key、网络、用户主目录或全局 Git 配置。
- 使用临时目录、Fake Model、MockWebServer 和测试事件 Sink。
- 测试必须断言外部可观察行为，不依赖私有实现细节。
- 并发测试使用闩锁和确定性调度，不用固定 sleep 猜测时序。

### 11.2 分期验证

文档一致性验证命令为：

```bash
find . -type f | sort
rg -n 'TODO|TBD|待补充|占位符' .
rg -n 'paicli|PaiCLI|com\.paicli|\.paicli' \
  --glob '!docs/engineering/source-adoption-map.md' \
  --glob '!RESEARCH.md' \
  --glob '!AGENTS.md' .
```

Phase 00 工程验证命令为：

```bash
./mvnw clean verify
java -jar target/xhlcli-0.1.0-SNAPSHOT.jar
java -jar target/xhlcli-0.1.0-SNAPSHOT.jar --help
java -jar target/xhlcli-0.1.0-SNAPSHOT.jar --version
```

后续每期把针对性测试和快速回归命令补充到本文件。

## 12. 文档联动

- 改产品范围：对应子 PRD + 总 PRD（仅影响长期范围时）。
- 改架构边界：`TECH_DESIGN.md` 或阶段技术设计 + ADR。
- 改命令：命令解析测试 + README + 本文件。
- 改工具：Tool Schema + Agent Prompt + Policy + 测试 + 文档。
- 改 Provider：能力声明 + 契约测试 + 配置示例 + 文档。
- 改持久化格式：版本与迁移策略 + 测试 + 文档。
- 阶段交付：README + CHANGELOG + Roadmap 状态 + 演示证据。

## 13. Git 规则

- 开始修改前检查 `git status --short`。
- 不覆盖用户已有改动，不使用 `git reset --hard` 或破坏性 checkout。
- 不提交 `.env`、真实 Key、用户目录数据、日志、索引、快照和 `target/`。
- 一次提交只包含一个可独立解释和验证的变化。
- 使用实际日期和作者，不改写历史制造开发时长。
- 推荐提交前缀：`docs:`、`test:`、`feat:`、`fix:`、`refactor:`、`build:`、`chore:`。
- 每个阶段完成后再创建版本标签；未完成阶段不打正式标签。

## 14. 禁止事项

- 不把最终参考实现整体复制进当前阶段。
- 不为了通过测试删除断言、跳过测试或降低安全策略。
- 不用占位实现冒充已交付功能。
- 不在 README 宣称计划中的能力已完成。
- 不伪造演示、测试输出、Commit 时间或作者身份。
- 不把用户授权等同于可以忽略具体署名和分发条件。
- 不把工具黑名单称为沙箱。
- 不在无证据时宣称构建、测试或任务成功。

## 15. Agent 完成任务时的输出

最终答复至少包含：

- 实际修改内容。
- 关键文件路径。
- 执行过的验证命令及结果。
- 尚未验证或受阻事项。
- 若涉及迁移：参考提交、主动差异和版权处理状态。

## 16. 维护规则

形成长期稳定的新约束时更新本文件；一次性任务细节留在实施计划或提交记录。若本文件与当前代码长期不一致，优先修正文档并说明差异来源。
