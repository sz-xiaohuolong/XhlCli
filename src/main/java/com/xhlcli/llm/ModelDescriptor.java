package com.xhlcli.llm;

import java.util.List;
import java.util.Objects;

/**
 * 模型描述符（ModelDescriptor）。
 * 集中声明模型的标识、Provider 归属、显示名称、能力元数据与别名。
 */
public record ModelDescriptor(
        String id,
        String provider,
        String modelName,
        String displayName,
        ModelCapabilities capabilities,
        boolean configured,
        List<String> aliases
) {
    public ModelDescriptor {
        Objects.requireNonNull(id, "id cannot be null");
        Objects.requireNonNull(provider, "provider cannot be null");
        Objects.requireNonNull(modelName, "modelName cannot be null");
        Objects.requireNonNull(displayName, "displayName cannot be null");
        Objects.requireNonNull(capabilities, "capabilities cannot be null");
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }

    public ModelDescriptor withConfigured(boolean newConfigured) {
        return new ModelDescriptor(id, provider, modelName, displayName, capabilities, newConfigured, aliases);
    }

    public boolean matches(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String q = query.trim().toLowerCase(java.util.Locale.ROOT);
        if (id.toLowerCase(java.util.Locale.ROOT).equals(q)) {
            return true;
        }
        if (modelName.toLowerCase(java.util.Locale.ROOT).equals(q)) {
            return true;
        }
        if (provider.toLowerCase(java.util.Locale.ROOT).equals(q)) {
            return true;
        }
        for (String alias : aliases) {
            if (alias.toLowerCase(java.util.Locale.ROOT).equals(q)) {
                return true;
            }
        }
        return false;
    }
}
