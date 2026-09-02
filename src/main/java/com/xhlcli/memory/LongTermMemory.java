package com.xhlcli.memory;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

public class LongTermMemory {
    private final Path memoryFile;
    private final ObjectMapper mapper;

    public LongTermMemory(Path memoryFile) {
        this.memoryFile = memoryFile;
        this.mapper = new ObjectMapper();
    }

    public void save(MemoryEntry entry) {
        try {
            if (memoryFile.getParent() != null && !Files.exists(memoryFile.getParent())) {
                Files.createDirectories(memoryFile.getParent());
            }
            String json = mapper.writeValueAsString(entry);
            Files.writeString(memoryFile, json + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Warning: failed to save memory entry: " + e.getMessage());
        }
    }

    public List<MemoryEntry> loadAll() {
        List<MemoryEntry> entries = new ArrayList<>();
        if (!Files.exists(memoryFile)) {
            return entries;
        }
        try (BufferedReader reader = Files.newBufferedReader(memoryFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    MemoryEntry entry = mapper.readValue(line, MemoryEntry.class);
                    entries.add(entry);
                } catch (Exception e) {
                    // Ignore parse errors on single lines
                }
            }
        } catch (IOException e) {
            // Ignore
        }
        return entries;
    }

    public boolean delete(String id) {
        List<MemoryEntry> entries = loadAll();
        boolean removed = entries.removeIf(e -> e.id().equals(id));
        if (removed) {
            rewriteAll(entries);
        }
        return removed;
    }

    public void clear() {
        try {
            Files.deleteIfExists(memoryFile);
        } catch (IOException e) {
            // Ignore
        }
    }

    private void rewriteAll(List<MemoryEntry> entries) {
        try {
            if (memoryFile.getParent() != null && !Files.exists(memoryFile.getParent())) {
                Files.createDirectories(memoryFile.getParent());
            }
            try (BufferedWriter writer = Files.newBufferedWriter(memoryFile, StandardCharsets.UTF_8)) {
                for (MemoryEntry entry : entries) {
                    writer.write(mapper.writeValueAsString(entry));
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            // Ignore
        }
    }
}
