package com.xhlcli.web;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Locale;

/**
 * 搜索引擎工厂，按环境变量、.env 与已配置凭据选择最佳 SearchProvider。
 */
public final class SearchProviderFactory {

    private SearchProviderFactory() {}

    public static SearchProvider create() {
        String provider = readEnv("SEARCH_PROVIDER");
        String serpKey = readEnv("SERPAPI_KEY");
        String searxngUrl = readEnv("SEARXNG_URL");
        String zhipuKey = readEnv("ZHIPU_API_KEY");
        if (zhipuKey == null || zhipuKey.isBlank()) {
            zhipuKey = readEnv("GLM_API_KEY");
        }
        String zhipuEngine = readEnv("ZHIPU_SEARCH_ENGINE");

        String chosen = pickProvider(provider, serpKey, searxngUrl, zhipuKey);

        return switch (chosen) {
            case "serpapi" -> new SerpApiSearchProvider(serpKey);
            case "searxng" -> new SearxngSearchProvider(searxngUrl);
            case "zhipu" -> new ZhipuSearchProvider(zhipuKey, zhipuEngine);
            default -> new DuckDuckGoSearchProvider();
        };
    }

    static String pickProvider(String explicit, String serpKey, String searxngUrl, String zhipuKey) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit.trim().toLowerCase(Locale.ROOT);
        }
        if (serpKey != null && !serpKey.isBlank()) {
            return "serpapi";
        }
        if (searxngUrl != null && !searxngUrl.isBlank()) {
            return "searxng";
        }
        if (zhipuKey != null && !zhipuKey.isBlank()) {
            return "zhipu";
        }
        return "duckduckgo";
    }

    private static String readEnv(String key) {
        String fromEnv = System.getenv(key);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        String fromProp = System.getProperty(key);
        if (fromProp != null && !fromProp.isBlank()) {
            return fromProp.trim();
        }
        return readFromDotEnv(key);
    }

    private static String readFromDotEnv(String key) {
        File[] envFiles = {new File(".env"), new File(System.getProperty("user.home"), ".xhlcli/.env"), new File(System.getProperty("user.home"), ".env")};
        for (File envFile : envFiles) {
            if (!envFile.exists() || !envFile.isFile()) continue;
            try (BufferedReader reader = new BufferedReader(new FileReader(envFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (line.startsWith(key + "=")) {
                        return line.substring((key + "=").length()).trim();
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
