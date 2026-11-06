package com.xhlcli.skill;

import com.xhlcli.skill.builtin.BuiltinSkills;
import com.xhlcli.skill.tool.LoadSkillTool;
import com.xhlcli.llm.CancellationToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class SkillPreferencesTest {
    @TempDir Path temp;
    private SkillRegistry registry(Path preferences) {
        var registry=new SkillRegistry(null,null,BuiltinSkills.all(),preferences);
        registry.scanAndReload();return registry;
    }
    @Test void 停用后新实例不再索引或加载技能且可重新启用() {
        Path file=temp.resolve("skills.json");
        var first=registry(file);assertTrue(first.disable("WEB-RESEARCH"));
        var reopened=registry(file);
        assertFalse(reopened.find("web-research").orElseThrow().isEnabled());
        assertFalse(reopened.generateIndexPrompt(5000).contains("web-research"));
        var result=new LoadSkillTool(reopened).execute(new ObjectMapper().createObjectNode().put("name","web-research"),new CancellationToken());
        assertTrue(result.summary().contains("currently disabled"));
        assertTrue(reopened.enable("web-research"));
        assertTrue(registry(file).find("web-research").orElseThrow().isEnabled());
    }
    @Test void 不同工作区交替修改不会覆盖另一项开关() {
        Path file=temp.resolve("skills.json");var first=registry(file);var second=registry(file);
        first.disable("web-research");second.disable("git-feature-workflow");
        var reopened=registry(file);
        assertTrue(reopened.listAll().stream().noneMatch(SkillDefinition::isEnabled));
        first.scanAndReload();assertFalse(first.find("git-feature-workflow").orElseThrow().isEnabled());
    }
    @Test void 保存失败保留原状态() throws Exception {
        Path file=temp.resolve("skills.json");var registry=registry(file);
        Files.createDirectory(file);
        assertThrows(RuntimeException.class,()->registry.disable("web-research"));
        assertTrue(registry.find("web-research").orElseThrow().isEnabled());
    }
    @Test void 损坏设置拒绝加载并保留原文件() throws Exception {
        Path file=temp.resolve("skills.json");Files.writeString(file,"{\"web-research\":\"false\"}");
        assertThrows(RuntimeException.class,()->registry(file));
        assertEquals("{\"web-research\":\"false\"}",Files.readString(file));
    }
}
