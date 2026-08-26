package com.xhlcli.llm;

import com.xhlcli.model.ChatMessage;
import com.xhlcli.model.ChatResponse;

import java.util.List;

public interface LlmClient extends AutoCloseable {
    ChatResponse stream(
            List<ChatMessage> messages,
            StreamListener listener,
            CancellationToken cancellationToken) throws LlmException;

    @Override
    default void close() {}
}
