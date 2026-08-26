package com.xhlcli.config;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SecretRedactor {
    private static final Pattern BEARER = Pattern.compile("(?i)(Bearer\\s+)[^\\s,}\"]+");
    private static final Pattern QUOTED_CREDENTIAL = Pattern.compile(
            "(?i)([\"'](?:api[_-]?key|apiKey|authorization|token|password)[\"']\\s*[:=]\\s*[\"'])(.*?)([\"'])");
    private static final Pattern BARE_CREDENTIAL = Pattern.compile(
            "(?i)((?:api[_-]?key|apiKey|authorization|token|password)\\s*[:=]\\s*)[^\\s,}]+");

    private SecretRedactor() {}

    public static String redact(String text, String knownKey) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        String redacted = text;
        if (knownKey != null && !knownKey.isBlank()) {
            redacted = redacted.replaceAll(Pattern.quote(knownKey), Matcher.quoteReplacement("***"));
        }
        redacted = BEARER.matcher(redacted).replaceAll("$1***");
        redacted = QUOTED_CREDENTIAL.matcher(redacted).replaceAll("$1***$3");
        return BARE_CREDENTIAL.matcher(redacted).replaceAll("$1***");
    }
}
