package com.xhlcli.memory;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MemoryEntry(
        String id,
        String content,
        String scope,
        String timestamp,
        String source
) {
    public MemoryEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(timestamp, "timestamp");
    }

    public static MemoryEntry create(String content, String scope, String source) {
        return new MemoryEntry(
                UUID.randomUUID().toString().substring(0, 8),
                content,
                scope,
                Instant.now().toString(),
                source
        );
    }
}
