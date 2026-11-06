package com.xhlcli.memory;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MemoryEntry(
        String id,
        String content,
        String scope,
        String timestamp,
        String source,
        boolean pinned,
        String expiresAt,
        String supersededBy
) {
    public MemoryEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(timestamp, "timestamp");
        if (id.isBlank() || content.isBlank() || !(scope.equals("global") || scope.equals("project"))) {
            throw new IllegalArgumentException("记忆标识、内容或范围无效");
        }
        Instant.parse(timestamp);
        if (expiresAt != null) Instant.parse(expiresAt);
    }

    public MemoryEntry(String id, String content, String scope, String timestamp, String source) {
        this(id, content, scope, timestamp, source, false, null, null);
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
