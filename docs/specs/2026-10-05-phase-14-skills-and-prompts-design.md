# Phase 14：Skill 与 Prompt 分层架构设计规范 (Technical Design Specification)

> **文档状态：** 提案中 (Proposed)  
> **设计日期：** 2026-10-05  
> **目标版本：** `v0.12.0`  
> **对应 PRD：** [`docs/prd/phase-14-skills-and-prompts.md`](../prd/phase-14-skills-and-prompts.md)  
> **实现状态：** 待用户批准实施

---

## 1. 架构目标与四维交付定位

在复杂的软件工程任务中，Agent 既需要掌握稳定的通用工程规范与安全底线，又需要针对特定项目技术栈、团队惯例乃至特定专项任务（如发布工作流、性能剖析、Web 调研）提供专家级引导。

如果将所有规约全部硬编码在 System Prompt 中，会导致：
1. **Prompt 恶性膨胀 (Context Bloat)**：消耗宝贵的上下文预算，降低模型推理效率并增加成本；
2. **注意力稀释 (Attention Degradation)**：无关提示干扰核心任务逻辑，引发工具误调与越权；
3. **灵活性缺失**：无法支持团队提交项目专用规则和个人自定义偏好。

Phase 14 构建基于 **渐进式披露 (Progressive Disclosure)** 与 **三层覆盖 (Three-Tier Overrides)** 的 Prompt 分层与按需 Skill 治理子系统。

### [What] 职责与定位 (Role & Boundary)
- **拓扑位置**：处于 Agent 推理调度核心层（`com.xhlcli.context` / `com.xhlcli.agent`）与本地工具执行层之间，是构建 LLM System Message 与扩展专家经验的唯一枢纽。
- **输入输出**：
  - 输入：运行模式、模型能力、工作区环境、项目规则、长期记忆、已注册 Skill 集合及模型发起的 `load_skill` 调用。
  - 输出：结构化、分层受控、带来源标识且脱敏的 System Prompt，以及按需激活的上下文扩展注入。
- **职责边界**：
  - **负责**：Prompt 7 层确定性组装、安全层不可覆盖保障、三层 Skill 发现与覆盖冲突处理、按需加载与去重、引用安全隔离、脱敏导出审计。
  - **不负责**：Skill 脚本的自动隐式执行（必须显式经过普通工具审批流水线）；不负责在线 Skill 市场分发；不支持复杂的任意 YAML 高级语法。

---

## 2. 系统拓扑与核心流转链路

### [How] 主干流转链路 (Happy Path)

系统分为 **启动期发现与索引组装** 和 **运行期按需动态加载** 两大阶段：

```text
+-----------------------------------------------------------------------------------------+
|                                  启动与初始化阶段                                        |
+-----------------------------------------------------------------------------------------+
    |
    +---> 1. Skill 扫描器 (SkillRegistry)
    |     扫描 classpath:/skills/ (内置) -> ~/.xhlcli/skills/ (用户) -> .xhlcli/skills/ (项目)
    |     解析 SKILL.md YAML Frontmatter (name, description, allowed-tools)
    |     按同名完全覆盖策略合并，生成当前生效 Skill 列表
    |
    +---> 2. 分层 Prompt 组装器 (LayeredPromptAssembler)
          - Layer 1: 基础身份与语言 [BUILTIN/OVERRIDE]
          - Layer 2: 核心安全规则与工具约束 [BUILTIN - IMMUTABLE 绝对不可覆盖]
          - Layer 3: Agent 运行模式 [ReAct / Plan-and-Execute / Team]
          - Layer 4: 运行时环境上下文 [Date, ModelCapabilities, Workspace]
          - Layer 5: 项目规则与记忆检索 [Project Rules, Long-Term Memory]
          - Layer 6: 紧凑 Skill 索引 [仅含 name + description，受限预算]
          - Layer 7: 上下文管理与交接准则
          输出完整 System Message

+-----------------------------------------------------------------------------------------+
|                                 任务执行与按需加载阶段                                   |
+-----------------------------------------------------------------------------------------+
    |
    +---> 1. 用户输入意图: "帮我按照团队规范执行 Git 分支发布流程"
    +---> 2. Agent 匹配 Skill 索引中 git-feature-workflow 的 description
    +---> 3. Agent 发起 Tool Call: load_skill(name="git-feature-workflow")
    +---> 4. LoadSkillTool 执行:
          - 校验 Skill 是否启用并加载 instructions
          - 标记为当前 Run 激活状态（去重防重复加载）
          - 提取 references/ 相对资料（受限在 Skill 根目录内防逃逸）
          - 返回 Skill 规约与步骤指导
    +---> 5. Agent 遵循 Skill 流程执行后续工具调用（脚本经 execute_command 走正常审批审计）
```

