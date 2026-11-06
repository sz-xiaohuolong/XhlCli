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
            server.start();server.enqueue(response("第一段".repeat(30), "第二段".repeat(30), "第三段".repeat(30), "第四段".repeat(30)).throttleBody(350,500,TimeUnit.MILLISECONDS));
            var events=new LinkedBlockingQueue<ObjectNode>();Path project=Files.createDirectory(temp.resolve("project"));
            try(var service=new DesktopService(temp.resolve("home"),Map.of("DEEPSEEK_API_KEY","fake","DEEPSEEK_BASE_URL",server.url("/").toString()),events::add)) {
                var added=service.handle("project.add",params("path",project.toString()));
                String id=service.handle("session.create",params("projectId",added.path("id").asText())).path("id").asText();
                service.handle("turn.start",params("sessionId",id,"input","流式取消"));
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
