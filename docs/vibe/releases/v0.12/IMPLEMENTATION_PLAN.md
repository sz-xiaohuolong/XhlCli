# Release v0.12 Implementation Plan

<!-- Source: plans/2026-10-05-phase-14-skills-and-prompts.md -->

# Phase 14：Skill 与 Prompt 分层实施路线与计划 (Implementation Plan)

> **文档性质：** 阶段实施任务清单与执行蓝图  
> **计划日期：** 2026-10-05  
> **目标版本：** `v0.12.0`  
> **对应 PRD：** [`docs/prd/phase-14-skills-and-prompts.md`](../prd/phase-14-skills-and-prompts.md)  
> **对应 Spec：** [`docs/specs/2026-10-05-phase-14-skills-and-prompts-design.md`](../specs/2026-10-05-phase-14-skills-and-prompts-design.md)  
> **执行准则：** 必须等待用户明确确认批准（Human Approval Gate）后方可开始编码！

---

## 1. 阶段目标与交付范围

构建高效、解耦且具备渐进式披露特性的 Prompt 分层治理与按需 Skill 子系统：
1. **Prompt 分层组装引擎 (`com.xhlcli.prompt`)**：实现 7 层确定性组装（基础身份、不可覆盖系统安全底线、Agent 模式、运行时环境、项目规则与长期记忆、Skill 紧凑索引、上下文与交接准则），支持各层独立预算与安全不可穿透保证；
2. **Skill 元数据解析与沙箱引用 (`com.xhlcli.skill`)**：实现 `SKILL.md` YAML Frontmatter 解析（`name`, `description`, `allowed-tools` 等），支持语法错误安全隔离，实现 `SkillReferenceResolver` 严格防止相对路径逃逸（`../` 越界拦截）；
3. **三层覆盖注册中心 (`SkillRegistry`)**：实现 `BUILTIN` < `USER` (`~/.xhlcli/skills/`) < `PROJECT` (`.xhlcli/skills/`) 三层扫描与同名完全覆盖，支持动态启停、热重载与紧凑索引生成；
4. **内置 Skill 与按需加载工具**：内置 `git-feature-workflow` (代码工程工作流) 与 `web-research` (网络深度调研)，实现 `load_skill` 本地工具并注册到 `ToolRegistry`，实现单 Run 自动去重注入；
5. **终端管理指令与脱敏导出**：扩展终端 `/skill [list|show|enable|disable|reload]` 和 `/prompt [show|export]` 指令，实现完整系统 Prompt 的来源分层标注与敏感凭据脱敏导出；
6. **自动化回归与 Golden Test 验收**：新增 30+ 项专项测试与 Golden Set 测试，保障全量测试 100% 绿灯，产出评测报告 `docs/engineering/skill-and-prompt-evaluation.md`，同步更新全套 Living Docs，发布 Tag `v0.12.0`。

---

## 2. 详细任务分解 (Task Breakdown)

### Task 1: Prompt 分层体系与组装引擎 (`com.xhlcli.prompt`)
- [ ] **1.1** 创建 `com.xhlcli.prompt.PromptLayer` 枚举：
  - `BASE_IDENTITY` (基础身份与语言)
  - `SAFETY_POLICY` (不可覆盖安全规则与工具使用准则)
  - `AGENT_MODE` (当前模式专属指引)
  - `RUNTIME_CONTEXT` (时间、模型能力、工作区环境)
  - `PROJECT_RULES_AND_MEMORY` (项目规约与检索记忆)
  - `SKILL_INDEX` (紧凑 Skill 索引)
  - `ACTIVE_SKILLS` (当前 Run 按需激活的 Skill 正文)
  - `HANDOVER` (上下文窗口与交接要求)
- [ ] **1.2** 创建 `com.xhlcli.prompt.PromptSource` (`BUILTIN`, `USER`, `PROJECT`) 与 `PromptBlock` (记录内容、来源、是否不可覆盖及字符数)；
- [ ] **1.3** 创建 `com.xhlcli.prompt.LayerBudgetConfig`（定义各层软硬预算配额）与 `LayeredPromptAssembler` 核心组装器：
  - 严格保持 7 层逻辑顺序；
  - 强制确保 `SAFETY_POLICY` 绝对不可被用户或项目级覆盖；
  - 超出预算时按层平滑截断，记录截断状态；
- [ ] **1.4** 编写 `LayeredPromptAssemblerTest`：覆盖基础组装、同名覆盖、恶意覆盖安全层拦截、以及超额预算截断测试。

