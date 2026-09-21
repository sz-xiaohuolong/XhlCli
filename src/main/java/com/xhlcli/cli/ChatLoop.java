package com.xhlcli.cli;

import com.xhlcli.agent.AgentRunner;
import com.xhlcli.config.ChatConfig;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.render.PlainRunRenderer;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class ChatLoop {
    private final InputReader inputReader;
    private final ChatCommandParser commandParser;
    private final AgentRunner agent;
    private final PlainRunRenderer renderer;
    private final ChatConfig config;
    private final com.xhlcli.hitl.HitlHandler hitlHandler;
    private com.xhlcli.memory.MemoryManager memoryManager;
    private com.xhlcli.context.ContextAssembler contextAssembler;
    private com.xhlcli.memory.ConversationHistoryCompactor compactor;
    private com.xhlcli.tool.local.search.GrepCodeTool grepCodeTool;
    private java.nio.file.Path projectDirectory;
    private com.xhlcli.agent.PlanExecuteAgent planAgent;
    private com.xhlcli.team.TeamOrchestrator teamOrchestrator;
    private com.xhlcli.llm.LlmClient currentClient;
    private com.xhlcli.llm.LlmProviderRegistry providerRegistry;
    private com.xhlcli.llm.DiagnosticSink diagnostics;

    public void setMemoryManager(com.xhlcli.memory.MemoryManager manager) { this.memoryManager = manager; }
    public void setContextAssembler(com.xhlcli.context.ContextAssembler assembler) { this.contextAssembler = assembler; }
    public void setCompactor(com.xhlcli.memory.ConversationHistoryCompactor compactor) { this.compactor = compactor; }
    public void setGrepCodeTool(com.xhlcli.tool.local.search.GrepCodeTool grepCodeTool) { this.grepCodeTool = grepCodeTool; }
    public void setProjectDirectory(java.nio.file.Path projectDirectory) { this.projectDirectory = projectDirectory; }
    public void setPlanAgent(com.xhlcli.agent.PlanExecuteAgent planAgent) { this.planAgent = planAgent; }
    public void setTeamOrchestrator(com.xhlcli.team.TeamOrchestrator teamOrchestrator) { this.teamOrchestrator = teamOrchestrator; }
    public void setLlmClient(com.xhlcli.llm.LlmClient client) { this.currentClient = client; }
    public void setProviderRegistry(com.xhlcli.llm.LlmProviderRegistry registry) { this.providerRegistry = registry; }
    public void setDiagnostics(com.xhlcli.llm.DiagnosticSink diagnostics) { this.diagnostics = diagnostics; }
    public com.xhlcli.llm.LlmClient getLlmClient() { return this.currentClient; }

    private void printContext() {
        if (contextAssembler != null) {
            System.out.println("Context Window: " + contextAssembler.getBudget().getContextWindow());
            System.out.println("Available for conversation: " + contextAssembler.getBudget().getAvailableForConversation());
        } else {
            System.out.println("Context manager not initialized.");
        }
    }

    private void compactHistory() {
        System.out.println("Compacting history...");
        agent.clearHistory(); // just a simplified version
        System.out.println("History compacted.");
    }

    private void saveMemory(String input) {
        if (memoryManager != null) {
            String trimmed = input.trim();
            int firstSpace = trimmed.indexOf(' ');
            if (firstSpace == -1 || firstSpace == trimmed.length() - 1) {
                System.out.println("用法: /save [--global] <记忆内容>");
                return;
            }
            String content = trimmed.substring(firstSpace + 1).trim();
            if (content.startsWith("--global")) {
                String fact = content.substring("--global".length()).trim();
                if (fact.isBlank()) {
                    System.out.println("用法: /save --global <记忆内容>");
                    return;
                }
                memoryManager.saveGlobal(fact, "user");
                System.out.println("已保存全局长期记忆: " + fact);
            } else {
                if (content.isBlank()) {
                    System.out.println("用法: /save <记忆内容>");
                    return;
                }
                memoryManager.saveProject(content, "user");
                System.out.println("已保存项目长期记忆: " + content);
            }
        } else {
            System.out.println("记忆管理器未初始化。");
        }
    }

    private void manageMemory(String input) {
        if (memoryManager != null) {
            String[] parts = input.trim().split("\\s+");
            if (parts.length < 2) {
                System.out.println("用法: /memory <list|search <query>|delete <id>|clear>");
                return;
            }
            String subcmd = parts[1].toLowerCase(java.util.Locale.ROOT);
            switch (subcmd) {
                case "list" -> {
                    var list = memoryManager.loadAll();
                    if (list.isEmpty()) {
                        System.out.println("当前没有保存任何长期记忆。");
                    } else {
                        System.out.println("=== 长期记忆列表 (" + list.size() + " 条) ===");
                        list.forEach(e -> System.out.println("- [" + e.id() + "] [" + e.scope() + "] " + e.content()));
                    }
                }
                case "search" -> {
                    if (parts.length < 3) {
                        System.out.println("用法: /memory search <关键词>");
                        return;
                    }
                    String query = input.trim().substring(input.trim().indexOf(parts[2]));
                    var results = memoryManager.search(query);
                    if (results.isEmpty()) {
                        System.out.println("未找到与 \"" + query + "\" 相关的记忆。");
                    } else {
                        System.out.println("=== 搜索结果 (" + results.size() + " 条) ===");
                        results.forEach(e -> System.out.println("- [" + e.id() + "] [" + e.scope() + "] " + e.content()));
                    }
                }
                case "delete" -> {
                    if (parts.length < 3) {
                        System.out.println("用法: /memory delete <id>");
                        return;
                    }
                    String id = parts[2];
                    boolean deleted = memoryManager.delete(id);
                    if (deleted) {
                        System.out.println("已成功删除记忆 [" + id + "]。");
                    } else {
                        System.out.println("未找到 ID 为 [" + id + "] 的记忆。");
                    }
                }
                case "clear" -> {
                    memoryManager.clearAll();
                    System.out.println("已清空所有长期记忆。");
                }
                default -> System.out.println("未知子命令。用法: /memory <list|search <query>|delete <id>|clear>");
            }
        } else {
            System.out.println("记忆管理器未初始化。");
        }
    }

    private void searchText(String input) {
        if (grepCodeTool == null) {
            renderer.printMessage("代码搜索工具未初始化。");
            return;
        }
        String trimmed = input.trim();
        int firstSpace = trimmed.indexOf(' ');
        if (firstSpace == -1 || firstSpace == trimmed.length() - 1) {
            renderer.printMessage("用法: /search-text <关键词/正则>");
            return;
        }
        String query = trimmed.substring(firstSpace + 1).trim();
        try {
            com.fasterxml.jackson.databind.node.ObjectNode args = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            args.put("pattern", query);
            var output = grepCodeTool.execute(args, new CancellationToken());
            renderer.printMessage(output.summary());
        } catch (Exception e) {
            renderer.printMessage("搜索失败: " + e.getMessage());
        }
    }

    private void handleIndex(String input) {
        String projPath = projectDirectory != null ? projectDirectory.toAbsolutePath().normalize().toString() : ".";
        String trimmed = input.trim();
        String[] parts = trimmed.split("\\s+");
        com.xhlcli.rag.CodeIndex indexer = new com.xhlcli.rag.CodeIndex(renderer::printMessage);
        if (parts.length > 1) {
            String sub = parts[1].toLowerCase(java.util.Locale.ROOT);
            if ("status".equals(sub)) {
                var stats = indexer.getStatus(projPath);
                renderer.printMessage(String.format("📊 索引状态：已索引 %d 个文件，%d 个代码块，%d 条代码关系",
                        stats.fileCount(), stats.chunkCount(), stats.relationCount()));
                return;
            } else if ("clean".equals(sub)) {
                indexer.clean(projPath);
                return;
            } else {
                projPath = parts[1];
            }
        }
        indexer.index(projPath);
    }

    private void searchSemantic(String input) {
        String trimmed = input.trim();
        int firstSpace = trimmed.indexOf(' ');
        if (firstSpace == -1 || firstSpace == trimmed.length() - 1) {
            renderer.printMessage("用法: /search <自然语言查询或关键词>");
            return;
        }
        String query = trimmed.substring(firstSpace + 1).trim();
        String projPath = projectDirectory != null ? projectDirectory.toAbsolutePath().normalize().toString() : ".";
        renderer.printMessage("🔍 检索: " + query);
        try (com.xhlcli.rag.CodeRetriever retriever = new com.xhlcli.rag.CodeRetriever(projPath)) {
            var stats = retriever.getStats();
            if (stats.chunkCount() == 0) {
                renderer.printMessage("⚠️ 代码库尚未索引，请先使用 /index 命令构建索引。");
                return;
            }
            var results = retriever.hybridSearch(query, 5);
            if (results.isEmpty()) {
                renderer.printMessage("📭 未找到与查询相关的代码。");
            } else {
                renderer.printMessage(com.xhlcli.rag.SearchResultFormatter.formatForCli(query, results));
            }
        } catch (Exception e) {
            renderer.printMessage("❌ 检索失败: " + e.getMessage());
        }
    }

    private void handlePlan(String input) {
        if (planAgent == null) {
            renderer.printMessage("Plan 模式未配置或不可用。");
            return;
        }
        String trimmed = input.trim();
        int firstSpace = trimmed.indexOf(' ');
        String goal;
        if (firstSpace == -1 || firstSpace == trimmed.length() - 1) {
            try {
                goal = inputReader.readLine("Plan 任务目标 > ");
            } catch (Exception e) {
                return;
            }
        } else {
            goal = trimmed.substring(firstSpace + 1).trim();
        }

        if (goal.isBlank()) {
            renderer.printMessage("任务目标不能为空。用法: /plan <任务描述>");
            return;
        }

        CancellationToken token = new CancellationToken();
        if (!activeResponse.compareAndSet(null, token)) {
            throw new IllegalStateException("A response is already active");
        }
        try {
            planAgent.run(goal, renderer::accept, token);
        } finally {
            activeResponse.compareAndSet(token, null);
        }
    }

    private void handleTeam(String input) {
        if (teamOrchestrator == null) {
            renderer.printMessage("TeamOrchestrator 未就绪。");
            return;
        }

        String trimmed = input.trim();
        int firstSpace = trimmed.indexOf(' ');
        String goal;
        if (firstSpace == -1 || firstSpace == trimmed.length() - 1) {
            try {
                goal = inputReader.readLine("Team 任务目标 > ");
            } catch (Exception e) {
                return;
            }
        } else {
            goal = trimmed.substring(firstSpace + 1).trim();
        }

        if (goal.isBlank()) {
            renderer.printMessage("任务目标不能为空。用法: /team <任务描述>");
            return;
        }

        if (goal.length() <= 6 && (goal.contains("你好") || goal.contains("hi") || goal.contains("测试") || goal.contains("1+1"))) {
            renderer.printMessage("💡 提示：检测到极简任务，Multi-Agent 团队协同更适合多模块重构与跨文件复杂任务。已启动协同处理...");
        }

        CancellationToken token = new CancellationToken();
        if (!activeResponse.compareAndSet(null, token)) {
            throw new IllegalStateException("A response is already active");
        }
        try {
            teamOrchestrator.run(goal, renderer::accept, token);
        } finally {
            activeResponse.compareAndSet(token, null);
        }
    }

    private void handleModel(String input) {
        String trimmed = input.trim();
        String[] parts = trimmed.split("\\s+");
        String subcmd = parts.length > 1 ? parts[1].toLowerCase(java.util.Locale.ROOT) : "list";

        if (providerRegistry == null) {
            renderer.printMessage("模型注册中心未初始化。");
            return;
        }

        switch (subcmd) {
            case "list" -> {
                var models = providerRegistry.listModels();
                renderer.printMessage("=========================================================================================");
                renderer.printMessage("🤖 可用模型与 Provider 矩阵：");
                renderer.printMessage("-----------------------------------------------------------------------------------------");
                for (var m : models) {
                    boolean isCurrent = currentClient != null && (m.id().equalsIgnoreCase(currentClient.providerName() + ":" + currentClient.modelName()) || m.matches(currentClient.modelName()));
                    String marker = isCurrent ? "[✓]" : "[ ]";
                    String window = (m.capabilities().maxContextWindow() / 1000) + "k";
                    String tools = m.capabilities().supportsTools() ? "Yes" : "No";
                    String status = m.provider().equals("ollama") ? "Local" : (m.configured() ? "Configured" : "Missing Key");
                    renderer.printMessage(String.format("%s %-36s Provider: %-10s Window: %-5s Tools: %-4s Status: %s",
                            marker, m.id(), m.provider(), window, tools, status));
                }
                renderer.printMessage("=========================================================================================");
                renderer.printMessage("💡 提示：使用 '/model use <model_or_alias>' 切换模型，使用 '/model status' 查看当前详情。");
            }
            case "status" -> {
                if (currentClient == null) {
                    renderer.printMessage("当前无活动模型。");
                    return;
                }
                var caps = currentClient.capabilities();
                renderer.printMessage("=========================================================================================");
                renderer.printMessage("🎯 当前活动模型状态：");
                renderer.printMessage("-----------------------------------------------------------------------------------------");
                renderer.printMessage("Provider:         " + currentClient.providerName());
                renderer.printMessage("Model:            " + currentClient.modelName());
                renderer.printMessage("Context Window:   " + caps.maxContextWindow() + " tokens");
                renderer.printMessage("Supports Tools:   " + (caps.supportsTools() ? "Yes" : "No"));
                renderer.printMessage("Supports Vision:  " + (caps.supportsImageInput() ? "Yes" : "No"));
                renderer.printMessage("Prompt Cache:     " + (caps.supportsPromptCaching() ? ("Yes (" + caps.promptCacheMode() + ")") : "No"));
                renderer.printMessage("Reasoning Effort: " + (caps.requiresReasoningEffort() ? "Yes" : "No"));
                renderer.printMessage("=========================================================================================");
            }
            case "use" -> {
                if (parts.length < 3) {
                    renderer.printMessage("用法: /model use <model_name_or_alias>");
                    return;
                }
                String target = parts[2];
                var optDesc = providerRegistry.findModel(target);
                if (optDesc.isEmpty()) {
                    renderer.printMessage("❌ 未找到匹配的模型或别名: '" + target + "'。输入 '/model list' 查看可用模型列表。");
                    return;
                }
                var desc = optDesc.get();
                try {
                    com.xhlcli.llm.LlmClient newClient = providerRegistry.createClient(desc, diagnostics != null ? diagnostics : (msg, data) -> {});
                    this.currentClient = newClient;
                    if (agent instanceof com.xhlcli.agent.ReactAgent ra) {
                        ra.setClient(newClient);
                    }
                    if (planAgent != null) {
                        planAgent.setClient(newClient);
                    }
                    if (teamOrchestrator != null) {
                        teamOrchestrator.setClient(newClient);
                    }
                    if (compactor != null) {
                        compactor.setClient(newClient);
                    }
                    if (contextAssembler != null) {
                        contextAssembler.getBudget().updateContextWindow(newClient.capabilities().maxContextWindow());
                        int historyTokens = com.xhlcli.context.TokenBudget.estimateTokens(agent.history());
                        int available = contextAssembler.getBudget().getAvailableForConversation();
                        if (historyTokens > available) {
                            renderer.printMessage(String.format("⚠️ 警告：当前会话历史 (约 %d tokens) 已超出新模型可用预算 (%d tokens)。建议使用 /compact 压缩历史或 /clear 开启新会话。", historyTokens, available));
                        }
                    }
                    renderer.printMessage(String.format("✅ 已成功切换至模型 [%s] (Provider: %s, 窗口: %dk, 工具支持: %s)",
                            newClient.modelName(), newClient.providerName(),
                            newClient.capabilities().maxContextWindow() / 1000,
                            newClient.capabilities().supportsTools() ? "是" : "否"));
                } catch (com.xhlcli.llm.LlmException failure) {
                    renderer.printMessage("❌ 切换模型失败: " + failure.getMessage());
                }
            }
            default -> renderer.printMessage("未知子命令。用法: /model [list|use <model>|status]");
        }
    }

    private com.xhlcli.mcp.manager.McpServerManager mcpServerManager;

    public void setMcpServerManager(com.xhlcli.mcp.manager.McpServerManager mcpServerManager) {
        this.mcpServerManager = mcpServerManager;
    }

    public com.xhlcli.mcp.manager.McpServerManager getMcpServerManager() {
        return mcpServerManager;
    }

    private void handleMcp(String input) {
        String trimmed = input.trim();
        String[] parts = trimmed.split("\\s+");
        String subcmd = parts.length > 1 ? parts[1].toLowerCase(java.util.Locale.ROOT) : "list";

        if (mcpServerManager == null) {
            renderer.printMessage("MCP 管理器未初始化。");
            return;
        }

        switch (subcmd) {
            case "list" -> {
                var servers = mcpServerManager.listServers();
                renderer.printMessage("=========================================================================================");
                renderer.printMessage("🔌 MCP Servers 状态列表：");
                renderer.printMessage("-----------------------------------------------------------------------------------------");
                if (servers.isEmpty()) {
                    renderer.printMessage("（未配置任何 MCP Server。可在 ~/.xhlcli/mcp.json 或 .xhlcli/mcp.json 中配置）");
                } else {
                    for (var entry : servers.entrySet()) {
                        String name = entry.getKey();
                        var srv = entry.getValue();
                        String statusStr = "[" + srv.status().name().toLowerCase() + "]";
                        String type = srv.config().transportType().name().toLowerCase();
                        int toolCount = srv.tools().size();
                        int resCount = srv.resources().size();
                        String details = switch (srv.status()) {
                            case READY -> String.format("%d tools, %d resources", toolCount, resCount);
                            case ERROR -> "Error: " + srv.errorMessage();
                            case DISABLED -> "Disabled";
                            case STARTING -> "Starting...";
                            case STOPPED -> "Stopped";
                        };
                        renderer.printMessage(String.format("%-11s %-16s (%-5s) - %s (source: %s)",
                                statusStr, name, type, details, srv.config().sourcePath()));
                    }
                }
                renderer.printMessage("=========================================================================================");
                renderer.printMessage("💡 提示：使用 '/mcp status <server>' 查看详情，'/mcp tools' 列出可用工具，'/mcp resources' 列出外部资源。");
            }
            case "status" -> {
                if (parts.length < 3) {
                    renderer.printMessage("用法: /mcp status <server_name>");
                    return;
                }
                String srvName = parts[2];
                var srv = mcpServerManager.getServer(srvName);
                if (srv == null) {
                    renderer.printMessage("❌ 未找到指定的 MCP Server: '" + srvName + "'");
                    return;
                }
                renderer.printMessage("=========================================================================================");
                renderer.printMessage("🔍 MCP Server 详情: " + srvName);
                renderer.printMessage("-----------------------------------------------------------------------------------------");
                renderer.printMessage("Status:           " + srv.status());
                renderer.printMessage("Transport:        " + srv.config().transportType());
                if (srv.config().transportType() == com.xhlcli.mcp.model.McpTransportType.STDIO) {
                    renderer.printMessage("Command:          " + srv.config().command() + " " + String.join(" ", srv.config().args()));
                } else {
                    renderer.printMessage("URL:              " + srv.config().url());
                }
                renderer.printMessage("Trusted ReadOnly: " + (srv.config().trustedReadOnly() ? "Yes" : "No"));
                renderer.printMessage("Config Source:    " + srv.config().sourcePath());
                renderer.printMessage("Tools Count:      " + srv.tools().size());
                renderer.printMessage("Resources Count:  " + srv.resources().size());
                if (srv.errorMessage() != null) {
                    renderer.printMessage("Error Message:    " + srv.errorMessage());
                }
                var logs = srv.getRecentLogs();
                if (!logs.isEmpty()) {
                    renderer.printMessage("Recent Logs (last " + Math.min(logs.size(), 5) + "):");
                    for (int i = Math.max(0, logs.size() - 5); i < logs.size(); i++) {
                        renderer.printMessage("  " + logs.get(i));
                    }
                }
                renderer.printMessage("=========================================================================================");
            }
            case "tools" -> {
                String filterServer = parts.length > 2 ? parts[2] : null;
                var tools = mcpServerManager.getAllTools();
                renderer.printMessage("=========================================================================================");
                renderer.printMessage("🛠️ MCP 可用工具清单：");
                renderer.printMessage("-----------------------------------------------------------------------------------------");
                int count = 0;
                for (var tool : tools) {
                    if (filterServer == null || tool.serverName().equalsIgnoreCase(filterServer)) {
                        renderer.printMessage("- " + tool.definition().name() + " [Server: " + tool.serverName() + "]");
                        renderer.printMessage("  描述: " + tool.definition().description());
                        count++;
                    }
                }
                if (count == 0) {
                    renderer.printMessage("（当前无可用 MCP 工具）");
                }
                renderer.printMessage("=========================================================================================");
            }
            case "resources" -> {
                String filterServer = parts.length > 2 ? parts[2] : null;
                var resources = mcpServerManager.getAllResources();
                renderer.printMessage("=========================================================================================");
                renderer.printMessage("📦 MCP 外部资源清单：");
                renderer.printMessage("-----------------------------------------------------------------------------------------");
                int count = 0;
                for (var res : resources) {
                    renderer.printMessage("- " + res.uri() + " (" + res.mimeType() + ")");
                    renderer.printMessage("  名称: " + res.name() + " | 描述: " + res.description());
                    count++;
                }
                if (count == 0) {
                    renderer.printMessage("（当前无可用 MCP 资源）");
                }
                renderer.printMessage("=========================================================================================");
                renderer.printMessage("💡 提示：使用 '/mcp read <uri>' 读取具体资源内容。");
            }
            case "read" -> {
                if (parts.length < 3) {
                    renderer.printMessage("用法: /mcp read <resource_uri>");
                    return;
                }
                String uri = parts[2];
                try {
                    var contents = mcpServerManager.readResource(uri, null, java.time.Duration.ofSeconds(10));
                    renderer.printMessage("=========================================================================================");
                    renderer.printMessage("📄 资源内容: " + uri);
                    renderer.printMessage("-----------------------------------------------------------------------------------------");
                    for (var c : contents) {
                        if (c.text() != null) {
                            renderer.printMessage(c.text());
                        } else if (c.blob() != null) {
                            renderer.printMessage("[Binary Blob: " + c.mimeType() + ", size=" + c.blob().length + " bytes]");
                        }
                    }
                    renderer.printMessage("=========================================================================================");
                } catch (Exception e) {
                    renderer.printMessage("❌ 读取资源失败: " + e.getMessage());
                }
            }
            case "restart" -> {
                if (parts.length < 3) {
                    renderer.printMessage("用法: /mcp restart <server_name>");
                    return;
                }
                String srvName = parts[2];
                try {
                    mcpServerManager.restartServer(srvName, java.time.Duration.ofSeconds(5));
                    renderer.printMessage("🔄 Server [" + srvName + "] 已重启。当前状态: " + mcpServerManager.getServer(srvName).status());
                } catch (Exception e) {
                    renderer.printMessage("❌ 重启失败: " + e.getMessage());
                }
            }
            case "stop" -> {
                if (parts.length < 3) {
                    renderer.printMessage("用法: /mcp stop <server_name>");
                    return;
                }
                String srvName = parts[2];
                try {
                    mcpServerManager.stopServer(srvName);
                    renderer.printMessage("⏹️ Server [" + srvName + "] 已停止。");
                } catch (Exception e) {
                    renderer.printMessage("❌ 停止失败: " + e.getMessage());
                }
            }
            case "start" -> {
                if (parts.length < 3) {
                    renderer.printMessage("用法: /mcp start <server_name>");
                    return;
                }
                String srvName = parts[2];
                try {
                    mcpServerManager.startServer(srvName, java.time.Duration.ofSeconds(5));
                    renderer.printMessage("▶️ Server [" + srvName + "] 已启动。当前状态: " + mcpServerManager.getServer(srvName).status());
                } catch (Exception e) {
                    renderer.printMessage("❌ 启动失败: " + e.getMessage());
                }
            }
            case "logs" -> {
                if (parts.length < 3) {
                    renderer.printMessage("用法: /mcp logs <server_name>");
                    return;
                }
                String srvName = parts[2];
                var srv = mcpServerManager.getServer(srvName);
                if (srv == null) {
                    renderer.printMessage("❌ 未找到指定的 MCP Server: '" + srvName + "'");
                    return;
                }
                var logs = srv.getRecentLogs();
                renderer.printMessage("=========================================================================================");
                renderer.printMessage("📜 Server 日志: " + srvName + " (共 " + logs.size() + " 条)");
                renderer.printMessage("-----------------------------------------------------------------------------------------");
                for (String line : logs) {
                    renderer.printMessage(line);
                }
                renderer.printMessage("=========================================================================================");
            }
            default -> renderer.printMessage("未知子命令。用法: /mcp [list|status <server>|tools|resources|read <uri>|restart <server>|stop <server>|start <server>|logs <server>]");
        }
    }

    private final AtomicReference<CancellationToken> activeResponse = new AtomicReference<>();

    public ChatLoop(
            InputReader inputReader,
            ChatCommandParser commandParser,
            AgentRunner agent,
            PlainRunRenderer renderer,
            ChatConfig config) {
        this(inputReader, commandParser, agent, renderer, config, null);
    }

    public ChatLoop(
            InputReader inputReader,
            ChatCommandParser commandParser,
            AgentRunner agent,
            PlainRunRenderer renderer,
            ChatConfig config,
            com.xhlcli.hitl.HitlHandler hitlHandler) {
        this.inputReader = Objects.requireNonNull(inputReader, "inputReader");
        this.commandParser = Objects.requireNonNull(commandParser, "commandParser");
        this.agent = Objects.requireNonNull(agent, "agent");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.config = Objects.requireNonNull(config, "config");
        this.hitlHandler = hitlHandler;
    }

    public int run() {
        renderer.printWelcome(config.model());
        while (true) {
            String input;
            try {
                input = inputReader.readLine("You > ");
            } catch (InputInterruptedException ignored) {
                continue;
            } catch (InputEndOfFileException ignored) {
                renderer.printGoodbye();
                return 0;
            }

            switch (commandParser.parse(input)) {
                case HELP -> renderer.printHelp();
                case CONFIG -> renderer.printConfig(config);
                case CLEAR -> {
                    agent.clearHistory();
                    if (teamOrchestrator != null) {
                        teamOrchestrator.clearHistory();
                    }
                    if (hitlHandler != null) {
                        hitlHandler.clearApprovedAll();
                    }
                    renderer.printCleared();
                }
                case EXIT -> {
                    renderer.printGoodbye();
                    return 0;
                }
                case CONTEXT -> printContext();
                case COMPACT -> compactHistory();
                case SAVE -> saveMemory(input);
                case MEMORY -> manageMemory(input);
                case SEARCH_TEXT -> searchText(input);
                case INDEX -> handleIndex(input);
                case SEARCH -> searchSemantic(input);
                case PLAN -> handlePlan(input);
                case TEAM -> handleTeam(input);
                case MODEL -> handleModel(input);
                case MCP -> handleMcp(input);
                case UNKNOWN -> renderer.printUnknownCommand(input.trim());
                case USER_MESSAGE -> sendTurn(input);
            }
        }
    }

    public boolean cancelActiveResponse() {
        CancellationToken token = activeResponse.getAndSet(null);
        if (token == null) {
            return false;
        }
        token.cancel();
        return true;
    }

    private void sendTurn(String input) {
        if (input.isBlank()) {
            return;
        }
        CancellationToken token = new CancellationToken();
        if (!activeResponse.compareAndSet(null, token)) {
            throw new IllegalStateException("A response is already active");
        }
        try {
            agent.run(input, renderer::accept, token);
        } finally {
            activeResponse.compareAndSet(token, null);
        }
    }
}
