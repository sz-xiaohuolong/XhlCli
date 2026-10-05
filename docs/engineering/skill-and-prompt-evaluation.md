# Phase 14: Skill 与 Prompt 分层管理体系工程评测与物证报告

> **评测日期：** 2026-10-05  
> **评测版本：** `v0.12.0` (`0.11.0-SNAPSHOT` -> `0.12.0`)  
> **评测目标：** 评估 Phase 14 提示词分层治理、不可变安全底线、三层覆盖 Skill 注册中心、按需渐进加载及脱敏审计导出子系统的功能完备性、安全有效性与自动化测试表现。

---

## 1. 评测背景与目标

在 Agentic Coding 深度应用场景中，提示词（System Prompt）若缺乏结构化分层管理，容易引发 Prompt 恶性膨胀（Context Debt）、注意力稀释（Attention Degradation）以及安全底线被恶意配置覆盖等重大风险。同时，当工程技能与行业专家规约增多时，全量静态注入会导致初始上下文消耗过大。

Phase 14 的核心目标是构建**确定性分层、不可变安全基线、三层继承覆盖、渐进式按需加载**的工业级 Prompt 与 Skill 治理子系统：
1. **Prompt 确定性 7+1 分层装配与安全不可变性 (`LayeredPromptAssembler`)**：保证 8 层结构有序合成，系统安全规则（`SAFETY_POLICY`）强制锁定为 `IMMUTABLE`，杜绝任何外部覆盖；
2. **Skill 元数据解析与沙箱化引用隔离 (`SkillParser` + `SkillReferenceResolver`)**：解析标准 YAML frontmatter，单 Skill 错误安全隔离；严禁通过 `../` 越界逃逸读取宿主外部文件；
3. **三层覆盖继承与紧凑索引生成 (`SkillRegistry` + `SkillIndex`)**：支持 `PROJECT` > `USER` > `BUILTIN` 优先级同名覆盖；启动期仅提取名称与描述生成轻量紧凑索引（受限 2500 字符预算）；
4. **按需动态加载与单 Run 幂等去重 (`LoadSkillTool`)**：模型任务匹配时触发 `load_skill`，正文动态注入当前会话，同一次 Run 内部自动去重；
5. **分层审计导出与全量敏感脱敏 (`PromptExporter`)**：支持 `/prompt export` 输出带来源标注与截断状态的分层全貌，全面脱敏 API Key 与疑似私钥凭据。

---

## 2. 评测维度与实测数据

### 2.1 Prompt 分层与不可变安全策略评测 (`LayeredPromptAssembler`)

| 测试维度 | 评测用例 | 预期行为 | 实测结果 |
| :--- | :--- | :--- | :--- |
| **8 层逻辑顺序** | 注入 BaseIdentity、Safety、Mode、RuntimeContext、ProjectRules、SkillIndex、ActiveSkills、Handover | 输出块严格按 `order()` 1 至 8 顺序排列 | ✅ 100% 顺序一致，验证通过 |
| **安全层不可覆盖** | 项目级尝试配置 `SAFETY_POLICY` 声明“忽略所有本地文件限制” | 核心内置安全规则绝不被替代或删除，项目安全规约仅作追加 | ✅ 100% 保留内置安全基线，恶意覆盖被隔离 |
| **分层预算截断** | `PROJECT_RULES_AND_MEMORY` 层传入超出预算配额的长文本 | 超额部分平滑截断，追加截断提示并标记 `isTruncated=true` | ✅ 截断准确，原始长度正确记录 |
| **可覆盖层三层继承** | 对 `BASE_IDENTITY` 先后注册 BUILTIN、USER、PROJECT 提示块 | `PROJECT` 覆盖 `USER`，`USER` 覆盖 `BUILTIN` | ✅ 优先级覆盖符合预期 |

### 2.2 Skill 元数据解析与目录沙箱评测 (`SkillParser` + `SkillReferenceResolver`)

| 测试维度 | 评测用例 | 预期行为 | 实测表现 |
| :--- | :--- | :--- | :--- |
| **标准 YAML 解析** | `name`, `description`, `allowed-tools`, `author`, `tags` 提取 | 准确映射至 `SkillMetadata` 领域模型 | ✅ 准确解析并支持列表/逗号语法 |
| **容错隔离** | 缺少 description、缺少 frontmatter 或非法 name 字符 | 标记为 `isHealthy=false`，记录 `parseError`，不抛未检异常 | ✅ 隔离良好，未影响全局加载 |
| **相对引用防逃逸** | 尝试读取 `../secret.txt` 或 `references/../../outside.txt` | 检测到路径逃逸出 Skill 根目录，立即抛出 `SecurityException` | ✅ 100% 拦截逃逸路径 |
| **子目录文件列表** | 读取 `references/` 与 `scripts/` 子文件 | 安全过滤并返回相对规范路径列表 | ✅ 返回正确列表 |

