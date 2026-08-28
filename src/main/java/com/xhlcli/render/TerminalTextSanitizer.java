package com.xhlcli.render;

import java.util.Objects;

/** Removes terminal control sequences safely even when they cross stream chunks. */
public final class TerminalTextSanitizer {
    private State state = State.NORMAL;

    public String accept(String chunk) {
        Objects.requireNonNull(chunk, "chunk");
        StringBuilder safe = new StringBuilder(chunk.length());
        for (int index = 0; index < chunk.length(); index++) {
            consume(chunk.charAt(index), safe);
        }
        return safe.toString();
    }

    public String finish() {
        state = State.NORMAL;
        return "";
    }

    public static String sanitize(String value) {
        TerminalTextSanitizer sanitizer = new TerminalTextSanitizer();
        return sanitizer.accept(value == null ? "" : value) + sanitizer.finish();
    }

    private void consume(char value, StringBuilder safe) {
        switch (state) {
            case NORMAL -> consumeNormal(value, safe);
            case ESCAPE -> consumeEscape(value, safe);
            case CSI -> consumeCsi(value, safe);
            case OSC -> consumeStringControl(value, State.OSC_ESCAPE);
            case OSC_ESCAPE -> consumeStringEscape(value, State.OSC);
            case DCS -> consumeStringControl(value, State.DCS_ESCAPE);
            case DCS_ESCAPE -> consumeStringEscape(value, State.DCS);
            case CONTROL_STRING -> consumeStringControl(value, State.CONTROL_STRING_ESCAPE);
            case CONTROL_STRING_ESCAPE -> consumeStringEscape(value, State.CONTROL_STRING);
        }
    }

    private void consumeNormal(char value, StringBuilder safe) {
        if (value == 0x1b) {
            state = State.ESCAPE;
        } else if (value == 0x9b) {
            state = State.CSI;
        } else if (value == 0x9d) {
            state = State.OSC;
        } else if (value == 0x90) {
            state = State.DCS;
        } else if (value == 0x98 || value == 0x9e || value == 0x9f) {
            state = State.CONTROL_STRING;
        } else if (value == '\n') {
            safe.append(value);
        } else if (value == '\t') {
            safe.append(' ');
        } else if (!isControl(value)) {
            safe.append(value);
        }
    }

    private void consumeEscape(char value, StringBuilder safe) {
        if (value == '[') {
            state = State.CSI;
        } else if (value == ']') {
            state = State.OSC;
        } else if (value == 'P') {
            state = State.DCS;
        } else if (value == 'X' || value == '^' || value == '_') {
            state = State.CONTROL_STRING;
        } else if (value == '\n') {
            safe.append('\n');
            state = State.NORMAL;
        } else {
            state = State.NORMAL;
            if (value > 0x7e) {
                consumeNormal(value, safe);
            }
        }
    }

    private void consumeCsi(char value, StringBuilder safe) {
        if (value >= 0x40 && value <= 0x7e) {
            state = State.NORMAL;
        } else if (value == '\n') {
            safe.append('\n');
            state = State.NORMAL;
        }
    }

    private void consumeStringControl(char value, State escapeState) {
        if (value == 0x07 || value == 0x9c) {
            state = State.NORMAL;
        } else if (value == 0x1b) {
            state = escapeState;
        }
    }

    private void consumeStringEscape(char value, State stringState) {
        if (value == '\\') {
            state = State.NORMAL;
        } else if (value != 0x1b) {
            state = stringState;
        }
    }

    private static boolean isControl(char value) {
        return value < 0x20 || (value >= 0x7f && value <= 0x9f);
    }

    private enum State {
        NORMAL, ESCAPE, CSI, OSC, OSC_ESCAPE, DCS, DCS_ESCAPE, CONTROL_STRING, CONTROL_STRING_ESCAPE
    }
}
