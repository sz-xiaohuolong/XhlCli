package com.xhlcli.agent;

import com.xhlcli.llm.CancellationToken;
import com.xhlcli.model.ChatMessage;
import com.xhlcli.model.RunEventSink;
import com.xhlcli.model.RunResult;

import java.util.List;

/** Stateful boundary for one observable, bounded model-agent run. */
public interface AgentRunner {
    RunResult run(String input, RunEventSink events, CancellationToken cancellationToken);

    void clearHistory();

    List<ChatMessage> history();
}