---

## 3. 架构设计推演与权衡

### [Why] 架构设计推演 (Counterfactual / What-If)

1. **为什么必须采用“索引 + 按需加载”，而不能启动时把全部 Skill 正文加载进 System Prompt？**
   - **反事实推演**：如果系统安装了 20 个工程 Skill（如 Spring Boot 调优、React 重构、Git 流水线、Docker 排错、Kubernetes 部署等），每个 Skill 正文平均 300 行（约 1500 tokens），全量加载将直接消耗 30,000 tokens 的固定系统上下文！在小上下文模型或大项目深度对话中，这将迅速导致上下文压缩截断甚至 OOM，且无关规则将严重干扰模型对当前具体任务的推理。
   - **设计选择**：启动时仅提取 `name` 和 `description` 生成紧凑索引（20 个 Skill 仅需约 500 tokens）；当且仅当任务匹配时，通过 `load_skill` 单次加载正文，实现精准的渐进式披露。

2. **为什么安全规则层 (`SAFETY_POLICY`) 必须设置为绝对不可覆盖 (Immutable)？**
   - **反事实推演**：若允许项目级或用户级提示完全覆盖 System Prompt，恶意代码仓库中可能植入 `.xhlcli/rules.md` 甚至通过 Prompt Injection 声明“忽略所有本地工作区路径保护、关闭 HITL 人工审批”。若没有强制不可穿透的安全层，Agent 将失去所有防御能力。
   - **设计选择**：`LayeredPromptAssembler` 明确标记安全层为 `IMMUTABLE`。无论外界如何配置覆盖，核心工作区边界、安全命令白名单、只读限制与敏感风控永远作为最高优先级强行注入。

3. **为什么 Skill 附带的脚本绝对不能自动隐式执行？**
   - **反事实推演**：如果 Skill 声明后只要模型匹配就自动执行后台脚本，恶意仓库可能附带破坏性清理脚本导致宿主数据被清空。
   - **设计选择**：脚本仅作为静态资产存放在 `scripts/` 中，执行必须走既有的 `execute_command`，强制接受 `PathGuard` 限制、`CommandGuard` 校验、`TerminalHitlHandler` 终端人工确认及 `AuditLog` 审计记账。

---

## 4. 最佳实践与业界开源实证调研

### [Best Practice] 最佳实践与演进对比 (Alternatives & Evolution)

通过 `agent-reach` 调研业界同类顶级开源产品与规范（Anthropic Claude Code、Cline、Roo Code）：

1. **Anthropic Claude Code 的 "Seven Steering Surfaces" 体系实证**：
   - Anthropic 官方将 Agent 引导机制明确划分为 7 个控制面：System Prompt (核心能力与安全)、`CLAUDE.md` (项目级记忆)、Rules (路径范围局部约束)、Skills (过程化工作流)、Subagents (专职子代理)、Hooks (确定性生命周期钩子)、Output Styles (格式与风格)。
   - **Skills 规范**：每个 Skill 独立成目录，采用 `SKILL.md` 并包含 YAML frontmatter（`name`, `description`, `allowed-tools`）。启动时仅提取元数据放入 System Prompt 索引；模型调用或命令行触发时才按需加载正文。目录内支持 `references/` 和 `scripts/` 拆分。
   - **层级继承**：支持 Personal (`~/.claude/skills/`) 与 Project (`.claude/skills/`)，同名无缝覆盖。

2. **Cline / Roo Code 规则机制实证与局限**：
   - Cline / Roo Code 采用 `.clinerules/` 目录组织不同模式与任务的规则提示。
   - **局限**：早期将所有规约文件全量串联拼入 System Prompt，在大型复杂项目中常导致系统提示超限与模型注意力稀释（Context Debt）；且各层提示缺乏结构化预算配额与脱敏审计导出机制。

3. **XhlCLI Phase 14 的工业级演进**：
   - **确定性 7 层组装引擎**：明确每一层的单一职责、优先级、预算上限与不可覆盖安全底线；
   - **健壮的三层 Skill 治理**：Builtin < User < Project 完整覆盖链，单 Skill 解析故障安全隔离；
   - **沙箱化资源访问**：`SkillReferenceResolver` 严格基于路径规范化防止 `../` 越界读取；
   - **可审计与可评测**：提供 `/prompt export` 敏感凭据全量脱敏导出，并提供基于 Golden Set 的 Prompt 稳定性比对测试。

---

## 5. 核心接口与类结构定义

