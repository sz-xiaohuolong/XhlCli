package com.xhlcli.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.model.ToolOutput;
import com.xhlcli.tool.demo.CurrentTimeTool;
import com.xhlcli.tool.demo.EchoTool;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DemoToolsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void echoTextPreservesUnicodeContent() throws Exception {
        EchoTool tool = new EchoTool();

        ToolOutput output = tool.execute(mapper.readTree("{\"text\":\"你好，XhlCLI 👋\"}"), new CancellationToken());

        assertEquals("你好，XhlCLI 👋", output.data().path("text").asText());
    }

    @Test
    void currentTimeUsesItsInjectedClockAndRejectsExtraArgumentsInItsDefinition() throws Exception {
        CurrentTimeTool tool = new CurrentTimeTool(Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC));

        ToolOutput output = tool.execute(mapper.readTree("{}"), new CancellationToken());

        assertEquals("2026-08-27T00:00:00Z", output.data().path("time").asText());
        assertFalse(tool.definition().parameters().path("additionalProperties").asBoolean(true));
    }
}