### Task 2: Skill 元数据解析与目录沙箱 (`com.xhlcli.skill`)
- [ ] **2.1** 创建领域模型 `SkillMetadata` (name, description, allowedTools, author, tags)；
- [ ] **2.2** 创建 `SkillDefinition` (metadata, source, directoryPath, skillMdPath, instructions, parseError, isEnabled)；
- [ ] **2.3** 创建 `SkillParser`：
  - 读取 `SKILL.md`，支持标准 `---` 分割的 YAML frontmatter 提取；
  - 严格校验 `name` 规范（小写字母、数字、短横线）；
  - 遇到未知标签或格式异常时，将错误记录在 `parseError`，不抛出异常崩溃主进程；
- [ ] **2.4** 创建 `SkillReferenceResolver`：
  - 限制仅在 Skill 目录及其子目录下读取相对路径文件；
  - 使用 `Path.normalize()` 防范 `../` 越界攻击，违规抛出 `SecurityException`；
- [ ] **2.5** 编写 `SkillParserTest` 与 `SkillReferenceResolverTest`。

### Task 3: Skill 注册中心、三层覆盖与紧凑索引 (`com.xhlcli.skill`)
- [ ] **3.1** 创建 `SkillRegistry` 注册中心：
  - 扫描路径：内置资源 (`classpath:/skills/`)、用户级 (`~/.xhlcli/skills/`)、项目级 (`.xhlcli/skills/`)；
  - 同名合并策略：`PROJECT` > `USER` > `BUILTIN`，记录实际来源；
  - 维护内存中启用/禁用状态集合；
  - 提供 `scanAndReload()` 支持运行时热更新；
- [ ] **3.2** 创建 `SkillIndex` 索引生成器：
  - 仅拼接启用的 Skill 名称和简要描述；
  - 严格控制总长度不超过预算（如 2000 字符），超出时按稳定顺序截断并追加提示；
- [ ] **3.3** 编写 `SkillRegistryTest`：测试三层扫描合并、同名覆盖优先顺序、启停切换及动态重载。

### Task 4: 内置 Skill 库与按需加载工具 (`com.xhlcli.skill.tool`)
- [ ] **4.1** 创建内置 Skill 静态资源：
  - `git-feature-workflow`: 标准 Git 分支创建、修改验证、原子提交与 PR 描述规范；
  - `web-research`: 结构化检索、交叉对比验证与技术选型报告规范；
- [ ] **4.2** 创建本地工具 `com.xhlcli.skill.tool.LoadSkillTool` (`load_skill`)：
  - 参数：`name` (String, required)；
  - 校验 Skill 是否存在且启用；
  - 触发会话中的 Skill 激活状态变更；
  - 相同 Run 内部自动去重，若已激活返回轻量提示；
- [ ] **4.3** 注册 `load_skill` 进入 `ToolRegistry`，并在 `ApprovalPolicy` 中标记为只读免审批工具；
- [ ] **4.4** 编写 `LoadSkillToolTest` 与 `SkillToolIntegrationTest`。

### Task 5: 终端指令扩展 (`/skill`, `/prompt`) 与脱敏导出 (`com.xhlcli.cli`)
- [ ] **5.1** 扩展 `ChatCommand` 与 `ChatCommandParser` 支持 `/skill` 与 `/prompt`；
- [ ] **5.2** 创建 `com.xhlcli.prompt.PromptExporter`：
  - 格式化输出 System Prompt 的分层全貌（含各 Block 来源标注与截断指标）；
  - 全量调用 `SecretRedactor` 抹除 API Key 与敏感凭据；
- [ ] **5.3** 在 `ChatLoop` 中挂载：
  - `handleSkill`: 支持 `list`, `show <name>`, `enable <name>`, `disable <name>`, `reload`；
  - `handlePrompt`: 支持 `show` (控制台打印脱敏预览), `export <file>` (导出到指定文件)；
- [ ] **5.4** 更新 `PlainRunRenderer` 中的 `/help` 菜单；
- [ ] **5.5** 编写 `SkillPromptCliTest` 模拟终端命令测试。

### Task 6: 核心集成、Golden Test 验收、物证与发布
- [ ] **6.1** 重构 `ChatBootstrap`：接入 `LayeredPromptAssembler`、`SkillRegistry` 与 `LoadSkillTool`，重构 `ContextAssembler` 适配分层提示输入；
- [ ] **6.2** 编写 `PromptGoldenTest`：锁定固定场景与上下文，基准比对 Prompt 分层组装的一致性与安全层存在性；
- [ ] **6.3** 运行全量 `./mvnw clean verify`，确保所有测试 100% 绿灯；
- [ ] **6.4** 产出评测物证 `docs/engineering/skill-and-prompt-evaluation.md`；
- [ ] **6.5** 同步更新 Living Docs：`PROJECT.md`, `ROADMAP.md`, `TECH_DESIGN.md`, `specs/README.md`, `plans/README.md`, `CHANGELOG.md`, `AGENTS.md`, `README.md`；
- [ ] **6.6** 升级 `pom.xml` 为 `0.12.0-SNAPSHOT`，执行 Git 提交、打 Tag `v0.12.0` 并推送到 GitHub。
