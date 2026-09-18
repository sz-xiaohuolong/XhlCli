package com.xhlcli.llm;

import com.xhlcli.model.ChatMessage;
import com.xhlcli.model.ChatResponse;
import com.xhlcli.model.ToolDefinition;

import java.util.List;

public interface LlmClient extends AutoCloseable {
    ChatResponse stream(
            List<ChatMessage> messages,
            List<ToolDefinition> tools,
            StreamListener listener,
            CancellationToken cancellationToken) throws LlmException;

    default ChatResponse stream(
            List<ChatMessage> messages,
            StreamListener listener,
            CancellationToken cancellationToken) throws LlmException {
        return stream(messages, List.of(), listener, cancellationToken);
    }

    default String providerName() {
        return "unknown";
    }

    default String modelName() {
        return "unknown";
    }

    default ModelCapabilities capabilities() {
        return ModelCapabilities.openAiDefault();
    }

    @Override
    default void close() {}
}
