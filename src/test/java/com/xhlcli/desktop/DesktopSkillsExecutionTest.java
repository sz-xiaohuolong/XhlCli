package com.xhlcli.desktop;

import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DesktopSkillsExecutionTest {
    @TempDir Path temp;
    private ObjectNode params(){return DesktopJson.MAPPER.createObjectNode();}
    private MockResponse answer(String text){return new MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: {\"choices\":[{\"delta\":{\"content\":\""+text+"\"},\"finish_reason\":null}]}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n");}
    private MockResponse load(){return new MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-skill\",\"type\":\"function\",\"function\":{\"name\":\"load_skill\",\"arguments\":\"{\\\"name\\\":\\\"sample-skill\\\"}\"}}]},\"finish_reason\":null}]}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n");}
    private ObjectNode finished(BlockingQueue<ObjectNode> events) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<deadline){var event=events.poll(1,TimeUnit.SECONDS);if(event!=null&&event.path("event").asText().equals("turn.finished")){assertEquals("completed",event.path("data").path("status").asText(),event.toString());return event;}}
        throw new AssertionError("任务没有结束");
    }
    @Test void 开关在已有聊天的下一轮控制索引和实际加载() throws Exception {
        Path home=temp.resolve("home"),project=Files.createDirectory(temp.resolve("project"));
        Path directory=home.resolve(".xhlcli/skills/sample-skill");Files.createDirectories(directory);
        Files.writeString(directory.resolve("SKILL.md"),"---\nname: sample-skill\ndescription: 示例技能\n---\n独有技能正文标记");
        try(var server=new MockWebServer()){
            server.start();server.enqueue(answer("第一轮"));server.enqueue(load());server.enqueue(answer("已拒绝加载"));server.enqueue(load());server.enqueue(answer("已恢复"));
            var events=new LinkedBlockingQueue<ObjectNode>();
            try(var service=new DesktopService(home,Map.of("DEEPSEEK_API_KEY","fixture","DEEPSEEK_BASE_URL",server.url("/").toString()),events::add)){
                String pid=service.handle("project.add",params().put("path",project.toString())).path("id").asText();
                String sid=service.handle("session.create",params().put("projectId",pid).put("model","deepseek:deepseek-chat")).path("id").asText();
                service.handle("turn.start",params().put("sessionId",sid).put("input","开始"));finished(events);server.takeRequest(2,TimeUnit.SECONDS);
                service.handle("skills.set",params().put("projectId",pid).put("name","sample-skill").put("enabled",false));
                assertFalse(service.handle("commands.list",params().put("projectId",pid)).toString().contains("/sample-skill"));
                service.handle("turn.start",params().put("sessionId",sid).put("input","尝试加载技能"));var disabled=finished(events);
                var request=DesktopJson.MAPPER.readTree(server.takeRequest(2,TimeUnit.SECONDS).getBody().readUtf8());
                assertFalse(request.path("messages").get(0).path("content").asText().contains("sample-skill"));
                String afterTool=server.takeRequest(2,TimeUnit.SECONDS).getBody().readUtf8();
                assertTrue(afterTool.contains("currently disabled"));assertFalse(afterTool.contains("独有技能正文标记"));
                assertFalse(disabled.toString().contains("独有技能正文标记"));
                service.handle("skills.set",params().put("name","sample-skill").put("enabled",true));
                service.handle("turn.start",params().put("sessionId",sid).put("input","重新加载"));finished(events);
                var enabled=DesktopJson.MAPPER.readTree(server.takeRequest(2,TimeUnit.SECONDS).getBody().readUtf8());
                assertTrue(enabled.path("messages").get(0).path("content").asText().contains("sample-skill"));
                assertTrue(server.takeRequest(2,TimeUnit.SECONDS).getBody().readUtf8().contains("独有技能正文标记"));
            }
        }
    }
    @Test void 页面开关不修改已经取得的任务技能视图() throws Exception {
        var active=DesktopSkills.registry(temp,null);
        DesktopSkills.set(temp,null,params().put("name","web-research").put("enabled",false));
        assertTrue(active.find("web-research").orElseThrow().isEnabled());
        active.scanAndReload();assertFalse(active.find("web-research").orElseThrow().isEnabled());
    }
}
