package com.xhlcli.desktop;

import com.xhlcli.agent.*;
import com.xhlcli.config.*;
import com.xhlcli.context.*;
import com.xhlcli.hitl.*;
import com.xhlcli.llm.*;
import com.xhlcli.memory.*;
import com.xhlcli.model.*;
import com.xhlcli.policy.*;
import com.xhlcli.prompt.*;
import com.xhlcli.skill.*;
import com.xhlcli.skill.builtin.BuiltinSkills;
import com.xhlcli.skill.tool.LoadSkillTool;
import com.xhlcli.tool.*;
import com.xhlcli.tool.demo.*;
import com.xhlcli.tool.local.*;
import com.xhlcli.tool.local.search.SearchCodeTool;
import com.xhlcli.tool.local.search.GrepCodeTool;
import com.xhlcli.web.tool.*;
import com.xhlcli.mcp.config.McpConfigLoader;
import com.xhlcli.mcp.manager.McpServerManager;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.BiConsumer;

/** 无终端依赖的装配适配；执行仍由原有 Agent 和工具实现完成。 */
final class DesktopAgent implements AutoCloseable {
    final ReactAgent agent;
    final LlmClient client;
    private final ScheduledTimeoutScheduler scheduler = new ScheduledTimeoutScheduler();
    private final DesktopWorkspace resources;
    private final ToolRegistry registry;
    private final ToolExecutor executor;
    private final PlanExecuteAgent.PlanReviewHandler planReview;
    private final MemoryManager memory;
    private final Path workspace;
    private final BiConsumer<String,Object> detail;
    DesktopAgent(Path workspace, Path home, String model, DesktopConfig config, HitlHandler approvals,
                 BiConsumer<String,Object> detail,DesktopWorkspace resources,String reasoningEffort,
                 PlanExecuteAgent.PlanReviewHandler planReview) throws Exception {
        this.resources=resources;this.workspace=workspace;this.planReview=Objects.requireNonNull(planReview);this.detail=detail;
        client=config.client(workspace,model,reasoningEffort);
        try {
        var mapper=DesktopJson.MAPPER; var paths=new WorkspacePathResolver(workspace);
        var skills=resources.skills;
        skills.scanAndReload();
        registry=new ToolRegistry(List.of(new ListDirTool(paths),new ReadFileTool(paths),new WriteFileTool(paths),
                new ApplyPatchTool(paths),new GitDiffTool(paths),new ExecuteCommandTool(paths),new GlobFilesTool(paths),
                new GrepCodeTool(paths),new SearchCodeTool(paths),new EchoTool(),new CurrentTimeTool(Clock.systemUTC()),
                new WebSearchTool(),new WebFetchTool(),new LoadSkillTool(skills)));
        var delegate=new DefaultToolExecutor(registry,new ToolSchemaValidator(mapper),new ToolResultBudget(ToolResultBudget.DEFAULT_MAX_CHARS,mapper),
                mapper,System::nanoTime,new PathGuard(workspace),approvals,new AuditLog(home.resolve(".xhlcli/audit")));
        executor=(call,token)->{
            detail.accept("tool.call",call);
            var result=delegate.execute(call,token);
            detail.accept("tool.result",result);
            return result;
        };
        registry.updateDynamicTools(resources.mcp().getAllTools());
        var prompts=new LayeredPromptAssembler();
        prompts.registerBlock(PromptLayer.RUNTIME_CONTEXT,PromptSource.BUILTIN,"## 桌面运行环境",
                "工作区："+workspace+"\n模型："+client.modelName()+"\n日期："+LocalDate.now()+"\n用户通过桌面界面工作；工具审批会在界面显示。默认用简体中文回答。");
        prompts.registerBlock(PromptLayer.SKILL_INDEX,PromptSource.BUILTIN,"## 可用技能",skills.generateIndexPrompt(2500));
        for(String file:List.of("AGENTS.md",".xhlcli/rules.md")) if(Files.isRegularFile(workspace.resolve(file))) {
            Path safe=paths.resolveSafe(file);
            prompts.registerBlock(PromptLayer.PROJECT_RULES_AND_MEMORY,PromptSource.PROJECT,"## 项目规则 "+file,Files.readString(safe));
        }
        var limits=config.limits(workspace);
        agent=new ReactAgent(ChatMessage.system(prompts.assembleSystemPrompt()),client,executor,registry.definitions(),
                new RunLimits(limits.agentSettings().maxIterations(),limits.agentSettings().timeout()),scheduler,mapper,Clock.systemUTC(),DesktopStore::id,
                text->config.redact(text,workspace),
                ()->new StreamingSecretRedactor(config.environmentKey(workspace,client.providerName())));
        agent.setProjectDirectory(workspace);
        agent.setContextAssembler(new ContextAssembler(new TokenBudget(client.capabilities().maxContextWindow())));
        agent.setCompactor(new ConversationHistoryCompactor(client));
        memory=new MemoryManager(home.resolve(".xhlcli/memory"),workspace.resolve(".xhlcli/memory"));
        agent.setMemoryRecall(memory,MemoryRecallSettings.fromEnvironment(config.environment(workspace)));
        agent.setConcurrencyLimits(limits.agentSettings().maxConcurrency(),limits.agentSettings().toolTimeout());
        } catch(Exception failure) { close(); throw failure; }
    }
    RunResult runCommand(DesktopCommands.Parsed command,RunEventSink events,CancellationToken token) throws Exception {
        String name=command.name(),goal=command.arguments();
        if(!name.equals("/plan")&&!name.equals("/team")) {
            String skillName=name.substring(1),task=goal;
            if(name.equals("/skill")) {
                String[] parts=goal.split("\\s+",3);
                if(parts.length<3)throw new IllegalArgumentException("用法：/skill run <名称> <任务描述>");
                skillName=parts[1];task=parts[2];
            }
            var loaded=new LoadSkillTool(resources.skills).execute(DesktopJson.MAPPER.createObjectNode().put("name",skillName),token);
            if(!"loaded".equals(loaded.data().path("status").asText()))throw new IllegalArgumentException("技能不可用："+skillName+"。请检查名称、启用状态和技能配置。");
            detail.accept("notice",Map.of("message","已加载技能："+skillName));
            return agent.runMessage(ChatMessage.user("请使用以下已加载技能完成任务。\n\n"+loaded.summary()+"\n\n用户任务：\n"+task),events,token);
        }
        RunResult result;
        try(var output=new java.io.PrintStream(new java.io.OutputStream(){
            private final java.io.ByteArrayOutputStream line=new java.io.ByteArrayOutputStream();
            @Override public synchronized void write(int value){if(value=='\n'){flush();}else line.write(value);}
            @Override public synchronized void flush(){if(line.size()>0){String text=line.toString(java.nio.charset.StandardCharsets.UTF_8);line.reset();detail.accept("command.progress",Map.of("text",text));}}
        },true,java.nio.charset.StandardCharsets.UTF_8)) {
            String context=com.xhlcli.team.TeamRouter.context(agent.history(),workspace);
            if(name.equals("/plan")) {
                var planner=new com.xhlcli.plan.Planner(client,output);
                planner.setProjectMemorySupplier(()->context);
                var plan=new PlanExecuteAgent(client,executor,registry.definitions(),planner,null,planReview,output);
                // 桌面按顺序执行计划步骤，保持审批与工具记录可追溯。
                plan.setMaxConcurrency(1);
                result=plan.run(goal,events,token);
            }else {
                var team=new com.xhlcli.team.TeamOrchestrator(client,executor,registry.definitions(),memory,output);
                team.setProjectDirectory(workspace);
                result=team.runWithContext(goal,context,events,token);
            }
        }
        var history=new ArrayList<>(agent.history());history.add(ChatMessage.user(name+" "+goal));
        if(!result.finalAnswer().isBlank())history.add(ChatMessage.assistant(result.finalAnswer()));
        agent.restoreHistory(history);
        return result;
    }
    @Override public void close() {
        try {scheduler.close();}finally {client.close();}
    }
}
