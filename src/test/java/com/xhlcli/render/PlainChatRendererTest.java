package com.xhlcli.render;

import com.xhlcli.app.ChatEvent;
import com.xhlcli.llm.LlmErrorType;
import com.xhlcli.model.TokenUsage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlainChatRendererTest {

    @Test
    void rendersStreamingPrefixOnceAndKnownUsage() {
        Streams streams = new Streams("secret");

        streams.renderer.accept(new ChatEvent.Waiting());
        streams.renderer.accept(new ChatEvent.TextDelta("你"));
        streams.renderer.accept(new ChatEvent.TextDelta("好"));
        streams.renderer.accept(new ChatEvent.Completed(new TokenUsage(7, 2, true)));

        assertEquals("Thinking...\nAssistant: 你好\n[tokens: input=7, output=2]\n", streams.out());
        assertEquals("", streams.err());
    }

    @Test
    void rendersUnknownUsage() {
        Streams streams = new Streams(null);

        streams.renderer.accept(new ChatEvent.TextDelta("hello"));
        streams.renderer.accept(new ChatEvent.Completed(TokenUsage.unknown()));

        assertTrue(streams.out().endsWith("[tokens: unknown]\n"));
    }

    @Test
    void rendersActionableSanitizedFailureAndPartialMarker() {
        Streams streams = new Streams("top-secret");

        streams.renderer.accept(new ChatEvent.TextDelta("partial"));
        streams.renderer.accept(new ChatEvent.Failed(
                LlmErrorType.AUTHENTICATION,
                "provider rejected top-secret",
                true));

        assertFalse(streams.err().contains("top-secret"));
        assertTrue(streams.err().contains("AUTHENTICATION"));
        assertTrue(streams.err().contains("response incomplete"));
        assertTrue(streams.err().contains("Check DEEPSEEK_API_KEY"));
        assertTrue(streams.err().startsWith("\n"));
    }

    @Test
    void cancellationRestoresANewline() {
        Streams streams = new Streams(null);
        streams.renderer.accept(new ChatEvent.TextDelta("partial"));

        streams.renderer.accept(new ChatEvent.Cancelled());

        assertEquals("\nCancelled.\n", streams.err());
    }

    private static final class Streams {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final ByteArrayOutputStream err = new ByteArrayOutputStream();
        private final PlainChatRenderer renderer;

        private Streams(String apiKey) {
            renderer = new PlainChatRenderer(
                    new PrintStream(out, true, StandardCharsets.UTF_8),
                    new PrintStream(err, true, StandardCharsets.UTF_8),
                    apiKey);
        }

        private String out() {
            return out.toString(StandardCharsets.UTF_8);
        }

        private String err() {
            return err.toString(StandardCharsets.UTF_8);
        }
    }
}
