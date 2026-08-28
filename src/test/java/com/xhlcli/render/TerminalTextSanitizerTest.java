package com.xhlcli.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TerminalTextSanitizerTest {

    @Test
    void removesCsiAcrossEverySplitWhileKeepingVisibleText() {
        String source = "before\u001B[31mred\u001B[0mafter";
        for (int split = 0; split <= source.length(); split++) {
            TerminalTextSanitizer sanitizer = new TerminalTextSanitizer();
            String rendered = sanitizer.accept(source.substring(0, split))
                    + sanitizer.accept(source.substring(split)) + sanitizer.finish();
            assertEquals("beforeredafter", rendered, "split=" + split);
        }
    }

    @Test
    void removesOsc52AndAllStringControlsAcrossChunks() {
        assertControlRemoved("before\u001B]52;c;clipboard\u0007after", "clipboard");
        assertControlRemoved("before\u001B]52;c;clipboard\u001B\\after", "clipboard");
        assertControlRemoved("before\u001BPpayload\u001B\\after", "payload");
        assertControlRemoved("before\u001BXpayload\u001B\\after", "payload");
        assertControlRemoved("before\u001B^payload\u001B\\after", "payload");
        assertControlRemoved("before\u001B_payload\u001B\\after", "payload");
    }

    @Test
    void removesUnsafeControlCharactersButKeepsLinesAndUnicode() {
        assertEquals("first\nsecond third\u4f60\u597d", TerminalTextSanitizer.sanitize("first\r\nsecond\tthird\u0007\u001b\u4f60\u597d"));
    }

    private static void assertControlRemoved(String source, String payload) {
        for (int split = 0; split <= source.length(); split++) {
            TerminalTextSanitizer sanitizer = new TerminalTextSanitizer();
            String rendered = sanitizer.accept(source.substring(0, split))
                    + sanitizer.accept(source.substring(split)) + sanitizer.finish();
            assertEquals("beforeafter", rendered, "split=" + split);
            assertFalse(rendered.contains(payload), "split=" + split);
        }
    }
}
