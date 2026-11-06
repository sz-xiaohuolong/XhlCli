package com.xhlcli.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 计划确认是独立的人类决定，自动工具审批不能代替。 */
class DesktopPlanReviewTest {
    @TempDir Path temp;
    private static final String GOAL="/plan 编写一篇技术介绍文档并验证保存内容";
    private ObjectNode p(String... values){var n=DesktopJson.MAPPER.createObjectNode();for(int i=0;i<values.length;i+=2)n.put(values[i],values[i+1]);return n;}
    private String session(DesktopService service,String mode) throws Exception {
        Path project=Files.createDirectories(temp.resolve("project"));
        String id=service.handle("project.add",p("path",project.toString())).path("id").asText();
        return service.handle("session.create",p("projectId",id,"approvalMode",mode)).path("id").asText();
    }
    private JsonNode await(BlockingQueue<ObjectNode> events,String type) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<end){var event=events.poll(100,TimeUnit.MILLISECONDS);if(event!=null&&event.path("event").asText().equals(type))return event.path("data");}
        throw new AssertionError("未收到事件："+type);
    }
    private MockResponse response(Object delta) throws Exception {
        String json=DesktopJson.MAPPER.writeValueAsString(Map.of("choices",List.of(Map.of("delta",delta,"finish_reason","stop"))));
        return new MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: "+json+"\n\ndata: [DONE]\n\n");
    }
    private MockResponse plan(String summary) throws Exception {
        return response(Map.of("content",DesktopJson.MAPPER.writeValueAsString(Map.of("summary",summary,"tasks",List.of(Map.of("id","t1","description","写入说明文档","type","FILE_WRITE","dependencies",List.of()))))));
    }
    private MockResponse write() throws Exception {
        return response(Map.of("tool_calls",List.of(Map.of("index",0,"id","write1","type","function","function",Map.of("name","write_file","arguments","{\"path\":\"result.md\",\"content\":\"# 验收文档\\n\"}")))));
    }
    private Map<String,String> env(MockWebServer server){return Map.of("DEEPSEEK_API_KEY","fixture-only","DEEPSEEK_BASE_URL",server.url("/").toString());}
    private JsonNode turn(DesktopService service) throws Exception{return service.handle("state",p()).path("sessions").get(0).path("turns").get(0);}

    @Test void automaticToolsStillWaitForExplicitPlanAndPersistMarkdown() throws Exception {
        try(var server=new MockWebServer()) {
            server.start();server.enqueue(plan("先写入，再核对"));server.enqueue(write());
            String markdown="# 结果\n\n> 已保存\n\n- **说明**\n\n| 项目 | 结果 |\n| --- | --- |\n| 写入 | 完成 |\n\n```text\n原始内容\n```";
            server.enqueue(response(Map.of("content",markdown)));
            var events=new LinkedBlockingQueue<ObjectNode>();String id;
            try(var service=new DesktopService(temp.resolve("home"),env(server),events::add)) {
                id=session(service,"auto");service.handle("turn.start",p("sessionId",id,"input",GOAL));
                var ready=await(events,"plan.ready");
                assertEquals("plan_review",turn(service).path("status").asText());
                assertEquals(1,server.getRequestCount());assertFalse(Files.exists(temp.resolve("project/result.md")));
                assertTrue(turn(service).path("events").toString().contains("先写入，再核对"));
                assertThrows(IllegalStateException.class,()->service.handle("approval.respond",p("sessionId",id,"approvalId",ready.path("id").asText()).put("allow",true)));
                var decision=p("sessionId",id,"planId",ready.path("id").asText(),"action","execute");
                service.handle("plan.respond",decision);
                assertThrows(IllegalStateException.class,()->service.handle("plan.respond",decision));
                var finished=await(events,"turn.finished");assertEquals("completed",finished.path("status").asText());
                assertTrue(finished.path("answer").asText().contains("\n\n"+markdown));
                assertEquals("# 验收文档\n",Files.readString(temp.resolve("project/result.md")));
                assertTrue(finished.path("events").toString().contains("approval.automatic"));
            }
            try(var service=new DesktopService(temp.resolve("home"),env(server),events::add)) {
                assertTrue(turn(service).path("answer").asText().contains(markdown));
                assertTrue(turn(service).path("events").toString().contains("plan.resolved"));
                assertEquals(3,server.getRequestCount());
            }
        }
    }
    @Test void switchingAutomaticModeCannotResolveWaitingPlanAndCancelDoesNotWrite() throws Exception {
        try(var server=new MockWebServer()) {
            server.start();server.enqueue(plan("等待人工决定"));var events=new LinkedBlockingQueue<ObjectNode>();
            try(var service=new DesktopService(temp.resolve("home"),env(server),events::add)) {
                String id=session(service,"on-request");service.handle("turn.start",p("sessionId",id,"input",GOAL));
                var ready=await(events,"plan.ready");service.handle("session.update",p("sessionId",id,"approvalMode","auto"));
                assertEquals("plan_review",turn(service).path("status").asText());
                assertEquals(1,server.getRequestCount());
                service.handle("plan.respond",p("sessionId",id,"planId",ready.path("id").asText(),"action","cancel"));
                assertEquals("canceled",await(events,"turn.finished").path("status").asText());
                assertEquals(1,server.getRequestCount());assertFalse(Files.exists(temp.resolve("project/result.md")));
            }
        }
    }
    @Test void feedbackProducesNewReviewAndOldDecisionCannotExecute() throws Exception {
        try(var server=new MockWebServer()) {
            server.start();server.enqueue(plan("初始方案"));server.enqueue(plan("按新要求调整"));var events=new LinkedBlockingQueue<ObjectNode>();
            try(var service=new DesktopService(temp.resolve("home"),env(server),events::add)) {
                String id=session(service,"auto");service.handle("turn.start",p("sessionId",id,"input",GOAL));
                var ready=await(events,"plan.ready");String oldId=ready.path("id").asText();
                assertThrows(IllegalArgumentException.class,()->service.handle("plan.respond",p("sessionId",id,"planId",oldId,"action","supplement","feedback"," ")));
                service.handle("plan.respond",p("sessionId",id,"planId",oldId,"action","supplement","feedback","只写概述"));
                var revised=await(events,"plan.ready");assertNotEquals(oldId,revised.path("id").asText());assertEquals(2,server.getRequestCount());
                assertFalse(Files.exists(temp.resolve("project/result.md")));
                assertThrows(IllegalStateException.class,()->service.handle("plan.respond",p("sessionId",id,"planId",oldId,"action","execute")));
                service.handle("turn.cancel",p("sessionId",id));assertEquals("canceled",await(events,"turn.finished").path("status").asText());
                assertThrows(IllegalStateException.class,()->service.handle("plan.respond",p("sessionId",id,"planId",revised.path("id").asText(),"action","execute")));
            }
        }
    }
    @Test void savingPlanDecisionFailureNeverExecutesTools() throws Exception {
        try(var server=new MockWebServer()) {
            server.start();server.enqueue(plan("等待确认"));var events=new LinkedBlockingQueue<ObjectNode>();
            try(var service=new DesktopService(temp.resolve("home"),env(server),events::add)) {
                String id=session(service,"auto");service.handle("turn.start",p("sessionId",id,"input",GOAL));
                var ready=await(events,"plan.ready");
                Path state=temp.resolve("home/.xhlcli/desktop/state.json"),backup=state.resolveSibling("state.backup");
                Files.move(state,backup);Files.createDirectory(state);
                try {
                    assertThrows(IllegalStateException.class,()->service.handle("plan.respond",p("sessionId",id,"planId",ready.path("id").asText(),"action","execute")));
                    assertEquals(1,server.getRequestCount());assertFalse(Files.exists(temp.resolve("project/result.md")));
                }finally{Files.delete(state);Files.move(backup,state);}
                var ended=await(events,"turn.finished");assertNotEquals("completed",ended.path("status").asText());
                assertEquals(1,server.getRequestCount());assertFalse(Files.exists(temp.resolve("project/result.md")));
            }
        }
    }

}
