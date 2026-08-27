package com.xhlcli.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.model.ToolDefinition;
import com.xhlcli.model.ToolOutput;

/** An in-process operation that can be invoked by a model tool call. */
public interface Tool {
    ToolDefinition definition();

    ToolOutput execute(JsonNode arguments, CancellationToken cancellationToken) throws Exception;
}
