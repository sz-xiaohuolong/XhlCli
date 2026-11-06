package com.xhlcli.desktop;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;

final class DesktopJson {
    static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new SimpleModule()
            .addSerializer(Instant.class, new JsonSerializer<Instant>() {
                @Override public void serialize(Instant value, JsonGenerator gen, SerializerProvider provider) throws IOException {
                    gen.writeString(value.toString());
                }
            }));
    private DesktopJson() {}
    static void writePrivate(Path file, Object value) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".desktop-", ".tmp");
        try {
            try { Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------")); }
            catch (UnsupportedOperationException ignored) {}
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
