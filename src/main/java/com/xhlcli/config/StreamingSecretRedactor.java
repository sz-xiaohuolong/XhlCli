package com.xhlcli.config;

import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Incrementally redacts credentials without emitting a token while it may still be
 * completed by a later stream chunk.
 */
public final class StreamingSecretRedactor {
    private static final int MARKER_TAIL = 40;
    private static final Pattern QUOTED_START = Pattern.compile(
            "(?i)([\"'](?:api[_-]?key|apikey|authorization|token|password)[\"']\\s*[:=]\\s*)([\"'])");
    private static final Pattern BEARER_START = Pattern.compile("(?i)\\bBearer\\s+");
    private static final Pattern BARE_START = Pattern.compile(
            "(?i)\\b(?:api[_-]?key|apikey|authorization|token|password)\\s*[:=]\\s*");
    private static final Pattern PARTIAL_MARKER = Pattern.compile(
            "(?i)(?:Bearer\\s*|[\"']?(?:api[_-]?key|apikey|authorization|token|password)[\"']?"
                    + "\\s*[:=]?\\s*[\"']?)$");

    private final String knownKey;
    private final Function<String, String> customSanitizer;
    private final boolean bufferAll;
    private final StringBuilder pending = new StringBuilder();
    private boolean finished;

    public StreamingSecretRedactor(String knownKey) {
        this(knownKey, null, false);
    }

    private StreamingSecretRedactor(
            String knownKey, Function<String, String> customSanitizer, boolean bufferAll) {
        this.knownKey = knownKey == null || knownKey.isBlank() ? null : knownKey;
        this.customSanitizer = customSanitizer;
        this.bufferAll = bufferAll;
    }

    public static StreamingSecretRedactor buffered(Function<String, String> sanitizer) {
        return new StreamingSecretRedactor(null, Objects.requireNonNull(sanitizer, "sanitizer"), true);
    }

    public String accept(String chunk) {
        if (finished) {
            throw new IllegalStateException("streaming redactor is already finished");
        }
        pending.append(Objects.requireNonNull(chunk, "chunk"));
        if (bufferAll) {
            return "";
        }
        return drain(false);
    }

    public String finish() {
        if (finished) {
            return "";
        }
        finished = true;
        return drain(true);
    }

    private String drain(boolean finalChunk) {
        if (pending.isEmpty()) {
            return "";
        }
        if (bufferAll) {
            String source = pending.toString();
            pending.setLength(0);
            return Objects.requireNonNull(customSanitizer.apply(source), "sanitizer result");
        }
        int safeEnd = finalChunk
                ? pending.length()
                : Math.max(0, pending.length() - Math.max(MARKER_TAIL, knownKeyTail()));
        if (!finalChunk) {
            safeEnd = protectKnownKeyCrossing(safeEnd);
            Matcher partialMarker = PARTIAL_MARKER.matcher(pending);
            if (partialMarker.find() && partialMarker.start() < safeEnd) {
                safeEnd = partialMarker.start();
            }
            safeEnd = protectCredentialCrossing(safeEnd, QUOTED_START, CredentialKind.QUOTED);
            safeEnd = protectCredentialCrossing(safeEnd, BEARER_START, CredentialKind.BARE);
            safeEnd = protectCredentialCrossing(safeEnd, BARE_START, CredentialKind.BARE);
        }
        if (safeEnd <= 0) {
            return "";
        }
        String source = pending.substring(0, safeEnd);
        pending.delete(0, safeEnd);
        return redact(source, finalChunk && pending.isEmpty());
    }

    private int knownKeyTail() {
        return knownKey == null ? 0 : Math.max(0, knownKey.length() - 1);
    }

    private int protectKnownKeyCrossing(int safeEnd) {
        if (knownKey == null) {
            return safeEnd;
        }
        int from = 0;
        while (from < pending.length()) {
            int start = pending.indexOf(knownKey, from);
            if (start < 0) {
                break;
            }
            if (start < safeEnd && start + knownKey.length() > safeEnd) {
                safeEnd = start;
            }
            from = start + 1;
        }
        return safeEnd;
    }

    private int protectCredentialCrossing(int safeEnd, Pattern marker, CredentialKind kind) {
        Matcher matcher = marker.matcher(pending);
        while (matcher.find()) {
            int valueEnd = kind == CredentialKind.QUOTED
                    ? quotedValueEnd(matcher.end(), matcher.group(2).charAt(0))
                    : bareValueEnd(matcher.end());
            if (matcher.start() < safeEnd && valueEnd > safeEnd) {
                safeEnd = matcher.start();
            }
        }
        return safeEnd;
    }

    private int quotedValueEnd(int start, char quote) {
        boolean escaped = false;
        for (int index = start; index < pending.length(); index++) {
            char value = pending.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (value == '\\') {
                escaped = true;
            } else if (value == quote) {
                return index + 1;
            }
        }
        return pending.length() + 1;
    }

    private int bareValueEnd(int start) {
        for (int index = start; index < pending.length(); index++) {
            char value = pending.charAt(index);
            if (Character.isWhitespace(value) || value == ',' || value == '}' || value == '"' || value == '\'') {
                return index;
            }
        }
        return pending.length() + 1;
    }

    private String redact(String source, boolean finalChunk) {
        String redacted = SecretRedactor.redact(source, knownKey);
        if (!finalChunk) {
            return redacted;
        }
        Matcher quoted = QUOTED_START.matcher(redacted);
        int searchFrom = 0;
        while (quoted.find(searchFrom)) {
            int end = findClosingQuote(redacted, quoted.end(), quoted.group(2).charAt(0));
            if (end >= 0) {
                searchFrom = end + 1;
                continue;
            }
            return redacted.substring(0, quoted.end()) + "***";
        }
        return redacted;
    }

    private static int findClosingQuote(String value, int start, char quote) {
        boolean escaped = false;
        for (int index = start; index < value.length(); index++) {
            char current = value.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (current == quote) {
                return index;
            }
        }
        return -1;
    }

    private enum CredentialKind { QUOTED, BARE }
}
