package com.xhlcli.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DesktopSkillsTest {
    @TempDir Path temp;
    private ObjectNode params(){return DesktopJson.MAPPER.createObjectNode();}
    private void skill(Path directory,String name,String description) throws Exception {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("SKILL.md"),"---\nname: "+name+"\ndescription: "+description+"\n---\n测试指导正文");
    }
    private JsonNode item(JsonNode result,String name){
        for(var entry:result.path("skills"))if(entry.path("name").asText().equals(name))return entry;
        fail("缺少技能："+name);return null;
    }
    @Test void 无项目和模型配置时也能列出个人与内置技能() throws Exception {
        Path home=temp.resolve("home");skill(home.resolve(".xhlcli/skills/personal"),"personal","个人说明");
        try(var service=new DesktopService(home,Map.of(),event->{})){
            var list=service.handle("skills.list",params());
            assertEquals("USER",item(list,"personal").path("source").asText());
            assertEquals("BUILTIN",item(list,"web-research").path("source").asText());
            assertFalse(list.toString().contains("测试指导正文"));
        }
    }
    @Test void 项目覆盖与全局开关在重启后保持且不删除技能文件() throws Exception {
        Path home=temp.resolve("home"),project=temp.resolve("project");
        skill(home.resolve(".xhlcli/skills/shared"),"shared","个人版本");
        skill(project.resolve(".xhlcli/skills/shared"),"shared","项目版本");
        String projectId;
        try(var service=new DesktopService(home,Map.of(),event->{})){
            projectId=service.handle("project.add",params().put("path",project.toString())).path("id").asText();
            var context=params().put("projectId",projectId);
            assertEquals("项目版本",item(service.handle("skills.list",context),"shared").path("description").asText());
            var changed=service.handle("skills.set",context.put("name","shared").put("enabled",false));
            assertFalse(item(changed,"shared").path("enabled").asBoolean());
            assertFalse(item(service.handle("skills.list",params()),"shared").path("enabled").asBoolean());
        }
        try(var service=new DesktopService(home,Map.of(),event->{})){
            assertFalse(item(service.handle("skills.list",params().put("projectId",projectId)),"shared").path("enabled").asBoolean());
            assertTrue(item(service.handle("skills.set",params().put("name","shared").put("enabled",true)),"shared").path("enabled").asBoolean());
        }
        assertTrue(Files.exists(project.resolve(".xhlcli/skills/shared/SKILL.md")));
    }
    @Test void 非法输入或损坏技能无法被启用() throws Exception {
        Path home=temp.resolve("home");Files.createDirectories(home.resolve(".xhlcli/skills/broken"));
        try(var service=new DesktopService(home,Map.of(),event->{})){
            var broken=item(service.handle("skills.list",params()),"broken");assertFalse(broken.path("healthy").asBoolean());
            assertThrows(IllegalArgumentException.class,()->service.handle("skills.set",params().put("name","broken").put("enabled",true)));
            assertThrows(IllegalArgumentException.class,()->service.handle("skills.set",params().put("name","web-research").put("enabled","false")));
            assertThrows(IllegalArgumentException.class,()->service.handle("skills.set",params().put("name","missing").put("enabled",false)));
            assertTrue(item(service.handle("skills.list",params()),"web-research").path("enabled").asBoolean());
        }
    }
}
