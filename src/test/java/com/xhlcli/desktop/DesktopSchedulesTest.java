package com.xhlcli.desktop;

import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class DesktopSchedulesTest {
    @TempDir Path temp;
    ObjectNode p(String... values){var p=DesktopJson.MAPPER.createObjectNode();for(int i=0;i<values.length;i+=2)p.put(values[i],values[i+1]);return p;}
    @Test void scheduleUsesBoundProjectRealTaskAndPersistsResults() throws Exception {
        Path workspace=Files.createDirectory(temp.resolve("project")),home=temp.resolve("home");String id;
        try(var server=new MockWebServer()) {
            server.start();server.enqueue(new MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: {\"choices\":[{\"delta\":{\"content\":\"今日新闻汇总\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"));
            var env=Map.of("DEEPSEEK_API_KEY","fake-key","DEEPSEEK_BASE_URL",server.url("/").toString());
            try(var service=new DesktopService(home,env,e->{})) {
                String projectId=service.handle("project.add",p("path",workspace.toString())).path("id").asText();
                var input=p("title","新闻","prompt","抓取新闻","projectId",projectId,"model","deepseek:deepseek-chat","time","08:00","zone","UTC");
                id=service.handle("schedules.save",input).path("id").asText();assertFalse(id.isBlank());
                assertEquals(1,service.handle("schedules.list",p()).path("schedules").size());
                service.handle("schedules.run",p("id",id));
                long deadline=System.nanoTime()+Duration.ofSeconds(6).toNanos();
                while(System.nanoTime()<deadline&& !service.handle("schedules.runs",p("id",id)).path(0).path("status").asText().equals("completed"))Thread.sleep(20);
                var run=service.handle("schedules.runs",p("id",id)).get(0);
                assertEquals("completed",run.path("status").asText());assertEquals("今日新闻汇总",run.path("result").asText());
                var request=server.takeRequest(1,TimeUnit.SECONDS);assertNotNull(request);String body=request.getBody().readUtf8();
                assertTrue(body.contains("web_search"));assertTrue(body.contains("web_fetch"));assertFalse(body.contains("\"name\":\"write_file\""));
                assertTrue(service.handle("state",p()).path("sessions").isEmpty());
                assertThrows(Exception.class,()->service.handle("schedules.save",input.deepCopy().put("projectId","不存在")));
                assertThrows(Exception.class,()->service.handle("schedules.save",input.deepCopy().put("zone","未知时区")));
                service.handle("schedules.toggle",p("id",id).put("enabled",false));
            }
            try(var service=new DesktopService(home,env,e->{})) {
                assertFalse(service.handle("schedules.list",p()).path("schedules").get(0).path("enabled").asBoolean());
                assertEquals("今日新闻汇总",service.handle("schedules.runs",p("id",id)).get(0).path("result").asText());
            }
        }
    }
    @Test void streamingAndCanceledResultsRedactOtherProviderCredentialsBeforeStorage() throws Exception {
        Path workspace=Files.createDirectory(temp.resolve("project")),home=temp.resolve("home");
        String secret="fake-other-provider-secret-12345";
        try(var server=new MockWebServer()) {
            server.start();
            String chunk="data: {\"choices\":[{\"delta\":{\"content\":\""+secret+" 新闻内容"+"资料".repeat(300)+"\"}}]}\n\n";
            server.enqueue(new MockResponse().setHeader("Content-Type","text/event-stream")
                    .setBody(chunk.repeat(50)).throttleBody(chunk.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,250,TimeUnit.MILLISECONDS));
            var env=Map.of("DEEPSEEK_API_KEY","fake-current-key","DEEPSEEK_BASE_URL",server.url("/").toString(),"OPENAI_API_KEY",secret);
            try(var service=new DesktopService(home,env,e->{})) {
                String projectId=service.handle("project.add",p("path",workspace.toString())).path("id").asText();
                String id=service.handle("schedules.save",p("title","脱敏验收","prompt","新闻","projectId",projectId,"model","deepseek:deepseek-chat","time","08:00","zone","UTC")).path("id").asText();
                service.handle("schedules.run",p("id",id));
                long deadline=System.nanoTime()+Duration.ofSeconds(6).toNanos();
                com.fasterxml.jackson.databind.JsonNode run;
                do {Thread.sleep(30);run=service.handle("schedules.runs",p("id",id)).path(0);} while(System.nanoTime()<deadline&&run.path("result").asText().isBlank());
                assertFalse(run.path("result").asText().isBlank(),"必须观察到流式进度");
                boolean exposed=run.toString().contains(secret);
                service.handle("schedules.cancel",p("id",id,"taskId",run.path("taskId").asText()));
                while(System.nanoTime()<deadline&&!service.handle("schedules.runs",p("id",id)).path(0).path("status").asText().equals("canceled"))Thread.sleep(30);
                assertFalse(exposed,"运行中接口不得暴露其他模型密钥");
                try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+home.resolve(".xhlcli/desktop/schedules.db"));var q=c.createStatement();var rows=q.executeQuery("SELECT result FROM runtime_tasks")) {
                    assertTrue(rows.next());assertFalse(rows.getString(1).contains(secret));assertTrue(rows.getString(1).contains("***"));
                }
            }
        }
    }

}
