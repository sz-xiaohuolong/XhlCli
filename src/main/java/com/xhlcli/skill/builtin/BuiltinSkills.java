package com.xhlcli.skill.builtin;

import com.xhlcli.prompt.PromptSource;
import com.xhlcli.skill.SkillDefinition;
import com.xhlcli.skill.SkillMetadata;

import java.util.List;

/**
 * XhlCLI 内置开箱即用 Skill 提供者。
 */
public final class BuiltinSkills {
    private BuiltinSkills() {}

    public static SkillDefinition gitFeatureWorkflow() {
        SkillMetadata meta = new SkillMetadata(
                "git-feature-workflow",
                "规范的 Git 特性开发与分支发布流程（包含代码差异比对、单元测试回归验证、原子化提交与 PR 总结）",
                List.of("git_diff", "execute_command", "read_file"),
                "XhlCLI Team",
                List.of("git", "workflow", "ci")
        );
        String instructions = """
                # Git Feature & Release Workflow
                
                当用户要求提交代码、创建 PR 或执行分支发布流程时，请严格遵守以下步骤：
                
                ## 1. 变更审查与状态确认
                - 调用 `git_diff` 审查当前工作区所有改动的代码。
                - 仔细确认改动范围，严禁引入未经请求的多余变更或格式化噪音。
                - 检查是否存在未脱敏的 API 密钥、私钥或临时测试凭据。
                
                ## 2. 自动化回归测试
                - 调用 `execute_command` 运行测试套件（如 `./mvnw test` 或对应工程测试命令）。
                - 必须保证所有测试用例 100% 绿灯；若有失败必须先修复故障再推进。
                
                ## 3. 规范化原子提交
                - 提交信息必须遵循 Conventional Commits 规范：
                  - `feat(<scope>): ...` 新增功能
                  - `fix(<scope>): ...` 缺陷修复
                  - `refactor(<scope>): ...` 重构优化
                  - `test(<scope>): ...` 测试用例完善
                  - `docs(<scope>): ...` 文档与注释更新
                
                ## 4. 交付总结输出
                - 向用户清晰汇报：核心变更点、影响范围、自测命令与回归结果。
                """;
        return SkillDefinition.healthy(meta, PromptSource.BUILTIN, null, null, instructions);
    }

    public static SkillDefinition webResearch() {
        SkillMetadata meta = new SkillMetadata(
                "web-research",
                "系统化互联网技术调研与选型（包含多源搜索交叉验证、网页正文提取过滤、权威信息甄别与对比报告生成）",
                List.of("web_search", "web_fetch", "read_file", "write_file"),
                "XhlCLI Team",
                List.of("research", "web", "survey")
        );
        String instructions = """
                # Technical Web Research Workflow
                
                当用户要求技术选型、调研官方最新文档、排查未知异常或对比业界开源方案时，请遵循以下流程：
                
                ## 1. 调研问题拆解与多角度检索
                - 将用户目标拆分为 2-3 个具备针对性关键词的搜索 Query。
                - 调用 `web_search` 检索官方 GitHub 仓库、技术 RFC、官方 Release Notes 及权威社区。
                
                ## 2. 核心页面抓取与噪声清洗
                - 从搜索结果列表中挑选 2-4 个权威度最高、时效性最匹配的 URL。
                - 调用 `web_fetch` 抓取正文，提取关键代码片段、架构图设计与配置示例。
                
                ## 3. 多源交叉验证与权威性甄别
                - 交叉比对不同来源的观点与实现，区分官方最新推荐方案与已废弃旧版本写法。
                - 重点关注安全性、性能开销、内存占用及社区活跃度。
                
                ## 4. 结构化调研报告生成
                - 按照统一结构输出调研结论：
                  1. 背景与核心需求
                  2. 业界主流方案对比（附带官方来源链接与关键代码模式）
                  3. 本地项目适配优劣分析
                  4. 最终落地决策与演进建议
                """;
        return SkillDefinition.healthy(meta, PromptSource.BUILTIN, null, null, instructions);
    }

    public static List<SkillDefinition> all() {
        return List.of(gitFeatureWorkflow(), webResearch());
    }
}
