package com.xhlcli.team;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HandoverPackageTest {

    @Test
    void testBuildAndPromptFormatting() {
        HandoverPackage pkg = HandoverPackage.builder("step-1", "实现基础领域模型")
                .contextSummary("用户需求是完成 Phase 10 Multi-Agent 架构。前置已完成 TeamRole 枚举定义。")
                .acceptanceCriteria("所有字段非空校验，单元测试覆盖率达到 100%")
                .filesModified(List.of("src/main/java/com/xhlcli/team/TeamRole.java"))
                .build();

        assertEquals("step-1", pkg.taskId());
        assertEquals("实现基础领域模型", pkg.taskGoal());
        assertEquals(1, pkg.filesModified().size());

        String prompt = pkg.toPromptText();
        assertTrue(prompt.contains("### 任务交接上下文 [step-1]"));
        assertTrue(prompt.contains("**当前任务目标**：实现基础领域模型"));
        assertTrue(prompt.contains("**前置上下文与依赖结果**："));
        assertTrue(prompt.contains("用户需求是完成 Phase 10"));
        assertTrue(prompt.contains("**验收标准**："));
        assertTrue(prompt.contains("单元测试覆盖率达到 100%"));
        assertTrue(prompt.contains("**涉及或修改的文件**："));
        assertTrue(prompt.contains("- src/main/java/com/xhlcli/team/TeamRole.java"));
    }

    @Test
    void testMinimalHandoverPackage() {
        HandoverPackage pkg = HandoverPackage.builder("step-2", "快速执行任务").build();
        assertEquals("step-2", pkg.taskId());
        assertEquals("快速执行任务", pkg.taskGoal());
        assertTrue(pkg.contextSummary().isEmpty());
        assertTrue(pkg.acceptanceCriteria().isEmpty());
        assertTrue(pkg.filesModified().isEmpty());

        String prompt = pkg.toPromptText();
        assertTrue(prompt.contains("### 任务交接上下文 [step-2]"));
        assertTrue(prompt.contains("**当前任务目标**：快速执行任务"));
        assertFalse(prompt.contains("**前置上下文与依赖结果**："));
        assertFalse(prompt.contains("**验收标准**："));
        assertFalse(prompt.contains("**涉及或修改的文件**："));
    }
}
