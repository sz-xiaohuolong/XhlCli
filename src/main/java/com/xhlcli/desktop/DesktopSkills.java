package com.xhlcli.desktop;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhlcli.skill.SkillRegistry;
import com.xhlcli.skill.builtin.BuiltinSkills;
import java.nio.file.Path;

/** 技能页面读取独立视图；保存开关不会修改运行中任务已取得的技能注册表。 */
final class DesktopSkills {
    static SkillRegistry registry(Path home,Path workspace) {
        var registry=new SkillRegistry(home.resolve(".xhlcli/skills"),workspace==null?null:workspace.resolve(".xhlcli/skills"),
                BuiltinSkills.all(),home.resolve(".xhlcli/desktop/skills.json"));
        registry.scanAndReload();return registry;
    }
    static ObjectNode list(Path home,Path workspace) {return describe(registry(home,workspace));}
    static ObjectNode set(Path home,Path workspace,ObjectNode params) {
        if(!params.path("name").isTextual()||params.path("name").asText().isBlank()||!params.path("enabled").isBoolean())
            throw new IllegalArgumentException("请选择技能并指定启用或停用状态");
        var registry=registry(home,workspace);String name=params.path("name").asText();
        var skill=registry.find(name).orElseThrow(()->new IllegalArgumentException("技能已不存在，请刷新列表"));
        boolean enabled=params.path("enabled").asBoolean();
        if(enabled&&!skill.isHealthy())throw new IllegalArgumentException("技能文件有误，请修复后刷新再启用");
        if(enabled)registry.enable(name);else registry.disable(name);
        return describe(registry);
    }
    private static String readableError(String error) {
        if(error.startsWith("SKILL.md not found"))return "缺少 SKILL.md 文件，请补齐后刷新。";
        if(error.equals("SKILL.md is empty"))return "SKILL.md 内容为空，请补齐后刷新。";
        if(error.startsWith("Missing YAML")||error.startsWith("Unclosed YAML"))return "文件头格式有误，请用 --- 包围技能名称和描述。";
        if(error.startsWith("Invalid skill name"))return "技能名称应为 1～64 个小写字母、数字、短横线或下划线。";
        if(error.startsWith("Missing 'description'"))return "技能缺少 description 描述，请补齐后刷新。";
        return "无法读取技能文件，请检查文件内容和读取权限后刷新。";
    }
    private static ObjectNode describe(SkillRegistry registry) {
        var result=DesktopJson.MAPPER.createObjectNode();var entries=result.putArray("skills");
        for(var skill:registry.listAll()){
            var entry=entries.addObject();entry.put("name",skill.name());entry.put("description",skill.isHealthy()?skill.description():"");
            entry.put("source",skill.source().name());entry.put("enabled",skill.isEnabled());entry.put("healthy",skill.isHealthy());
            if(!skill.isHealthy())entry.put("error",readableError(skill.parseError()));
        }
        return result;
    }
}
