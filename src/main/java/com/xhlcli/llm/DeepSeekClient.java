package com.xhlcli.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.config.ChatConfig;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;

import java.util.List;

public final class DeepSeekClient extends AbstractOpenAiCompatibleClient {

    public DeepSeekClient(ChatConfig config, DiagnosticSink diagnostics) {
        this(config, clientFor(config), new ObjectMapper(), Thread::sleep, diagnostics);
    }

    DeepSeekClient(
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
    protected String providerName() {
        return "deepseek";
    }
}
