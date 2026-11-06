package com.xhlcli.desktop;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.xhlcli.hitl.*;
import com.xhlcli.llm.*;
import com.xhlcli.model.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** 桌面命令入口：状态修改串行化，Agent 在独立任务中运行，取消和审批可随时到达。 */
public final class DesktopService implements AutoCloseable {
    private final Path home;
    private final DesktopStore store;
    private final DesktopConfig config;
    private final DesktopSchedules schedules;
    private final Consumer<ObjectNode> publish;
    private final ExecutorService workers=Executors.newCachedThreadPool();
    private final Map<String,ActiveTurn> active=new HashMap<>();
    private final Map<Path,String> busy=new HashMap<>();
    private final Map<Path,DesktopWorkspace> workspaces=new ConcurrentHashMap<>();
    private volatile boolean closing;
    public DesktopService(Path home, Map<String,String> environment, Consumer<ObjectNode> publish) throws Exception {
        this.home=home;this.publish=publish;store=new DesktopStore(home);config=new DesktopConfig(home,environment);
        try{schedules=new DesktopSchedules(home,store,config);}catch(Exception failure){store.close();workers.shutdownNow();throw failure;}
    }
    public synchronized JsonNode handle(String method, ObjectNode p) throws Exception {
        if(closing)throw new IllegalStateException("应用正在退出");
        if(method.startsWith("schedules."))return schedules.handle(method,p);
        return switch(method) {
            case "state" -> publicState();
            case "skills.list" -> DesktopSkills.list(home,p.hasNonNull("projectId")||p.hasNonNull("sessionId")?workspace(p):null);
            case "skills.set" -> DesktopSkills.set(home,p.hasNonNull("projectId")||p.hasNonNull("sessionId")?workspace(p):null,p);
            case "commands.list" -> DesktopJson.MAPPER.valueToTree(DesktopCommands.catalog(
                    p.hasNonNull("sessionId")||p.hasNonNull("projectId")?resources(workspace(p)):null));
            case "project.add" -> addProject(p.path("path").asText());
            case "session.create" -> createSession(p);
            case "session.update" -> updateSession(p);
            case "turn.start" -> start(p);
            case "turn.cancel" -> cancel(p.path("sessionId").asText());
            case "approval.respond" -> approve(p);
            case "changes" -> changes(p,false);
            case "undo" -> changes(p,true);
            case "config.get" -> config.describe(workspace(p));
            case "config.save" -> {config.save(p);yield config.describe(workspace(p));}
            default -> throw new IllegalArgumentException("未知桌面操作："+method);
        };
    }
    public JsonNode testConnection(ObjectNode p) throws Exception {
        Path workspace;String model=p.path("model").asText();
        synchronized(this){workspace=workspace(p);}
        try(var client=config.client(workspace,model)) {
            var token=new CancellationToken();
            var timeout=Executors.newSingleThreadScheduledExecutor();
            var future=timeout.schedule(token::cancel,30,TimeUnit.SECONDS);
            try {
                var result=client.stream(List.of(ChatMessage.user("只回复 OK")),delta->{},token);
                if(token.isCancelled()||result==null||result.content().isBlank())throw new IllegalStateException("连接测试未收到有效文本响应");
                return DesktopJson.MAPPER.valueToTree(Map.of("ok",true,"message","连接成功","model",client.modelName()));
            } finally {future.cancel(false);timeout.shutdownNow();}
        }
    }
    private ObjectNode publicState() {
        var state=store.state.deepCopy();
        for(var session:state.withArray("sessions"))((ObjectNode)session).remove("history");
        return state;
    }
    private ObjectNode addProject(String value) throws Exception {
        Path path=Path.of(value).toRealPath();
        if(!Files.isDirectory(path))throw new IllegalArgumentException("请选择文件夹");
        for(var item:store.state.withArray("projects"))if(item.path("path").asText().equals(path.toString()))return (ObjectNode)item;
        var item=store.state.withArray("projects").addObject();item.put("id",DesktopStore.id());item.put("path",path.toString());
        item.put("name",path.getFileName()==null?path.toString():path.getFileName().toString());store.save();return item.deepCopy();
    }
    private ObjectNode createSession(ObjectNode p) throws Exception {
        String approvalMode=p.has("approvalMode")?validateApprovalMode(p.get("approvalMode")):"on-request";
        var project=store.project(p.path("projectId").asText());
        Path workspace=Path.of(project.path("path").asText());
        String model=p.path("model").asText("");
        if(model.isBlank())model=config.defaultModel(workspace);
        String effort=ReasoningOptions.validate(model,p.path("reasoningEffort").asText("default"));
        var item=store.state.withArray("sessions").addObject();item.put("id",DesktopStore.id());item.put("projectId",project.path("id").asText());
        item.put("path",workspace.toString());item.put("title","新聊天");item.put("model",model);item.put("updatedAt",Instant.now().toString());
        item.put("approvalMode",approvalMode);item.put("reasoningEffort",effort);
        item.put("pinned",false);item.put("archived",false);item.putArray("history");item.putArray("turns");store.save();return item.deepCopy();
    }
    private ObjectNode updateSession(ObjectNode p) throws Exception {
        var stored=store.session(p.path("sessionId").asText());
        var session=stored.deepCopy();
        if(p.has("approvalMode"))session.put("approvalMode",validateApprovalMode(p.get("approvalMode")));
        if(p.has("title")){String title=p.path("title").asText().strip();if(title.isEmpty()||title.length()>160)throw new IllegalArgumentException("标题需要 1～160 个字符");session.put("title",title);}
        if(p.has("model")){String model=p.path("model").asText();if(model.length()>240||!model.contains(":"))throw new IllegalArgumentException("模型格式无效");session.put("model",model);}
        if(p.has("model"))session.put("reasoningEffort","default");
        if(p.has("reasoningEffort"))session.put("reasoningEffort",ReasoningOptions.validate(session.path("model").asText(),p.path("reasoningEffort").asText("")));
        if(p.has("pinned"))session.put("pinned",p.path("pinned").asBoolean());
        if(p.has("archived")){if(active.containsKey(session.path("id").asText()))throw new IllegalStateException("请先停止当前任务再归档");session.put("archived",p.path("archived").asBoolean());}
        // 只替换会话设置，保留执行线程持有的轮次和历史节点。
        var fields=List.of("title","model","pinned","archived","approvalMode","reasoningEffort");
        var previous=stored.deepCopy();
        for(String field:fields)if(p.has(field)||(field.equals("reasoningEffort")&&p.has("model")))stored.set(field,session.get(field));
        try {store.save();}catch(Exception failure){
            for(String field:fields)if(p.has(field)||(field.equals("reasoningEffort")&&p.has("model"))){if(previous.has(field))stored.set(field,previous.get(field));else stored.remove(field);}
            throw failure;
        }
        var current=active.get(session.path("id").asText());
        if(p.has("approvalMode")&&"auto".equals(session.path("approvalMode").asText())&&current!=null
                &&!current.token.isCancelled()&&current.approval!=null&&!current.approval.isDone()) {
            emit(current,"approval.automatic",Map.of("id",current.approvalId,"request",current.approvalRequest));
            current.approval.complete(ApprovalResult.approve());
        }
        var result=stored.deepCopy();result.remove("history");return result;
    }
    private String validateApprovalMode(JsonNode value) {
        if(!value.isTextual()||!Set.of("on-request","auto").contains(value.asText()))
            throw new IllegalArgumentException("审批模式无效，请选择按需审批或帮我审批");
        return value.asText();
    }
    private Path workspace(ObjectNode p) throws Exception {
        if(p.hasNonNull("sessionId")&&!p.path("sessionId").asText().isBlank())return Path.of(store.session(p.path("sessionId").asText()).path("path").asText()).toRealPath();
        if(p.hasNonNull("projectId")&&!p.path("projectId").asText().isBlank())return Path.of(store.project(p.path("projectId").asText()).path("path").asText()).toRealPath();
        return home;
    }
    private ObjectNode start(ObjectNode p) throws Exception {
        String id=p.path("sessionId").asText(), input=p.path("input").asText().strip();
        if(input.isEmpty()||input.length()>200000)throw new IllegalArgumentException("请输入任务，内容不能超过 200000 字符");
        var session=store.session(id);Path workspace=Path.of(session.path("path").asText()).toRealPath();
        if(busy.containsKey(workspace))throw new IllegalStateException("该工作目录已有任务运行，请等待结束或先停止。输入内容已保留。");
        if(session.path("archived").asBoolean())throw new IllegalStateException("请先恢复此聊天");
        String model=session.path("model").asText();
        String turnId=DesktopStore.id();
        var attachments=p.withArray("attachments");
        if(input.startsWith("/")&&!attachments.isEmpty())throw new IllegalArgumentException("斜杠指令暂不接受附件，请移除附件或使用普通消息发送");
        StringBuilder prompt=new StringBuilder(input);
        var imageParts=new ArrayList<com.xhlcli.model.ContentPart>();
        if(attachments.size()>8)throw new IllegalArgumentException("单次最多添加 8 个附件");
        for(var attachment:attachments) {
            Path file=Path.of(attachment.path("path").asText()).toRealPath();
            if(!Files.isRegularFile(file)||Files.size(file)>10*1024*1024)throw new IllegalArgumentException("附件须为不超过 10 MB 的普通文件");
            if(attachment.path("type").asText().equals("image")) {
                try(var client=config.client(workspace,model)) {if(!client.capabilities().supportsVision())throw new IllegalArgumentException("当前模型不支持图片，请切换支持视觉的模型");}
                Path dir=home.resolve(".xhlcli/desktop/attachments").resolve(id);Files.createDirectories(dir);
                String extension=file.getFileName().toString().replaceAll("^.*\\.","").toLowerCase(Locale.ROOT);
                if(!List.of("png","jpg","jpeg","gif","webp").contains(extension))throw new IllegalArgumentException("不支持的图片格式");
                Path copy=dir.resolve(DesktopStore.id()+"."+extension);Files.copy(file,copy);
                try {Files.setPosixFilePermissions(copy,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));}
                catch(UnsupportedOperationException ignored){}
                var image=com.xhlcli.image.ImageReferenceParser.userMessage("@image:<"+copy+">",workspace);
                if(!image.hasImages())throw new IllegalArgumentException("图片读取失败，请检查文件内容或选择 PNG/JPEG 图片");
                imageParts.addAll(image.contentParts());
            } else {
                if(Files.size(file)>256*1024)throw new IllegalArgumentException("文本附件不能超过 256 KB，请改为在任务中引用文件路径");
                prompt.append("\n\n[用户附加文件：").append(file.getFileName()).append("]\n").append(Files.readString(file));
            }
        }
        var turn=session.withArray("turns").addObject();turn.put("id",turnId);turn.put("input",input);turn.put("model",model);
        turn.put("reasoningEffort",session.path("reasoningEffort").asText("default"));
        turn.put("status","running");turn.put("startedAt",Instant.now().toString());turn.set("attachments",attachments.deepCopy());turn.putArray("events");
        if(session.path("title").asText().equals("新聊天"))session.put("title",input.replaceAll("\\s+"," ").substring(0,Math.min(input.replaceAll("\\s+"," ").length(),40)));
        session.put("updatedAt",Instant.now().toString());
        var running=new ActiveTurn(id,turnId,workspace,turn);
        try {store.save();}catch(Exception failure){session.withArray("turns").remove(session.withArray("turns").size()-1);throw failure;}
        active.put(id,running);busy.put(workspace,id);
        var parts=new ArrayList<com.xhlcli.model.ContentPart>();parts.add(com.xhlcli.model.ContentPart.text(prompt.toString()));parts.addAll(imageParts);
        ChatMessage message=imageParts.isEmpty()?ChatMessage.user(prompt.toString()):ChatMessage.user(parts);
        workers.submit(()->run(session,running,model,message));
        return turn.deepCopy();
    }
    private void run(ObjectNode session, ActiveTurn running, String model, ChatMessage input) {
        DesktopChanges changes=new DesktopChanges(running.workspace,home);
        try {
            var resources=resources(running.workspace);
            resources.skills.scanAndReload();
            var command=DesktopCommands.parse(input.content());
            boolean useAgent=DesktopCommands.requiresAgent(command,resources);
            if(useAgent) {
                try {String before=changes.capture(running.turnId,false);synchronized(this){running.turn.put("before",before);store.save();}}
                catch(Exception failure){emit(running,"notice",Map.of("message","快照不可用："+failure.getMessage()));}
            }
            if(running.token.isCancelled())throw new CancellationException("用户取消");
            List<ChatMessage> history;ObjectNode settings;
            synchronized(this){history=DesktopJson.MAPPER.convertValue(session.withArray("history"),new TypeReference<List<ChatMessage>>(){});settings=session.deepCopy();}
            RunResult result;List<ChatMessage> nextHistory;
            if(!useAgent) {
                String answer=DesktopCommands.local(command,resources,home,running.workspace,config,settings,history,running.token,target->{
                    synchronized(this){try {
                        updateSession(DesktopJson.MAPPER.createObjectNode().put("sessionId",running.sessionId).put("model",target));
                        emit(running,"session.model",Map.of("model",target));
                    }catch(Exception failure){throw new IllegalStateException(failure.getMessage(),failure);}}
                });
                result=new RunResult(running.turnId,RunStatus.COMPLETED,answer,"",0,TokenUsage.unknown());
                nextHistory=new ArrayList<>(history);nextHistory.add(input);
                if(!answer.isBlank())nextHistory.add(ChatMessage.assistant(answer.length()>16000?answer.substring(0,16000)+"\n[指令输出已节选]":answer));
            }else try(var adapter=new DesktopAgent(running.workspace,home,model,config,new ApprovalBridge(running),(type,value)->emit(running,type,value),resources,running.turn.path("reasoningEffort").asText("default"))) {
                adapter.agent.restoreHistory(history);
                RunEventSink sink=event->emit(running,event.getClass().getSimpleName(),event);
                result=command==null?adapter.agent.runMessage(input,sink,running.token):adapter.runCommand(command,sink,running.token);
                nextHistory=adapter.agent.history();
            }
            synchronized(this) {
                session.set("history",config.redactNode(DesktopJson.MAPPER.valueToTree(nextHistory),running.workspace));
                running.turn.put("status",running.token.isCancelled()?"canceled":result.status().name().toLowerCase(Locale.ROOT));
                running.turn.put("answer",config.redact(result.finalAnswer(),running.workspace));running.turn.put("error",config.redact(result.reason(),running.workspace));
            }
        } catch(Exception failure) {
            synchronized(this){running.turn.put("status",running.token.isCancelled()?"canceled":"failed");running.turn.put("error",config.redact(Objects.toString(failure.getMessage(),failure.getClass().getSimpleName()),running.workspace));}
        } finally {
            try {if(running.turn.hasNonNull("before")){
                String after=changes.capture(running.turnId,true);
                synchronized(this){running.turn.put("after",after);running.turn.put("changeCount",changes.diff(running.turn.path("before").asText(),after).size());}
            }}
            catch(Exception failure){emit(running,"notice",Map.of("message","结束快照失败，本轮无法安全撤销"));}
            synchronized(this) {
                running.approval=null;running.turn.put("finishedAt",Instant.now().toString());active.remove(running.sessionId);busy.remove(running.workspace);
                try {store.save();}catch(Exception failure){running.turn.put("error","保存失败："+failure.getMessage());running.turn.put("status","failed");}
                var notification=DesktopJson.MAPPER.createObjectNode();notification.put("event","turn.finished");notification.put("sessionId",running.sessionId);
                notification.put("turnId",running.turnId);notification.set("data",running.turn.deepCopy());publish.accept(notification);
            }
        }
    }
    private DesktopWorkspace resources(Path workspace) {
        return workspaces.computeIfAbsent(workspace,path->new DesktopWorkspace(home,path,config));
    }
    private synchronized void emit(ActiveTurn running, String type, Object value) {
        try {
            JsonNode data=config.redactNode(DesktopJson.MAPPER.valueToTree(value),running.workspace);
            var event=running.turn.withArray("events").addObject();event.put("type",type);event.set("data",data);
            long now=System.nanoTime();if(!type.equals("TextDelta")||now-running.lastSave>250_000_000){store.save();running.lastSave=now;}
            var notification=DesktopJson.MAPPER.createObjectNode();notification.put("event",type);notification.put("sessionId",running.sessionId);
            notification.put("turnId",running.turnId);notification.set("data",data);publish.accept(notification);
        }catch(Exception failure){running.token.cancel();throw new IllegalStateException("无法保存桌面事件",failure);}
    }
    private ObjectNode cancel(String id) throws Exception {
        var turn=active.get(id);if(turn==null)return DesktopJson.MAPPER.createObjectNode().put("status","idle");
        turn.turn.put("status","canceling");turn.token.cancel();
        if(turn.approval!=null)turn.approval.complete(ApprovalResult.reject("任务已取消"));
        store.save();return DesktopJson.MAPPER.createObjectNode().put("status","canceling");
    }
    private ObjectNode approve(ObjectNode p) {
        var current=active.get(p.path("sessionId").asText());
        if(current==null||current.approval==null||current.token.isCancelled()||!Objects.equals(current.approvalId,p.path("approvalId").asText()))throw new IllegalStateException("审批已失效");
        boolean accepted=current.approval.complete(p.path("allow").asBoolean()?ApprovalResult.approve():ApprovalResult.reject("用户拒绝"));
        if(!accepted)throw new IllegalStateException("审批已处理");
        return DesktopJson.MAPPER.createObjectNode().put("ok",true);
    }
    private JsonNode changes(ObjectNode p,boolean undo) throws Exception {
        var session=store.session(p.path("sessionId").asText());ObjectNode turn=null;
        for(var item:session.withArray("turns"))if(item.path("id").asText().equals(p.path("turnId").asText()))turn=(ObjectNode)item;
        if(turn==null)throw new IllegalArgumentException("轮次不存在");
        Path workspace=Path.of(session.path("path").asText()).toRealPath();
        if(busy.containsKey(workspace))throw new IllegalStateException("请等待该目录的任务停止后查看或撤销");
        var changes=new DesktopChanges(workspace,home);
        if(undo) {
            if(turn.path("undone").asBoolean())throw new IllegalStateException("该轮已撤销");
            changes.undo(turn.path("before").asText(),turn.path("after").asText());turn.put("undone",true);store.save();
            return DesktopJson.MAPPER.createObjectNode().put("ok",true);
        }
        return DesktopJson.MAPPER.valueToTree(changes.diff(turn.path("before").asText(),turn.path("after").asText()));
    }
    private final class ApprovalBridge implements HitlHandler {
        private final ActiveTurn current;
        ApprovalBridge(ActiveTurn current){this.current=current;}
        @Override public synchronized ApprovalResult requestApproval(ApprovalRequest request) {
            var future=new CompletableFuture<ApprovalResult>();String id=DesktopStore.id();
            synchronized(DesktopService.this){
                if(current.token.isCancelled())return ApprovalResult.reject("任务已取消");
                if("auto".equals(store.session(current.sessionId).path("approvalMode").asText())) {
                    emit(current,"approval.automatic",Map.of("id",id,"request",request));
                    return ApprovalResult.approve();
                }
                current.approval=future;current.approvalId=id;current.approvalRequest=request;current.turn.put("status","approval");
                emit(current,"approval.request",Map.of("id",id,"request",request));
            }
            try {
                while(!current.token.isCancelled())try{return future.get(200,TimeUnit.MILLISECONDS);}catch(TimeoutException ignored){}
                return ApprovalResult.reject("任务已取消");
            }catch(Exception failure){return ApprovalResult.reject("审批已中断");}
            finally {synchronized(DesktopService.this){current.approval=null;current.approvalRequest=null;current.turn.put("status",current.token.isCancelled()?"canceling":"running");emit(current,"approval.resolved",Map.of("id",id));}}
        }
        @Override public boolean isEnabled(){return true;}
        @Override public void setEnabled(boolean enabled){if(!enabled)throw new IllegalArgumentException("桌面端保持审批开启");}
    }
    private static final class ActiveTurn {
        final String sessionId,turnId;final Path workspace;final ObjectNode turn;final CancellationToken token=new CancellationToken();
        CompletableFuture<ApprovalResult> approval;ApprovalRequest approvalRequest;String approvalId;long lastSave;
        ActiveTurn(String sessionId,String turnId,Path workspace,ObjectNode turn){this.sessionId=sessionId;this.turnId=turnId;this.workspace=workspace;this.turn=turn;}
    }
    @Override public void close() throws Exception {
        synchronized(this){closing=true;for(var turn:active.values()){turn.token.cancel();if(turn.approval!=null)turn.approval.complete(ApprovalResult.reject("应用退出"));}}
        schedules.close();
        workers.shutdown();
        if(!workers.awaitTermination(8,TimeUnit.SECONDS)) {
            workers.shutdownNow();
            if(!workers.awaitTermination(1,TimeUnit.SECONDS))throw new IllegalStateException("执行线程尚未退出，保留状态锁直至进程结束");
        }
        for(var resources:workspaces.values())resources.close();
        synchronized(this){store.save();store.close();}
    }
}
