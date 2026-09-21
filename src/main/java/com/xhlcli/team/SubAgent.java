package com.xhlcli.team;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.llm.LlmClient;
import com.xhlcli.llm.LlmException;
import com.xhlcli.llm.StreamListener;
import com.xhlcli.model.ChatMessage;
import com.xhlcli.model.ChatResponse;
import com.xhlcli.model.ToolCall;
import com.xhlcli.model.ToolDefinition;
import com.xhlcli.model.ToolResult;
import com.xhlcli.tool.ToolExecutor;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 专职子代理（SubAgent）- Multi-Agent 协作体系中的核心执行单元。
 *
 * 维护角色分工与工具权限边界：
 * - PLANNER：仅规划，严禁使用任何工具（toolDefinitions 强制下发为空）
 * - WORKER：完整工具权限，具备多轮 ReAct 工具调用循环能力
 * - REVIEWER：仅审查，无写工具权限（toolDefinitions 强制下发为空）
 *
 * 具有独立的对话历史（conversationHistory），互不串联，支持 clearHistory 状态复位。
 */
public class SubAgent {
    private static final Logger log = Logger.getLogger(SubAgent.class.getName());
    private static final int DEFAULT_MAX_ITERATIONS = 10;

    private final String name;
    private final TeamRole role;
    private volatile LlmClient llmClient;
    private final ToolExecutor toolExecutor;

    public void setClient(LlmClient client) {
        this.llmClient = Objects.requireNonNull(client, "client");
    }

    public LlmClient getClient() {
        return llmClient;
    }
    private volatile List<ToolDefinition> toolDefinitions;

    public void setToolDefinitions(List<ToolDefinition> toolDefinitions) {
        if (role == TeamRole.WORKER) {
            this.toolDefinitions = toolDefinitions != null ? List.copyOf(toolDefinitions) : List.of();
        }
    }
    private final List<ChatMessage> conversationHistory;
    private final ObjectMapper mapper;
    private final int maxIterations;

    public SubAgent(String name, TeamRole role, LlmClient llmClient) {
        this(name, role, llmClient, null, List.of(), DEFAULT_MAX_ITERATIONS);
    }

    public SubAgent(String name, TeamRole role, LlmClient llmClient, ToolExecutor toolExecutor, List<ToolDefinition> toolDefinitions) {
        this(name, role, llmClient, toolExecutor, toolDefinitions, DEFAULT_MAX_ITERATIONS);
    }

    public SubAgent(
            String name,
            TeamRole role,
            LlmClient llmClient,
            ToolExecutor toolExecutor,
            List<ToolDefinition> toolDefinitions,
            int maxIterations) {
        this.name = Objects.requireNonNull(name, "name cannot be null");
        this.role = Objects.requireNonNull(role, "role cannot be null");
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient cannot be null");
        this.toolExecutor = toolExecutor;
        this.mapper = new ObjectMapper();
        this.maxIterations = Math.max(1, maxIterations);

        // 工具下发权限控制：仅 WORKER 允许持有工具，PLANNER 和 REVIEWER 强制屏蔽
        if (role == TeamRole.WORKER && toolDefinitions != null) {
            this.toolDefinitions = List.copyOf(toolDefinitions);
        } else {
            this.toolDefinitions = List.of();
        }

        this.conversationHistory = new ArrayList<>();
        // 初始注入专职系统提示词
        this.conversationHistory.add(ChatMessage.system(TeamPrompts.getSystemPrompt(role)));
    }

    public String getName() {
        return name;
    }

    public TeamRole getRole() {
        return role;
    }

    public List<ToolDefinition> getToolDefinitions() {
        return Collections.unmodifiableList(toolDefinitions);
    }

    public List<ChatMessage> getConversationHistory() {
        return Collections.unmodifiableList(conversationHistory);
    }

    /**
     * 清空对话历史，但保留首条专职系统提示词。
     */
    public synchronized void clearHistory() {
        if (!conversationHistory.isEmpty()) {
            ChatMessage systemMsg = conversationHistory.get(0);
            conversationHistory.clear();
            conversationHistory.add(systemMsg);
        }
    }

    public TeamMessage execute(TeamMessage task) {
        return execute(task, System.out, new CancellationToken());
    }

    public TeamMessage execute(TeamMessage task, PrintStream out) {
        return execute(task, out, new CancellationToken());
    }