### 5.1 目录组织与包结构
```
src/main/java/com/xhlcli/
├── prompt/
│   ├── PromptLayer.java            # 分层枚举 (BASE_IDENTITY, SAFETY_POLICY, etc.)
│   ├── PromptSource.java           # 来源枚举 (BUILTIN, USER, PROJECT)
│   ├── PromptBlock.java            # 提示块实体 (layer, source, content, isImmutable)
│   ├── LayeredPromptAssembler.java # 分层组装调度器 (按预算、覆盖规则合成完整 Prompt)
│   ├── LayerBudgetConfig.java      # 各层字符与 Token 预算配置
│   ├── PromptExporter.java         # 审计导出器 (带来源注释与敏感凭据脱敏)
│   └── PromptGoldenTest.java       # Golden Test 基准测试
├── skill/
│   ├── SkillMetadata.java          # 元数据对象 (name, description, allowedTools)
│   ├── SkillSource.java            # 来源枚举 (BUILTIN, USER, PROJECT)
│   ├── SkillDefinition.java        # Skill 领域对象 (元数据、来源、正文、路径、状态)
│   ├── SkillParser.java            # YAML Frontmatter + Markdown 解析器 (隔离容错)
│   ├── SkillRegistry.java          # 发现、加载、三层合并、启停与动态重载管理器
│   ├── SkillIndex.java             # 紧凑索引生成器 (名称+描述，受控预算)
│   ├── SkillReferenceResolver.java # 相对路径参考文件读取器 (防止目录穿越)
│   ├── tool/
│   │   └── LoadSkillTool.java      # load_skill 本地工具实现
│   └── builtin/
│       ├── BuiltinSkills.java      # 内置 Skill 提供者
│       ├── git-feature-workflow/   # 内置编码工作流 Skill
│       └── web-research/           # 内置 Web 调研 Skill
```

### 5.2 核心类接口契约

#### `SkillParser.java`
```java
public final class SkillParser {
    public static SkillDefinition parse(Path skillDir, SkillSource source);
}
```
- 解析 `SKILL.md`：以 `---` 分隔 Frontmatter 与 Markdown 正文；
- Frontmatter 提取 `name` (正则 `^[a-z0-9-_]+$`)、`description` (必填)、`allowed-tools` (可选)；
- 若解析失败，返回带有 `parseError` 的 `SkillDefinition`，不抛未检异常阻塞全局。

#### `SkillRegistry.java`
```java
public final class SkillRegistry {
    public void scanAndReload();
    public List<SkillDefinition> listAll();
    public Optional<SkillDefinition> find(String name);
    public boolean enable(String name);
    public boolean disable(String name);
    public String generateIndexPrompt(int maxCharsBudget);
}
```

#### `LayeredPromptAssembler.java`
```java
public final class LayeredPromptAssembler {
    public String assembleSystemPrompt(
            AgentMode mode,
            ModelCapabilities capabilities,
            Path workspace,
            List<MemoryEntry> memories,
            Set<String> activeSkillBodies);
}
```

#### `LoadSkillTool.java`
```java
public final class LoadSkillTool implements Tool {
    // 名字: load_skill
    // 参数: { "name": "skill-name" }
    // 行为: 校验 SkillRegistry，若启用则拉取正文并注入当前 Agent 会话，当前 Run 内部自动去重
}
```

#### 终端命令 `/skill` 与 `/prompt`
- `/skill list`：格式化表格输出所有已安装 Skill（名称、来源、启停状态、描述）。
- `/skill show <name>`：查看指定 Skill 完整元数据与正文。
- `/skill enable <name>` / `/skill disable <name>`：切换启停。
- `/skill reload`：热重载。
- `/prompt export [path]`：脱敏导出当前 System Prompt 分层全貌。

---

## 6. 验收标准与评测计划 (DoD)

1. **分层组装与安全不可覆盖**：
   - 验证 7 层提示按严格顺序合成；
   - 验证恶意配置无法擦除或重写 Layer 2 (`SAFETY_POLICY`)。
2. **三层覆盖机制**：
   - 项目级 `.xhlcli/skills/xyz` 覆盖用户级 `~/.xhlcli/skills/xyz` 及内置同名 Skill。
   - 解析异常的 Skill 隔离在自身状态为 `ERROR`，不中断应用。
3. **渐进式披露与按需加载**：
   - 启动时 System Prompt 仅包含 Skill 索引（`name` + `description`），正文不进入；
   - 模型调用 `load_skill` 后正文成功加载，且同一 Run 内二次调用提示已加载、不重复追加。
4. **安全沙箱防逃逸**：
   - `SkillReferenceResolver` 对 `../` 越界路径抛出 `SecurityException` 拒绝。
5. **审计导出与脱敏**：
   - `/prompt export` 导出内容中包含分层标注，且 API Key 与疑似敏感凭据经 `SecretRedactor` 替换为 `[REDACTED]`。
6. **自动化回归套件**：
   - 新增单元测试与集成测试覆盖率高，全量现有 366+ 测试 100% 保持绿灯。