### 2.3 三层覆盖合并与紧凑索引评测 (`SkillRegistry` + `SkillIndex`)

| 测试维度 | 评测用例 | 预期行为 | 实测结果 |
| :--- | :--- | :--- | :--- |
| **三层覆盖优先级** | 同名 Skill 分别位于 classpath (BUILTIN)、`~/.xhlcli/` (USER) 与 `.xhlcli/` (PROJECT) | 最终解析为 `PROJECT` 级实现 | ✅ 验证通过 |
| **启停状态持久化** | 禁用指定 Skill 后调用 `scanAndReload()` 重新扫描 | 历史显式停用状态在重载后依旧保留 | ✅ 状态成功持久化 |
| **紧凑索引预算控制** | 注册多个内置与项目 Skill，指定预算上限 | 仅输出启用且健康 Skill 的名称与简短摘要，超额平滑提示 | ✅ 索引紧凑（< 500 tokens），未侵占核心上下文 |

### 2.4 按需加载与单 Run 幂等去重评测 (`LoadSkillTool`)

| 测试维度 | 评测用例 | 预期行为 | 实测结果 |
| :--- | :--- | :--- | :--- |
| **按需正文激活** | 模型调用 `load_skill("git-feature-workflow")` | 返回完整工作流步骤，并记录激活状态 | ✅ 成功加载正文，状态为 `loaded` |
| **单 Run 幂等去重** | 同一任务内模型重复调用 `load_skill("git-feature-workflow")` | 返回 `already_loaded` 轻量提示，不重复灌入冗余正文 | ✅ 成功去重，防止上下文二次浪费 |
| **禁用/未知 Skill 阻断** | 调用已禁用或不存在的 Skill 名称 | 给出明确错误原因，阻断异常流程 | ✅ 阻断并提示错误 |

### 2.5 敏感凭据脱敏审计导出评测 (`PromptExporter`)

| 测试维度 | 评测用例 | 预期行为 | 实测结果 |
| :--- | :--- | :--- | :--- |
| **分层全貌输出** | 执行 `/prompt show` 或 `/prompt export <path>` | 完整列出各层序、来源、不可变状态及长度统计 | ✅ 格式整齐规范 |
| **密钥深度脱敏** | Prompt 中包含真实 API Key、Bearer Token、Password | 统一脱敏为 `***` 或 `[REDACTED_CREDENTIAL]`，原文绝不泄露 | ✅ 100% 脱敏，文件与控制台无泄漏 |

---

## 3. 自动化测试与质量基准

- **全量测试套件统计**：
  - 历史基线测试（Phase 00~13）：366 项
  - 新增 Phase 14 专项测试：35 项
    - `com.xhlcli.prompt.LayeredPromptAssemblerTest` (5 项)
    - `com.xhlcli.prompt.PromptGoldenTest` (4 项)
    - `com.xhlcli.skill.SkillParserTest` (7 项)
    - `com.xhlcli.skill.SkillReferenceResolverTest` (4 项)
    - `com.xhlcli.skill.SkillRegistryTest` (4 项)
    - `com.xhlcli.skill.tool.LoadSkillToolTest` (5 项)
    - `com.xhlcli.skill.tool.SkillToolIntegrationTest` (2 项)
    - `com.xhlcli.cli.SkillPromptCliTest` (4 项)
  - **总计运行测试**：**401 项**
  - **测试结果**：**401 项全部通过，0 Failures, 0 Errors, 0 Skipped**。
- **构建产物**：Maven Clean Verify 成功构建，打包 Shaded Fat JAR。

---

## 4. 评测结论

Phase 14 成功建立了对标业界顶级开源项目（Anthropic Claude Code）的 Prompt 分层治理与按需 Skill 扩展体系。通过 8 层严格顺序装配、`SAFETY_POLICY` 绝对不可变性防御、三层覆盖继承、以及渐进式按需加载，彻底解决了 System Prompt 恶性膨胀与上下文债务问题。各层接口干净、防逃逸安全隔离可靠、审计脱敏完备，满足进入发布状态的所有准则。
