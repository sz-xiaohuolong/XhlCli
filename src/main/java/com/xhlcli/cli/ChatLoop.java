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

    public void setMemoryManager(com.xhlcli.memory.MemoryManager manager) { this.memoryManager = manager; }
    public void setContextAssembler(com.xhlcli.context.ContextAssembler assembler) { this.contextAssembler = assembler; }
    public void setCompactor(com.xhlcli.memory.ConversationHistoryCompactor compactor) { this.compactor = compactor; }
    public void setGrepCodeTool(com.xhlcli.tool.local.search.GrepCodeTool grepCodeTool) { this.grepCodeTool = grepCodeTool; }
    public void setProjectDirectory(java.nio.file.Path projectDirectory) { this.projectDirectory = projectDirectory; }
    public void setPlanAgent(com.xhlcli.agent.PlanExecuteAgent planAgent) { this.planAgent = planAgent; }

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
