package com.xhlcli.render;

import com.xhlcli.config.ChatConfig;
import com.xhlcli.config.LogLevel;
import com.xhlcli.config.SecretRedactor;
import com.xhlcli.llm.DiagnosticSink;

import java.io.PrintStream;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.TreeMap;

public final class PlainDiagnosticSink implements DiagnosticSink {
    private final ChatConfig config;
    private final PrintStream err;

    public PlainDiagnosticSink(ChatConfig config, PrintStream err) {
        this.config = Objects.requireNonNull(config, "config");
        this.err = Objects.requireNonNull(err, "err");
    }

    @Override
    public synchronized void debug(String event, Map<String, String> metadata) {
        if (!config.logLevel().allows(LogLevel.DEBUG)) {
            return;
        }
        StringJoiner line = new StringJoiner(" ", "[debug] " + event, "");
        new TreeMap<>(metadata).forEach((key, value) -> line.add(key + "=" + value));
        err.println(SecretRedactor.redact(line.toString(), config.apiKey()));
    }
}
