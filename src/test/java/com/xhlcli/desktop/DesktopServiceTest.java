package com.xhlcli.desktop;

import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DesktopServiceTest {
    @TempDir Path temp;
    ObjectNode params(String... values) {
        var p=DesktopJson.MAPPER.createObjectNode();for(int i=0;i<values.length;i+=2)p.put(values[i],values[i+1]);return p;
    }
    @Test void approvalModeIsValidatedPersistedAndIsolated() throws Exception {
        Path home=temp.resolve("home"),project=Files.createDirectory(temp.resolve("project"));
        String id;
        try(var service=new DesktopService(home,Map.of(),event->{})) {
            String projectId=service.handle("project.add",params("path",project.toString())).path("id").asText();
            var session=service.handle("session.create",params("projectId",projectId));id=session.path("id").asText();
            assertEquals("on-request",session.path("approvalMode").asText());
            service.handle("session.update",params("sessionId",id,"approvalMode","auto"));
            assertThrows(IllegalArgumentException.class,()->service.handle("session.update",params("sessionId",id,"approvalMode","never")));
            assertThrows(IllegalArgumentException.class,()->service.handle("session.update",params("sessionId",id).putNull("approvalMode")));
            assertThrows(IllegalArgumentException.class,()->service.handle("session.create",params("projectId",projectId,"approvalMode","invalid")));
            assertEquals("on-request",service.handle("session.create",params("projectId",projectId)).path("approvalMode").asText());
        }
        try(var service=new DesktopService(home,Map.of(),event->{})) {
            var sessions=service.handle("state",params()).path("sessions");
            assertEquals(2,sessions.size());assertEquals("auto",sessions.get(0).path("approvalMode").asText());
        }
        Path file=home.resolve(".xhlcli/desktop/state.json");
        var legacy=(ObjectNode)DesktopJson.MAPPER.readTree(file.toFile());
        ((ObjectNode)legacy.path("sessions").get(0)).remove("approvalMode");
        ((ObjectNode)legacy.path("sessions").get(1)).put("approvalMode","unknown");
        DesktopJson.MAPPER.writeValue(file.toFile(),legacy);
        try(var service=new DesktopService(home,Map.of(),event->{})) {
            for(var session:service.handle("state",params()).path("sessions"))assertEquals("on-request",session.path("approvalMode").asText());
        }
    }
    @Test void automaticApprovalExecutesToolsButKeepsPathProtection() throws Exception {
        try(var server=new MockWebServer()) {
            server.start();server.enqueue(tool("auto.txt","自动批准的内容"));server.enqueue(response("完成"));
            server.enqueue(tool("../outside.txt","不允许越界"));server.enqueue(response("已拒绝"));
            var events=new LinkedBlockingQueue<ObjectNode>();Path project=Files.createDirectory(temp.resolve("project"));
            try(var service=new DesktopService(temp.resolve("home"),Map.of("DEEPSEEK_API_KEY","fake","DEEPSEEK_BASE_URL",server.url("/").toString()),events::add)) {
                String projectId=service.handle("project.add",params("path",project.toString())).path("id").asText();
                String id=service.handle("session.create",params("projectId",projectId,"approvalMode","auto")).path("id").asText();
                service.handle("turn.start",params("sessionId",id,"input","自动写入"));
                var turn=await(events,"turn.finished").path("data");
                assertEquals("自动批准的内容",Files.readString(project.resolve("auto.txt")));
                assertTrue(turn.path("events").toString().contains("approval.automatic"));
                assertFalse(turn.path("events").toString().contains("approval.request"));
                service.handle("turn.start",params("sessionId",id,"input","检查越界"));
                var blocked=await(events,"turn.finished").path("data");
                assertFalse(Files.exists(temp.resolve("outside.txt")));
                assertTrue(blocked.path("events").toString().contains("[POLICY]"));
                assertFalse(blocked.path("events").toString().contains("approval.automatic"));
            }
        }
    }
    @Test void switchingToAutomaticResolvesPendingApprovalAndSwitchingBackAsksAgain() throws Exception {
        try(var server=new MockWebServer()) {
            server.start();server.enqueue(tool("first.txt","已批准"));server.enqueue(response("完成"));
            server.enqueue(tool("second.txt","不应写入"));server.enqueue(response("已拒绝"));
            var events=new LinkedBlockingQueue<ObjectNode>();Path project=Files.createDirectory(temp.resolve("project"));
            try(var service=new DesktopService(temp.resolve("home"),Map.of("DEEPSEEK_API_KEY","fake","DEEPSEEK_BASE_URL",server.url("/").toString()),events::add)) {
                String projectId=service.handle("project.add",params("path",project.toString())).path("id").asText();
                String id=service.handle("session.create",params("projectId",projectId)).path("id").asText();
                service.handle("turn.start",params("sessionId",id,"input","先等待审批"));
                var approval=await(events,"approval.request");assertFalse(Files.exists(project.resolve("first.txt")));
                Path stateFile=temp.resolve("home/.xhlcli/desktop/state.json"),backup=stateFile.resolveSibling("state.backup");
                Files.move(stateFile,backup);Files.createDirectory(stateFile);
                try {
                    assertThrows(Exception.class,()->service.handle("session.update",params("sessionId",id,"approvalMode","auto")));
                    assertEquals("on-request",service.handle("state",params()).path("sessions").get(0).path("approvalMode").asText());
                    assertFalse(Files.exists(project.resolve("first.txt")));
                }finally{Files.delete(stateFile);Files.move(backup,stateFile);}
                service.handle("session.update",params("sessionId",id,"approvalMode","auto"));
                assertEquals("completed",await(events,"turn.finished").path("data").path("status").asText());
                assertEquals("completed",service.handle("state",params()).path("sessions").get(0).path("turns").get(0).path("status").asText());
                assertEquals("已批准",Files.readString(project.resolve("first.txt")));
                assertThrows(IllegalStateException.class,()->service.handle("approval.respond",params("sessionId",id,"approvalId",approval.path("data").path("id").asText()).put("allow",true)));
                service.handle("session.update",params("sessionId",id,"approvalMode","on-request"));
                service.handle("turn.start",params("sessionId",id,"input","重新人工审批"));
                var next=await(events,"approval.request");
                service.handle("approval.respond",params("sessionId",id,"approvalId",next.path("data").path("id").asText()).put("allow",false));
                await(events,"turn.finished");assertFalse(Files.exists(project.resolve("second.txt")));
            }
        }
    }
    @Test void canceledApprovalCannotBeRevivedByAutomaticMode() throws Exception {
        try(var server=new MockWebServer()) {
            server.start();server.enqueue(tool("canceled.txt","不应写入"));
            var events=new LinkedBlockingQueue<ObjectNode>();Path project=Files.createDirectory(temp.resolve("project"));
            try(var service=new DesktopService(temp.resolve("home"),Map.of("DEEPSEEK_API_KEY","fake","DEEPSEEK_BASE_URL",server.url("/").toString()),events::add)) {
                String projectId=service.handle("project.add",params("path",project.toString())).path("id").asText();
                String id=service.handle("session.create",params("projectId",projectId)).path("id").asText();
                service.handle("turn.start",params("sessionId",id,"input","取消审批"));
                await(events,"approval.request");service.handle("turn.cancel",params("sessionId",id));
                service.handle("session.update",params("sessionId",id,"approvalMode","auto"));
                assertEquals("canceled",await(events,"turn.finished").path("data").path("status").asText());
                assertFalse(Files.exists(project.resolve("canceled.txt")));
            }
        }
    }
    @Test void persistsAndContinuesSessionAfterRestart() throws Exception {
        try(var server=new MockWebServer()) {
            server.start();
            server.enqueue(response("第一段", "第二段")); server.enqueue(response("继续成功"));
            var events=new LinkedBlockingQueue<ObjectNode>();
            var home=Files.createDirectory(temp.resolve("home"));var project=Files.createDirectory(temp.resolve("repo"));
            var env=Map.of("DEEPSEEK_API_KEY","fake-key","DEEPSEEK_BASE_URL",server.url("/").toString());
            String sessionId;
            try(var service=new DesktopService(home,env,events::add)) {
                var added=service.handle("project.add",params("path",project.toString()));
                var session=service.handle("session.create",params("projectId",added.path("id").asText(),"model","deepseek:deepseek-chat"));
                sessionId=session.path("id").asText();
                service.handle("turn.start",params("sessionId",sessionId,"input","我的标记是 maple"));
                assertTimeoutPreemptively(Duration.ofSeconds(15),()->{
                    while(true){var event=events.poll(10,TimeUnit.SECONDS);assertNotNull(event);if(event.path("event").asText().equals("turn.finished"))break;}
                });
                assertTrue(events.isEmpty());
                var state=service.handle("state",params());
                assertEquals("completed",state.path("sessions").get(0).path("turns").get(0).path("status").asText());
            }
            assertTrue(server.takeRequest(5,TimeUnit.SECONDS).getBody().readUtf8().contains("maple"));
            try(var service=new DesktopService(home,env,event->{})) {
                service.handle("turn.start",params("sessionId",sessionId,"input","刚才的标记是什么"));
                var request=server.takeRequest(10,TimeUnit.SECONDS);
                assertNotNull(request);assertTrue(request.getBody().readUtf8().contains("maple"));
            }
        }
    }
    @Test void waitsForApprovalSerializesDirectoryAndRejectsExpiredApproval() throws Exception {
        try(var server=new MockWebServer()) {
            server.start();server.enqueue(tool("a.txt","批准后的内容"));server.enqueue(response("写入完成"));
            server.enqueue(tool("denied.txt","不应写入"));server.enqueue(response("已拒绝操作"));
            var events=new LinkedBlockingQueue<ObjectNode>();Path project=Files.createDirectory(temp.resolve("project"));
            try(var service=new DesktopService(temp.resolve("home"),Map.of("DEEPSEEK_API_KEY","fake","DEEPSEEK_BASE_URL",server.url("/").toString()),events::add)) {
                var added=service.handle("project.add",params("path",project.toString()));String projectId=added.path("id").asText();
                String id=service.handle("session.create",params("projectId",projectId)).path("id").asText();
                String second=service.handle("session.create",params("projectId",projectId)).path("id").asText();
                service.handle("turn.start",params("sessionId",id,"input","写入文件"));
                var approval=await(events,"approval.request");assertFalse(Files.exists(project.resolve("a.txt")));
                assertThrows(IllegalStateException.class,()->service.handle("turn.start",params("sessionId",second,"input","不能并发")));
                var answer=params("sessionId",id,"approvalId",approval.path("data").path("id").asText()).put("allow",true);
                service.handle("approval.respond",answer);await(events,"turn.finished");
                assertEquals("批准后的内容",Files.readString(project.resolve("a.txt")));
                assertThrows(IllegalStateException.class,()->service.handle("approval.respond",answer));
                service.handle("turn.start",params("sessionId",second,"input","拒绝写入"));
                var denied=await(events,"approval.request");
                service.handle("approval.respond",params("sessionId",second,"approvalId",denied.path("data").path("id").asText()).put("allow",false));
                await(events,"turn.finished");assertFalse(Files.exists(project.resolve("denied.txt")));
                server.takeRequest();server.takeRequest();
                String isolated=server.takeRequest(5,TimeUnit.SECONDS).getBody().readUtf8();assertFalse(isolated.contains("批准后的内容"));
            }
        }
    }
    @Test void publishesDeltasBeforeCompletionAndCanCancel() throws Exception {
        try(var server=new MockWebServer()) {
            server.start();
            // 队列的预览响应也会限速读取请求，长系统提示可能先耗尽等待时间。
            // 使用 Dispatcher 在完整请求到达后才限速响应，单独验证流式与取消。
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    return response("第一段".repeat(30), "第二段".repeat(30), "第三段".repeat(30), "第四段".repeat(30))
                            .throttleBody(350,500,TimeUnit.MILLISECONDS);
                }
            });
            var events=new LinkedBlockingQueue<ObjectNode>();Path project=Files.createDirectory(temp.resolve("project"));
            try(var service=new DesktopService(temp.resolve("home"),Map.of("DEEPSEEK_API_KEY","fake","DEEPSEEK_BASE_URL",server.url("/").toString()),events::add)) {
                var added=service.handle("project.add",params("path",project.toString()));
                String id=service.handle("session.create",params("projectId",added.path("id").asText())).path("id").asText();
                service.handle("turn.start",params("sessionId",id,"input","流式取消"+"上下文".repeat(5000)));
                assertFalse(await(events,"TextDelta").path("data").path("text").asText().isBlank());
                assertEquals("running",service.handle("state",params()).path("sessions").get(0).path("turns").get(0).path("status").asText());
                service.handle("turn.cancel",params("sessionId",id));
                assertEquals("canceled",await(events,"turn.finished").path("data").path("status").asText());
            }
        }
    }
    private ObjectNode await(BlockingQueue<ObjectNode> events,String type) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        var seen=new ArrayList<String>();
        while(System.nanoTime()<end){var event=events.poll(1,TimeUnit.SECONDS);if(event!=null){seen.add(event.toString());if(event.path("event").asText().equals(type))return event;}}
        throw new AssertionError("没有收到事件："+type+"，实际事件："+seen);
    }
    @Test void finalAnswerAndStoredHistoryAreRedactedWithoutBreakingEvents() throws Exception {
        try(var server=new MockWebServer()) {
            server.start();server.enqueue(response("我的密钥是 desktop-secret-example。示例 token=abc"));
            var events=new LinkedBlockingQueue<ObjectNode>();Path project=Files.createDirectory(temp.resolve("project"));Path home=temp.resolve("home");
            try(var service=new DesktopService(home,Map.of("DEEPSEEK_API_KEY","desktop-secret-example","DEEPSEEK_BASE_URL",server.url("/").toString()),events::add)) {
                var projectId=service.handle("project.add",params("path",project.toString())).path("id").asText();
                var sessionId=service.handle("session.create",params("projectId",projectId)).path("id").asText();
                service.handle("turn.start",params("sessionId",sessionId,"input","检查脱敏"));
                var finished=await(events,"turn.finished");assertEquals("completed",finished.path("data").path("status").asText());
                assertFalse(finished.toString().contains("desktop-secret-example"));assertFalse(finished.toString().contains("token=abc"));
                assertFalse(service.handle("state",params()).toString().contains("desktop-secret-example"));
                assertFalse(Files.readString(home.resolve(".xhlcli/desktop/state.json")).contains("desktop-secret-example"));
            }
        }
    }
    @Test void onlyExplicitImageAttachmentsProduceImagePayloads() throws Exception {
        try(var server=new MockWebServer()) {
            server.start();server.enqueue(response("文本检查完成"));server.enqueue(response("图片检查完成"));
            var events=new LinkedBlockingQueue<ObjectNode>();Path project=Files.createDirectory(temp.resolve("project"));
            Path image=temp.resolve("outside.png");javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",image.toFile());
            Path file=project.resolve("notes.txt");Files.writeString(file,"保留原文 @image:<"+image+"> 和 @clipboard");
            try(var service=new DesktopService(temp.resolve("home"),Map.of("OPENAI_API_KEY","fake","OPENAI_BASE_URL",server.url("/").toString()),events::add)) {
                var projectId=service.handle("project.add",params("path",project.toString())).path("id").asText();
                var sessionId=service.handle("session.create",params("projectId",projectId,"model","openai:gpt-4o")).path("id").asText();
                var p=params("sessionId",sessionId,"input","读取附件");p.putArray("attachments").addObject().put("path",file.toString()).put("type","file");
                service.handle("turn.start",p);assertEquals("completed",await(events,"turn.finished").path("data").path("status").asText());
                String body=server.takeRequest(5,TimeUnit.SECONDS).getBody().readUtf8();assertTrue(body.contains("@clipboard"));assertFalse(body.contains("image_url"));
                p.putArray("attachments").addObject().put("path",image.toString()).put("type","image");
                service.handle("turn.start",p);assertEquals("completed",await(events,"turn.finished").path("data").path("status").asText());
                assertTrue(server.takeRequest(5,TimeUnit.SECONDS).getBody().readUtf8().contains("data:image/"));
                service.handle("session.update",params("sessionId",sessionId,"model","deepseek:deepseek-chat"));
                assertThrows(Exception.class,()->service.handle("turn.start",p));
            }
        }
    }
    private MockResponse tool(String file,String content) throws Exception {
        var call=Map.of("index",0,"id","call-1","type","function","function",Map.of("name","write_file","arguments",DesktopJson.MAPPER.writeValueAsString(Map.of("path",file,"content",content))));
        String event=DesktopJson.MAPPER.writeValueAsString(Map.of("choices",List.of(Map.of("delta",Map.of("tool_calls",List.of(call)),"finish_reason","tool_calls"))));
        return new MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: "+event+"\n\ndata: [DONE]\n\n");
    }
    private MockResponse response(String... chunks) {
        StringBuilder s=new StringBuilder();
        for(String chunk:chunks)s.append("data: {\"choices\":[{\"delta\":{\"content\":\"").append(chunk).append("\"},\"finish_reason\":null}]}\n\n");
        s.append("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n");
        return new MockResponse().setHeader("Content-Type","text/event-stream").setBody(s.toString());
    }
}