    /**
     * 执行具体任务，支持将流式输出写入指定 PrintStream（如独立内存缓冲流）。
     */
    public synchronized TeamMessage execute(TeamMessage task, PrintStream out, CancellationToken cancellationToken) {
        if (out == null) {
            out = System.out;
        }
        if (cancellationToken == null) {
            cancellationToken = new CancellationToken();
        }

        if (cancellationToken.isCancelled()) {
            return TeamMessage.error(name, role, "任务已被取消");
        }

        String inputContent = task.content();
        if (inputContent == null || inputContent.isBlank()) {
            inputContent = "请根据分配的角色开始执行工作。";
        }

        conversationHistory.add(ChatMessage.user(inputContent));

        int iteration = 0;
        while (iteration < maxIterations) {
            if (cancellationToken.isCancelled()) {
                return TeamMessage.error(name, role, "任务已被取消");
            }
            iteration++;

            PrintStream finalOut = out;
            StreamListener listener = delta -> {
                if (delta != null && !delta.isEmpty()) {
                    finalOut.print(delta);
                    finalOut.flush();
                }
            };

            try {
                ChatResponse response = llmClient.stream(
                        conversationHistory,
                        toolDefinitions,
                        listener,
                        cancellationToken
                );

                if (response.hasToolCalls() && role == TeamRole.WORKER && toolExecutor != null) {
                    finalOut.println();
                    conversationHistory.add(ChatMessage.assistant(response.content(), response.toolCalls()));

                    for (ToolCall tc : response.toolCalls()) {
                        if (cancellationToken.isCancelled()) {
                            return TeamMessage.error(name, role, "任务已被取消");
                        }
                        finalOut.println("🔧 [" + name + "] 调用工具: " + tc.name());
                        ToolResult toolResult = toolExecutor.execute(tc, cancellationToken);
                        String observation = toolResult.observationJson(mapper);
                        conversationHistory.add(ChatMessage.tool(tc.id(), observation));
                    }
                    continue;
                }

                // 没有工具调用，任务完成
                finalOut.println();
                conversationHistory.add(ChatMessage.assistant(response.content()));
                return TeamMessage.result(name, role, response.content());

            } catch (LlmException e) {
                log.log(Level.SEVERE, "[" + name + "] LLM stream failed: " + e.getMessage(), e);
                return TeamMessage.error(name, role, "LLM 调用失败: " + e.getMessage());
            } catch (Exception e) {
                if (cancellationToken.isCancelled()) {
                    return TeamMessage.error(name, role, "任务已被取消");
                }
                log.log(Level.SEVERE, "[" + name + "] SubAgent execution error: " + e.getMessage(), e);
                return TeamMessage.error(name, role, "执行异常: " + e.getMessage());
            }
        }

        return TeamMessage.error(name, role, "已达到单任务最大工具调用轮数限制 (" + maxIterations + ")");
    }

    /**
     * 带上下文注入执行任务（用于 Worker 接收前置步骤结果与交接信息）。
     */
    public TeamMessage executeWithContext(TeamMessage task, String context, PrintStream out, CancellationToken cancellationToken) {
        String enrichedContent = task.content();
        if (context != null && !context.isBlank()) {
            enrichedContent = context.trim() + "\n\n" + task.content();
        }
        TeamMessage enrichedTask = new TeamMessage(task.fromAgent(), task.fromRole(), enrichedContent, task.type(), task.timestamp());
        return execute(enrichedTask, out, cancellationToken);
    }

    /**
     * 基于最小化交接包执行任务。
     */
    public TeamMessage executeWithHandover(HandoverPackage handover, PrintStream out, CancellationToken cancellationToken) {
        String prompt = handover.toPromptText();
        TeamMessage task = TeamMessage.task("orchestrator", prompt);
        return execute(task, out, cancellationToken);
    }

    /**
     * 针对步骤结果执行质量审查（Reviewer 专职方法）。
     */
    public TeamMessage review(String originalTask, String executionResult, PrintStream out, CancellationToken cancellationToken) {
        String reviewInput = """
                【待审查步骤任务】
                %s

                【执行产出结果】
                %s

                请严格按规定的 JSON 格式审查执行质量并给出 approved 与 issues/suggestions 结论。
                """.formatted(originalTask == null ? "" : originalTask.trim(),
                executionResult == null ? "" : executionResult.trim());

        TeamMessage reviewTask = TeamMessage.task("orchestrator", reviewInput);
        return execute(reviewTask, out, cancellationToken);
    }
}
