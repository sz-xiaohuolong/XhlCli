package com.xhlcli.tool;

import com.xhlcli.llm.CancellationToken;
import com.xhlcli.model.ToolCall;
import com.xhlcli.model.ToolResult;

@FunctionalInterface
public interface ToolExecutor {
    ToolResult execute(ToolCall call, CancellationToken cancellationToken);
}
