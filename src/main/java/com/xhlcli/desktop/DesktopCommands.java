package com.xhlcli.desktop;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhlcli.context.TokenBudget;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.memory.*;
import com.xhlcli.model.ChatMessage;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/** 桌面命令目录和本地管理操作；模型执行由 DesktopAgent 承接。 */
final class DesktopCommands {
    record Entry(String command,String description,String usage,boolean requiresModel) {}
    record Parsed(String name,String arguments) {}
    private static final List<Entry> ENTRIES=List.of(
        new Entry("/help","查看桌面命令","/help",false),
        new Entry("/plan","生成计划，确认后执行","/plan <任务目标>",true),
        new Entry("/team","启动规划、执行与审查团队","/team <任务目标>",true),
        new Entry("/mcp","查看和管理项目 MCP 服务","/mcp [list|status|tools|resources|read|start|stop|restart|logs|reload]",false),
        new Entry("/skill","查看、启停或运行技能","/skill [list|show|enable|disable|reload|run]",false),
        new Entry("/memory","查看、搜索和管理长期记忆","/memory [list|search|recall|delete|clear]",false),
        new Entry("/save","保存长期记忆","/save [--global] <记忆内容>",false),
        new Entry("/model","查看或切换当前聊天的模型","/model [list|status|use <模型标识>]",false),
        new Entry("/context","查看当前聊天的上下文信息","/context",false)
    );
    static Parsed parse(String input) {
        if(!input.stripLeading().startsWith("/"))return null;
        String[] parts=input.strip().split("\\s+",2);
        return new Parsed(parts[0].toLowerCase(Locale.ROOT),parts.length>1?parts[1]:"");
    }
    static List<Entry> catalog(DesktopWorkspace resources) {
        var result=new ArrayList<>(ENTRIES);
        for(String sub:List.of("list","status","tools","resources","read","start","stop","restart","logs","reload"))
            result.add(new Entry("/mcp "+sub,"MCP · "+switch(sub){case "list"->"列出服务";case "status"->"服务详情";case "tools"->"可用工具";case "resources"->"可用资源";case "read"->"读取资源";case "start"->"启动服务";case "stop"->"停止服务";case "restart"->"重启服务";case "logs"->"服务日志";default->"重新加载配置";},"/mcp "+sub,false));
        for(String sub:List.of("list","show","enable","disable","reload","run"))result.add(new Entry("/skill "+sub,"技能 · "+switch(sub){case "list"->"列出技能";case "show"->"查看正文";case "enable"->"启用技能";case "disable"->"禁用技能";case "run"->"运行技能";default->"重新加载";},"/skill "+sub,sub.equals("run")));
        for(String sub:List.of("list","search","recall","delete","clear"))result.add(new Entry("/memory "+sub,"记忆 · "+switch(sub){case "list"->"列出记忆";case "search"->"搜索内容";case "recall"->"预览召回";case "delete"->"按 ID 删除";default->"清空记忆";},"/memory "+sub,false));
        for(String sub:List.of("list","status","use"))result.add(new Entry("/model "+sub,"模型 · "+switch(sub){case "list"->"列出已配置模型";case "status"->"当前模型";default->"切换模型";},"/model "+sub,false));
        if(resources!=null)for(var skill:resources.skillView().listAll()) {
            String name="/"+skill.name().toLowerCase(Locale.ROOT);
            if(skill.isEnabled()&&ENTRIES.stream().noneMatch(item->item.command().equals(name)))result.add(new Entry(name,skill.description(),name+" <任务描述>",true));
        }
        return result;
    }
    static boolean requiresAgent(Parsed command,DesktopWorkspace resources) {
        if(command==null)return true;
        if(command.name().equals("/plan")||command.name().equals("/team"))return !command.arguments().isBlank();
        if(command.name().equals("/skill")&&command.arguments().matches("(?is)run\\s+.+"))return true;
        return ENTRIES.stream().noneMatch(item->item.command().equals(command.name()))&&resources.skills.find(command.name().substring(1)).isPresent()&&!command.arguments().isBlank();
    }
    static String local(Parsed command,DesktopWorkspace resources,Path home,Path workspace,DesktopConfig config,
                        ObjectNode session,List<ChatMessage> history,CancellationToken token,java.util.function.Consumer<String> selectModel) throws Exception {
        if(token.isCancelled())throw new java.util.concurrent.CancellationException("任务已停止");
        String arg=command.arguments();String[] parts=arg.split("\\s+",2);String sub=parts[0].toLowerCase(Locale.ROOT),rest=parts.length>1?parts[1]:"";
        var memory=new MemoryManager(home.resolve(".xhlcli/memory"),workspace.resolve(".xhlcli/memory"));
        return switch(command.name()) {
            case "/help" -> "可用桌面命令：\n\n"+ENTRIES.stream().map(e->"- `"+e.usage()+"`："+e.description()).collect(Collectors.joining("\n"))+"\n\n输入 `/技能名 任务描述` 可直接调用已启用技能。MCP 启停在本次应用运行期间对同一项目生效；技能开关沿用桌面技能设置。";
            case "/plan","/team" -> "用法："+command.name()+" <任务目标>";
            case "/mcp" -> mcp(resources,sub,rest,token);
            case "/skill" -> skill(resources,sub,rest);
            case "/save" -> {
                if(arg.isBlank())yield "用法：/save [--global] <记忆内容>";
                boolean global=arg.equals("--global")||arg.startsWith("--global ");
                String content=global?arg.substring(8).strip():arg;
                if(content.isBlank())throw new IllegalArgumentException("请填写要保存的记忆内容");
                if(global)memory.saveGlobal(content,"user");else memory.saveProject(content,"user");
                yield "已保存"+(global?"全局":"项目")+"长期记忆："+content;
            }
            case "/memory" -> switch(sub) {
                case "","list" -> memories(memory.loadAll());
                case "search" -> memories(memory.search(required(rest,"/memory search <关键词>")));
                case "recall" -> {
                    var recalled=memory.recall(MemoryRecallSettings.fromEnvironment(config.environment(workspace)).query(required(rest,"/memory recall <问题>"),List.of()));
                    if(recalled.readFailed())throw new IllegalStateException("记忆读取失败，请检查记忆文件");
                    yield recalled.selected().isEmpty()?"没有匹配的长期记忆。":recalled.selected().stream().map(m->"- ["+m.entry().id()+"] "+m.excerpt()).collect(Collectors.joining("\n"));
                }
                case "delete" -> memory.delete(required(rest,"/memory delete <id>"))?"已删除指定记忆。":"没有找到该记忆。";
                case "clear" -> {memory.clearAll();yield "已清空全局和当前项目的长期记忆；聊天历史保留。";}
                default -> throw new IllegalArgumentException("用法：/memory [list|search <关键词>|recall <问题>|delete <id>|clear]");
            };
            case "/model" -> {
                var models=config.describe(workspace).path("models");
                if(sub.equals("use")) {
                    required(rest,"/model use <模型标识>");String target=null;
                    for(var model:models)if(rest.equals(model.path("id").asText())||rest.equals(model.path("modelName").asText())){target=model.path("id").asText();break;}
                    if(target==null)throw new IllegalArgumentException("未找到已配置的模型，请用 /model list 查看或进入设置配置");
                    selectModel.accept(target);yield "当前聊天已切换到 `"+target+"`，下一轮生效。";
                }
                if(sub.isBlank()||sub.equals("status"))yield "当前聊天模型：`"+session.path("model").asText("未配置")+"`。使用 /model list 查看已配置模型。";
                if(!sub.equals("list"))throw new IllegalArgumentException("用法：/model [list|status|use <模型标识>]");
                var lines=new ArrayList<String>();for(var model:models)lines.add("- `"+model.path("id").asText()+"`");
                yield lines.isEmpty()?"尚未配置模型，请进入设置。":String.join("\n",lines);
            }
            case "/context" -> "当前聊天上下文：\n\n- 历史消息："+history.size()+" 条\n- 估算 Token："+TokenBudget.estimateTokens(history)+"\n- 当前模型：`"+session.path("model").asText()+"`\n- 工作目录：`"+workspace+"`\n\n会话自动保存在用户目录，更新应用后可继续对话。";
            default -> {
                if(resources.skills.find(command.name().substring(1)).isPresent())yield "用法："+command.name()+" <任务描述>";
                throw new IllegalArgumentException("未知或尚未接入桌面的指令："+command.name()+"。输入 /help 查看可用命令。");
            }
        };
    }
    private static String memories(List<MemoryEntry> entries) {
        return entries.isEmpty()?"没有匹配的长期记忆。":entries.stream().map(e->"- ["+e.id()+"] ["+e.scope()+"] "+e.content()).collect(Collectors.joining("\n"));
    }
    static String required(String value,String usage) {
        if(value.isBlank())throw new IllegalArgumentException("用法："+usage);return value;
    }
    private static String skill(DesktopWorkspace resources,String sub,String name) {
        var registry=resources.skills;
        if(sub.isBlank()||sub.equals("list"))return registry.listAll().stream().map(s->"- `"+s.name()+"` ["+(s.isEnabled()?"启用":s.isHealthy()?"禁用":"异常")+"]："+s.description()).collect(Collectors.joining("\n"));
        if(sub.equals("reload")){registry.scanAndReload();return "已重新加载技能。";}
        if(!List.of("show","enable","disable","run").contains(sub))throw new IllegalArgumentException("用法：/skill [list|show <名称>|enable <名称>|disable <名称>|reload|run <名称> <任务>]");
        if(sub.equals("run"))return "用法：/skill run <名称> <任务描述>";
        required(name,"/skill "+sub+" <名称>");var skill=registry.find(name).orElseThrow(()->new IllegalArgumentException("没有找到技能："+name));
        return switch(sub) {
            case "show" -> "技能："+skill.name()+"\n\n"+(skill.isHealthy()?skill.instructions():skill.parseError());
            case "enable" -> {if(!registry.enable(name))throw new IllegalArgumentException("技能配置损坏，无法启用");yield "已启用技能："+name;}
            default -> {registry.disable(name);yield "已禁用技能："+name;}
        };
    }
    private static String mcp(DesktopWorkspace resources,String sub,String name,CancellationToken token) throws Exception {
        if(sub.equals("reload")){resources.reloadMcp();return "已重新加载 MCP 配置。";}
        var manager=resources.mcp();
        if(sub.isBlank()||sub.equals("list"))return manager.listServers().isEmpty()?"未配置 MCP 服务。请在 ~/.xhlcli/mcp.json 或项目 .xhlcli/mcp.json 配置后运行 /mcp reload。":manager.listServers().entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e->"- `"+e.getKey()+"`："+e.getValue().status()+" · "+e.getValue().tools().size()+" 个工具").collect(Collectors.joining("\n"));
        if(sub.equals("tools"))return manager.getAllTools().isEmpty()?"当前没有可用的 MCP 工具。":manager.getAllTools().stream().map(t->"- `"+t.definition().name()+"`："+t.definition().description()).collect(Collectors.joining("\n"));
        if(sub.equals("resources"))return manager.getAllResources().isEmpty()?"当前没有可用的 MCP 资源。":manager.getAllResources().stream().map(r->"- `"+r.uri()+"`："+r.name()).collect(Collectors.joining("\n"));
        if(sub.equals("read"))return manager.readResource(required(name,"/mcp read <资源 URI>"),token,Duration.ofSeconds(10)).stream().map(c->c.text()!=null?c.text():"[二进制资源："+c.mimeType()+"]").collect(Collectors.joining("\n"));
        if(!List.of("status","logs","start","stop","restart").contains(sub))throw new IllegalArgumentException("未知 MCP 子命令，输入 /help 查看用法");
        required(name,"/mcp "+sub+" <服务名称>");var entry=manager.getServer(name);
        if(entry==null)throw new IllegalArgumentException("没有找到 MCP 服务："+name);
        switch(sub) {
            case "start" -> manager.startServer(name,Duration.ofSeconds(5));
            case "stop" -> manager.stopServer(name);
            case "restart" -> manager.restartServer(name,Duration.ofSeconds(5));
            case "logs" -> {return entry.getRecentLogs().isEmpty()?"当前没有服务日志。":String.join("\n",entry.getRecentLogs());}
        }
        return "MCP 服务 `"+name+"`："+entry.status()+"\n\n工具："+entry.tools().size()+"，资源："+entry.resources().size()+(entry.errorMessage()==null?"":"\n\n错误："+entry.errorMessage());
    }
}
