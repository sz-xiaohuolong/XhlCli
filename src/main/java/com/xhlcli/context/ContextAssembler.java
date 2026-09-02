package com.xhlcli.context;

import com.xhlcli.model.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 组装每次向大模型发出的完整上下文请求。
 */
public class ContextAssembler {
    
    private final TokenBudget budget;
    
    public ContextAssembler(TokenBudget budget) {
        this.budget = budget;
    }

    public TokenBudget getBudget() {
        return budget;
    }

    /**
     * 组装完整的上下文
     */
    public List<ChatMessage> assemble(
            String baseSystemRules,
            String toolPolicy,
            String agentMode,
            String runtimeContext,
            String projectRules,
            List<ChatMessage> retrievedMemory,
            List<ChatMessage> compactedConversation,
            List<ChatMessage> currentInput) {
        
        List<ChatMessage> context = new ArrayList<>();
        
        // 1-5. System level rules
        StringBuilder systemContent = new StringBuilder();
        if (baseSystemRules != null && !baseSystemRules.isBlank()) {
            systemContent.append(baseSystemRules).append("\n\n");
        }
        if (toolPolicy != null && !toolPolicy.isBlank()) {
            systemContent.append("## Tool & Safety Policy\n").append(toolPolicy).append("\n\n");
        }
        if (agentMode != null && !agentMode.isBlank()) {
            systemContent.append("## Agent Mode\n").append(agentMode).append("\n\n");
        }
        if (runtimeContext != null && !runtimeContext.isBlank()) {
            systemContent.append("## Runtime Context\n").append(runtimeContext).append("\n\n");
        }
        if (projectRules != null && !projectRules.isBlank()) {
            systemContent.append("## Project Rules\n").append(projectRules).append("\n\n");
        }

        if (!systemContent.isEmpty()) {
            context.add(ChatMessage.system(systemContent.toString().trim()));
        }

        // 6. Retrieved memory
        if (retrievedMemory != null) {
            context.addAll(retrievedMemory);
        }

        // 7. Compacted conversation (摘要)
        if (compactedConversation != null) {
            context.addAll(compactedConversation);
        }

        // 8. Current input (当前会话窗口的未压缩记录)
        if (currentInput != null) {
            context.addAll(currentInput);
        }
        
        return context;
    }
}
