package com.xhlcli.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhlcli.agent.RunLimits;
import com.xhlcli.runtime.task.*;
import java.nio.file.Path;
import java.time.*;

/** 桌面计划只接收已添加的项目；后台任务始终沿用只读执行器。 */
final class DesktopSchedules implements AutoCloseable {
    private final DesktopStore store;
    private final DesktopConfig config;
    private final ScheduledTaskService service;
    DesktopSchedules(Path home,DesktopStore store,DesktopConfig config) throws Exception {
        this.store=store;this.config=config;
        service=new ScheduledTaskService(home.resolve(".xhlcli/desktop/schedules.db"),home,Clock.systemUTC(),(execution,model)->{
            var limits=config.limits(execution.workspace()).agentSettings();
            String provider=model.substring(0,model.indexOf(':'));
            var runner=new BackgroundTaskRunner(()->{
                try{return config.client(execution.workspace(),model);}catch(Exception e){throw new IllegalStateException("定时任务模型不可用，请检查设置",e);}
            },home,new RunLimits(limits.maxIterations(),limits.timeout()),limits.maxConcurrency(),limits.toolTimeout(),config.environmentKey(execution.workspace(),provider));
            var safeExecution=new TaskExecution(execution.id(),execution.prompt(),execution.workspace(),execution.cancellation(),
                    text->execution.progress().accept(config.redact(text,execution.workspace())));
            var result=runner.run(safeExecution);
            return new TaskOutcome(result.status(),config.redact(result.result(),execution.workspace()),config.redact(result.error(),execution.workspace()));
        },true);
    }
    JsonNode handle(String method,ObjectNode p) throws Exception {
        return switch(method) {
            case "schedules.list" -> {
                var result=DesktopJson.MAPPER.createObjectNode();result.set("schedules",DesktopJson.MAPPER.valueToTree(service.list()));
                result.put("zone",ZoneId.systemDefault().getId());result.put("error",service.error());yield result;
            }
            case "schedules.save" -> {
                var project=store.project(p.path("projectId").asText());Path path=Path.of(project.path("path").asText()).toRealPath();
                String model=p.path("model").asText();if(model.isBlank())throw new IllegalArgumentException("请选择已配置的模型");
                try(var client=config.client(path,model)){}
                yield DesktopJson.MAPPER.valueToTree(service.save(p.path("id").asText(null),p.path("title").asText(),p.path("prompt").asText(),path,model,p.path("time").asText(),p.path("zone").asText()));
            }
            case "schedules.runs" -> DesktopJson.MAPPER.valueToTree(service.runs(p.path("id").asText()));
            case "schedules.toggle" -> {
                if(!p.path("enabled").isBoolean())throw new IllegalArgumentException("启用状态无效");
                service.setEnabled(p.path("id").asText(),p.path("enabled").asBoolean());yield ok();
            }
            case "schedules.run" -> {service.runNow(p.path("id").asText());yield ok();}
            case "schedules.cancel" -> {service.cancel(p.path("id").asText(),p.path("taskId").asText());yield ok();}
            case "schedules.delete" -> {service.delete(p.path("id").asText());yield ok();}
            default -> throw new IllegalArgumentException("未知定时任务操作");
        };
    }
    private ObjectNode ok(){return DesktopJson.MAPPER.createObjectNode().put("ok",true);}
    @Override public void close() throws Exception {service.close();}
}
