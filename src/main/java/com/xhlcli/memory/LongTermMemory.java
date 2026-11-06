package com.xhlcli.memory;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.NoSuchFileException;
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
        return new ArrayList<>(read().entries());
    }

    /** 保留逐行诊断；文件级故障不返回不完整快照。 */
    public ReadResult read() {
        List<MemoryEntry> entries = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(memoryFile, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) continue;
                try {
                    MemoryEntry entry = mapper.readValue(line, MemoryEntry.class);
                    if (entry == null) throw new IllegalArgumentException("空记录");
                    entries.add(entry);
                } catch (Exception e) {
                    // 不将原始内容或异常消息写入诊断，避免泄漏记忆数据。
                    diagnostics.add("第 " + lineNumber + " 行记忆无效，已跳过");
                }
            }
        } catch (NoSuchFileException e) {
            return new ReadResult(List.of(), List.of(), false);
        } catch (IOException | SecurityException e) {
            return new ReadResult(List.of(), List.of("记忆存储读取失败"), true);
        }
        return new ReadResult(entries, diagnostics, false);
    }

    public record ReadResult(List<MemoryEntry> entries, List<String> diagnostics, boolean readFailed) {
        public ReadResult {
            entries = List.copyOf(entries);
            diagnostics = List.copyOf(diagnostics);
        }
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
