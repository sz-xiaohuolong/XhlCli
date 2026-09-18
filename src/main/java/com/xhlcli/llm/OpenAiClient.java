package com.xhlcli.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.config.ChatConfig;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;

import java.util.List;
import java.util.Locale;

public final class OpenAiClient extends AbstractOpenAiCompatibleClient {

    public OpenAiClient(ChatConfig config, DiagnosticSink diagnostics) {
        this(config, clientFor(config), new ObjectMapper(), Thread::sleep, diagnostics);
    }

    OpenAiClient(
            ChatConfig config,
            OkHttpClient httpClient,
            ObjectMapper mapper,
            Sleeper sleeper,
            DiagnosticSink diagnostics) {
        super(config, httpClient, mapper, sleeper, diagnostics);
    }

    private static OkHttpClient clientFor(ChatConfig config) {
        return new OkHttpClient.Builder()
                .connectTimeout(config.connectTimeout())
                .readTimeout(config.readTimeout())
                .callTimeout(config.requestTimeout())
                .protocols(List.of(Protocol.HTTP_1_1))
                .build();
    }

    @Override
    public String providerName() {
        return "openai";
    }

    @Override
    public ModelCapabilities capabilities() {
        String model = config.model().toLowerCase(Locale.ROOT);
        boolean isReasoning = model.startsWith("o1") || model.startsWith("o3");
        return new ModelCapabilities(
                128_000,
                true,
                true,
                false,
                "none",
                isReasoning
        );
    }
}
