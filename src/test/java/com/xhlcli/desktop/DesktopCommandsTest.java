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

class DesktopCommandsTest {
    @TempDir Path temp;
    ObjectNode params(String... values) {
        var node=DesktopJson.MAPPER.createObjectNode();
        for(int i=0;i<values.length;i+=2)node.put(values[i],values[i+1]);
        return node;
    }
    String session(DesktopService service) throws Exception {
        Path project=temp.resolve("project");Files.createDirectories(project);
        String projectId=service.handle("project.add",params("path",project.toString())).path("id").asText();
        return service.handle("session.create",params("projectId",projectId)).path("id").asText();
    }
    JsonNode await(BlockingQueue<ObjectNode> events,String type) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<end){var item=events.poll(1,TimeUnit.SECONDS);if(item!=null&&item.path("event").asText().equals(type))return item.path("data");}
        throw new AssertionError("未收到事件："+type);
    }
    JsonNode command(DesktopService service,BlockingQueue<ObjectNode> events,String id,String input) throws Exception {
        service.handle("turn.start",params("sessionId",id,"input",input));
        return await(events,"turn.finished");
    }
    @Test void localCommandsWorkWithoutModelAndSurviveRestart() throws Exception {
        var events=new LinkedBlockingQueue<ObjectNode>();String id;
        try(var service=new DesktopService(temp.resolve("home"),Map.of(),events::add)) {
            id=session(service);
            var catalog=service.handle("commands.list",params("sessionId",id));
            assertTrue(catalog.toString().contains("/plan"));assertTrue(catalog.toString().contains("/mcp"));
            var help=command(service,events,id,"/HELP");assertEquals("completed",help.path("status").asText());assertTrue(help.path("answer").asText().contains("/team"));
            assertEquals("completed",command(service,events,id,"/save 项目标记 maple").path("status").asText());
            assertTrue(command(service,events,id,"/memory search maple").path("answer").asText().contains("maple"));
            assertTrue(command(service,events,id,"/mcp").path("answer").asText().contains("未配置"));
            assertTrue(command(service,events,id,"/context").path("answer").asText().contains("上下文"));
            assertTrue(command(service,events,id,"/plan").path("answer").asText().contains("/plan <"));
            var unknown=command(service,events,id,"/not-a-command");assertEquals("failed",unknown.path("status").asText());assertTrue(unknown.path("error").asText().contains("未知"));
        }
        try(var service=new DesktopService(temp.resolve("home"),Map.of(),events::add)) {
            var state=service.handle("state",params());assertEquals(7,state.path("sessions").get(0).path("turns").size());
            assertTrue(command(service,events,id,"/memory list").path("answer").asText().contains("maple"));
        }
    }
    @Test void modelSelectionValidatesAndPersistsWithoutSendingModelRequest() throws Exception {
        var events=new LinkedBlockingQueue<ObjectNode>();String id;
        var env=Map.of("DEEPSEEK_API_KEY","test-only","OPENAI_API_KEY","test-only");
        try(var service=new DesktopService(temp.resolve("home"),env,events::add)) {
            id=session(service);
            assertTrue(command(service,events,id,"/model list").path("answer").asText().contains("openai:"));
            assertEquals("completed",command(service,events,id,"/model use openai:gpt-4o").path("status").asText());
            assertEquals("openai:gpt-4o",service.handle("state",params()).path("sessions").get(0).path("model").asText());
            assertEquals("failed",command(service,events,id,"/model use unknown:invalid").path("status").asText());
        }
        try(var service=new DesktopService(temp.resolve("home"),env,events::add)) {
            assertEquals("openai:gpt-4o",service.handle("state",params()).path("sessions").get(0).path("model").asText());
        }
    }
    @Test void skillsExecuteThroughRealAgentAndRespectDisabledState() throws Exception {
        Path skill=temp.resolve("project/.xhlcli/skills/example/SKILL.md");Files.createDirectories(skill.getParent());
        Files.writeString(skill,"---\nname: example\ndescription: 测试技能\n---\n技能专属约束：回答包含 maple。\n");
        try(var server=new MockWebServer()) {
            server.start();server.enqueue(response("已遵循技能 maple"));
            var events=new LinkedBlockingQueue<ObjectNode>();
            try(var service=new DesktopService(temp.resolve("home"),Map.of("DEEPSEEK_API_KEY","test-only","DEEPSEEK_BASE_URL",server.url("/").toString()),events::add)) {
                String id=session(service);
                assertTrue(service.handle("commands.list",params("sessionId",id)).toString().contains("/example"));
                assertEquals("completed",command(service,events,id,"/example 执行检查").path("status").asText());
                assertTrue(server.takeRequest(5,TimeUnit.SECONDS).getBody().readUtf8().contains("技能专属约束"));
                assertEquals("completed",command(service,events,id,"/skill disable example").path("status").asText());
                assertEquals("failed",command(service,events,id,"/example 不可执行").path("status").asText());
                assertEquals(1,server.getRequestCount());
                assertEquals("completed",command(service,events,id,"/skill enable example").path("status").asText());
            }
        }
    }
    @Test void planUsesPlannerReviewAndKeepsResultForFollowingConversation() throws Exception {
        try(var server=new MockWebServer()) {
            server.start();server.enqueue(response("{\"summary\":\"检查计划\",\"tasks\":[{\"id\":\"t1\",\"description\":\"分析 maple\",\"type\":\"ANALYSIS\",\"dependencies\":[]}]}"));
            server.enqueue(response("maple 计划结果"));server.enqueue(response("继续计划对话"));
            var events=new LinkedBlockingQueue<ObjectNode>();
            try(var service=new DesktopService(temp.resolve("home"),Map.of("DEEPSEEK_API_KEY","test-only","DEEPSEEK_BASE_URL",server.url("/").toString()),events::add)) {
                String id=session(service);
                service.handle("turn.start",params("sessionId",id,"input","/plan 分析工程模块依赖关系并给出多阶段实施方案"));
                var approval=await(events,"approval.request");assertEquals("plan.execute",approval.path("request").path("toolName").asText());assertEquals(1,server.getRequestCount());
                service.handle("approval.respond",params("sessionId",id,"approvalId",approval.path("id").asText()).put("allow",true));
                var finished=await(events,"turn.finished");assertEquals("completed",finished.path("status").asText());assertTrue(finished.path("answer").asText().contains("maple"));
                assertEquals("completed",command(service,events,id,"继续刚才的计划").path("status").asText());
                server.takeRequest();server.takeRequest();assertTrue(server.takeRequest(5,TimeUnit.SECONDS).getBody().readUtf8().contains("maple 计划结果"));
            }
        }
    }
    @Test void teamRunsPlanningWorkerAndReview() throws Exception {
        try(var server=new MockWebServer()) {
            server.start();server.enqueue(response("{\"summary\":\"分析\",\"steps\":[{\"id\":\"s1\",\"description\":\"检查模块\",\"type\":\"ANALYSIS\",\"dependencies\":[]}]}"));
            server.enqueue(response("团队分析完成"));server.enqueue(response("{\"approved\":true,\"summary\":\"通过\",\"issues\":[]}"));
            var events=new LinkedBlockingQueue<ObjectNode>();
            try(var service=new DesktopService(temp.resolve("home"),Map.of("DEEPSEEK_API_KEY","test-only","DEEPSEEK_BASE_URL",server.url("/").toString()),events::add)) {
                String id=session(service);var result=command(service,events,id,"/team 分析当前模块");
                assertEquals("completed",result.path("status").asText());assertEquals(3,server.getRequestCount());
                assertTrue(result.path("answer").asText().contains("团队分析完成"));
            }
        }
    }
    @Test void mcpLifecyclePersistsAcrossCommandsAndExposesRealResources() throws Exception {
        try(var server=new MockWebServer()) {
            server.setDispatcher(new Dispatcher(){
                @Override public MockResponse dispatch(RecordedRequest request) {
                    try {
                        var rpc=DesktopJson.MAPPER.readTree(request.getBody().readUtf8());
                        Object result=switch(rpc.path("method").asText()) {
                            case "initialize" -> Map.of("protocolVersion","2024-11-05","serverInfo",Map.of("name","fixture","version","1"),"capabilities",Map.of("tools",Map.of(),"resources",Map.of()));
                            case "tools/list" -> Map.of("tools",List.of(Map.of("name","echo","description","测试工具","inputSchema",Map.of("type","object","properties",Map.of()))));
                            case "resources/list" -> Map.of("resources",List.of(Map.of("uri","memo://fixture","name","测试资源","mimeType","text/plain")));
                            case "resources/read" -> Map.of("contents",List.of(Map.of("uri","memo://fixture","text","MCP 真实资源 maple","mimeType","text/plain")));
                            default -> Map.of();
                        };
                        if(!rpc.has("id"))return new MockResponse().setResponseCode(202);
                        return new MockResponse().setHeader("Content-Type","application/json").setBody(DesktopJson.MAPPER.createObjectNode().put("jsonrpc","2.0").set("id",rpc.get("id")).toString().replaceFirst("}$",",\"result\":"+DesktopJson.MAPPER.writeValueAsString(result)+"}"));
                    }catch(Exception failure){return new MockResponse().setResponseCode(500);}
                }
            });server.start();
            Path file=temp.resolve("project/.xhlcli/mcp.json");Files.createDirectories(file.getParent());
            Files.writeString(file,DesktopJson.MAPPER.writeValueAsString(Map.of("mcpServers",Map.of("fixture",Map.of("url",server.url("/mcp").toString())))));
            var events=new LinkedBlockingQueue<ObjectNode>();
            try(var service=new DesktopService(temp.resolve("home"),Map.of(),events::add)) {
                String id=session(service);
                assertTrue(command(service,events,id,"/mcp list").path("answer").asText().contains("READY"));
                assertTrue(command(service,events,id,"/mcp tools").path("answer").asText().contains("echo"));
                assertTrue(command(service,events,id,"/mcp read memo://fixture").path("answer").asText().contains("maple"));
                assertTrue(command(service,events,id,"/mcp stop fixture").path("answer").asText().contains("STOPPED"));
                assertTrue(command(service,events,id,"/mcp status fixture").path("answer").asText().contains("STOPPED"));
                assertTrue(command(service,events,id,"/mcp start fixture").path("answer").asText().contains("READY"));
                assertTrue(command(service,events,id,"/mcp restart fixture").path("answer").asText().contains("READY"));
            }
        }
    }
    @Test void rejectingPlanReviewDoesNotRunAnyWorker() throws Exception {
        try(var server=new MockWebServer()) {
            server.start();server.enqueue(response("{\"summary\":\"计划\",\"tasks\":[{\"id\":\"t1\",\"description\":\"分析\",\"type\":\"ANALYSIS\",\"dependencies\":[]}]}"));
            var events=new LinkedBlockingQueue<ObjectNode>();
            try(var service=new DesktopService(temp.resolve("home"),Map.of("DEEPSEEK_API_KEY","test-only","DEEPSEEK_BASE_URL",server.url("/").toString()),events::add)) {
                String id=session(service);
                service.handle("turn.start",params("sessionId",id,"input","/plan 分析工程模块依赖关系并给出多阶段实施方案"));
                var approval=await(events,"approval.request");
                service.handle("approval.respond",params("sessionId",id,"approvalId",approval.path("id").asText()).put("allow",false));
                assertEquals("canceled",await(events,"turn.finished").path("status").asText());
                assertEquals(1,server.getRequestCount());
            }
        }
    }
    private MockResponse response(String text) throws Exception {
        String data=DesktopJson.MAPPER.writeValueAsString(Map.of("choices",List.of(Map.of("delta",Map.of("content",text),"finish_reason","stop"))));
        return new MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: "+data+"\n\ndata: [DONE]\n\n");
    }
}
