package com.xhlcli.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.model.ChatMessage;
import java.util.List;
import java.util.Map;

/** 在预算检查前生成请求副本，不修改历史或模型调用协议。 */
@FunctionalInterface
public interface ContextOptimizer {
    Result optimize(List<ChatMessage> messages, String model, CancellationToken cancellation);

    default boolean enabled() { return true; }

    record Result(List<ChatMessage> messages, ContextOptimizationReport report) {
        public Result { messages = List.copyOf(messages); }
    }

    static ContextOptimizer disabled() {
        return new ContextOptimizer() {
            @Override public boolean enabled() { return false; }
            @Override public Result optimize(List<ChatMessage> messages, String model, CancellationToken cancellation) {
                int tokens = TokenBudget.estimateTokens(messages);
                return new Result(messages, new ContextOptimizationReport(ContextOptimizationReport.Status.DISABLED, tokens, tokens, 0, 0, "DISABLED"));
            }
        };
    }

    static ContextOptimizer fromEnvironment(Map<String, String> environment, ObjectMapper mapper) {
        HeadroomSettings settings = HeadroomSettings.fromEnvironment(environment);
        return settings.enabled() ? new HeadroomContextOptimizer(settings, mapper) : disabled();
    }
}
