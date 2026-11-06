package com.xhlcli.desktop;

import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DesktopReasoningTest {
    @TempDir Path temp;
    private ObjectNode p(){return DesktopJson.MAPPER.createObjectNode();}
    private Map<String,String> env(String url){return Map.of("DEEPSEEK_API_KEY","fixture","DEEPSEEK_BASE_URL",url,"DEEPSEEK_MODEL","deepseek-flash");}
    private ObjectNode session(DesktopService s) throws Exception {
        Path project=Files.createDirectories(temp.resolve("project"));
        String pid=s.handle("project.add",p().put("path",project.toString())).path("id").asText();
        return (ObjectNode)s.handle("session.create",p().put("projectId",pid).put("model","deepseek:deepseek-flash"));
    }
    @Test void 强度按聊天保存并校验模型能力() throws Exception {
        String id;
        try(var s=new DesktopService(temp.resolve("home"),env("http://localhost:1"),e->{})){
            var created=session(s);id=created.path("id").asText();
            assertEquals("default",created.path("reasoningEffort").asText());
            var models=s.handle("config.get",p()).path("models");
            assertTrue(models.toString().contains("reasoningEfforts"));
            s.handle("session.update",p().put("sessionId",id).put("reasoningEffort","max"));
            assertThrows(IllegalArgumentException.class,()->s.handle("session.update",p().put("sessionId",id).put("reasoningEffort","invalid")));
            assertEquals("default",session(s).path("reasoningEffort").asText());
        }
        try(var s=new DesktopService(temp.resolve("home"),env("http://localhost:1"),e->{})){
            assertEquals("max",s.handle("state",p()).path("sessions").get(0).path("reasoningEffort").asText());
            var changed=s.handle("session.update",p().put("sessionId",id).put("model","openai:gpt-4o"));
            assertEquals("default",changed.path("reasoningEffort").asText());
            assertThrows(IllegalArgumentException.class,()->s.handle("session.update",p().put("sessionId",id).put("reasoningEffort","high")));
        }
    }
    private MockResponse reply(String delta){return new MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: {\"choices\":[{\"delta\":"+delta+"}]}\n\ndata: [DONE]\n\n");}
    private void finished(BlockingQueue<ObjectNode> q) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<end){var e=q.poll(1,TimeUnit.SECONDS);if(e!=null&&e.path("event").asText().equals("turn.finished")){assertEquals("completed",e.path("data").path("status").asText(),e.toString());return;}}
        fail("执行未完成");
    }
    @Test void 强度进入真实请求且工具续轮保留推理上下文() throws Exception {
        try(var server=new MockWebServer()){
            server.start();server.enqueue(reply("{\"reasoning_content\":\"测试推理标记\",\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"echo\",\"arguments\":\"{\\\"text\\\":\\\"hello\\\"}\"}}]}"));
            server.enqueue(reply("{\"content\":\"完成\"}"));server.enqueue(reply("{\"content\":\"关闭思考\"}"));
            var q=new LinkedBlockingQueue<ObjectNode>();
            try(var s=new DesktopService(temp.resolve("home"),env(server.url("/").toString()),q::add)){
                String id=session(s).path("id").asText();s.handle("session.update",p().put("sessionId",id).put("reasoningEffort","max"));
                s.handle("turn.start",p().put("sessionId",id).put("input","测试工具"));finished(q);
                var first=DesktopJson.MAPPER.readTree(server.takeRequest(2,TimeUnit.SECONDS).getBody().readUtf8());
                assertEquals("max",first.path("reasoning_effort").asText());assertEquals("enabled",first.path("thinking").path("type").asText());
                var second=DesktopJson.MAPPER.readTree(server.takeRequest(2,TimeUnit.SECONDS).getBody().readUtf8());
                assertTrue(second.path("messages").toString().contains("测试推理标记"));
                assertFalse(s.handle("state",p()).toString().contains("测试推理标记"));
                s.handle("session.update",p().put("sessionId",id).put("reasoningEffort","none"));
                s.handle("turn.start",p().put("sessionId",id).put("input","继续"));finished(q);
                var third=DesktopJson.MAPPER.readTree(server.takeRequest(2,TimeUnit.SECONDS).getBody().readUtf8());
                assertEquals("disabled",third.path("thinking").path("type").asText());assertFalse(third.has("reasoning_effort"));
            }
        }
    }
}
