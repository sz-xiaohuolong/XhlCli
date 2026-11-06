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
    private McpServerManager mcp;
    DesktopAgent(Path workspace, Path home, String model, DesktopConfig config, HitlHandler approvals,
                 BiConsumer<String,Object> detail) throws Exception {
        client=config.client(workspace,model);
        try {
        var mapper=DesktopJson.MAPPER; var paths=new WorkspacePathResolver(workspace);
        var skills=new SkillRegistry(home.resolve(".xhlcli/skills"),workspace.resolve(".xhlcli/skills"),BuiltinSkills.all());
        skills.scanAndReload();
        var registry=new ToolRegistry(List.of(new ListDirTool(paths),new ReadFileTool(paths),new WriteFileTool(paths),
                new ApplyPatchTool(paths),new GitDiffTool(paths),new ExecuteCommandTool(paths),new GlobFilesTool(paths),
                new GrepCodeTool(paths),new SearchCodeTool(paths),new EchoTool(),new CurrentTimeTool(Clock.systemUTC()),
                new WebSearchTool(),new WebFetchTool(),new LoadSkillTool(skills)));
        var delegate=new DefaultToolExecutor(registry,new ToolSchemaValidator(mapper),new ToolResultBudget(ToolResultBudget.DEFAULT_MAX_CHARS,mapper),
                mapper,System::nanoTime,new PathGuard(workspace),approvals,new AuditLog(home.resolve(".xhlcli/audit")));
        ToolExecutor executor=(call,token)->{
            detail.accept("tool.call",call);
            var result=delegate.execute(call,token);
            detail.accept("tool.result",result);
            return result;
        };
        mcp=new McpServerManager(registry,workspace,mapper);
        mcp.registerServers(new McpConfigLoader(mapper).load(home.resolve(".xhlcli/mcp.json"),workspace.resolve(".xhlcli/mcp.json"),config.environment(workspace)));
        mcp.startAll(Duration.ofMillis(3000));
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
        agent.setMemoryRecall(new MemoryManager(home.resolve(".xhlcli/memory"),workspace.resolve(".xhlcli/memory")),MemoryRecallSettings.fromEnvironment(config.environment(workspace)));
        agent.setConcurrencyLimits(limits.agentSettings().maxConcurrency(),limits.agentSettings().toolTimeout());
        } catch(Exception failure) { close(); throw failure; }
    }
    @Override public void close() {
        try {if(mcp!=null)mcp.close();}finally {try {scheduler.close();}finally {client.close();}}
    }
}
