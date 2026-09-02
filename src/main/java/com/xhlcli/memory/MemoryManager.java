package com.xhlcli.memory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class MemoryManager {
    private final LongTermMemory globalMemory;
    private final LongTermMemory projectMemory;

    public MemoryManager(Path globalStorageDir, Path projectStorageDir) {
        this.globalMemory = new LongTermMemory(globalStorageDir.resolve("long_term.jsonl"));
        this.projectMemory = new LongTermMemory(projectStorageDir.resolve("long_term.jsonl"));
    }

    public void saveGlobal(String content, String source) {
        globalMemory.save(MemoryEntry.create(content, "global", source));
    }

    public void saveProject(String content, String source) {
        projectMemory.save(MemoryEntry.create(content, "project", source));
    }

    public List<MemoryEntry> loadAll() {
        List<MemoryEntry> all = new ArrayList<>();
        all.addAll(globalMemory.loadAll());
        all.addAll(projectMemory.loadAll());
        all.sort(Comparator.comparing(MemoryEntry::timestamp));
        return all;
    }

    public List<MemoryEntry> search(String query) {
        // 简易的基于内容的搜索
        String lowerQuery = query.toLowerCase();
        return loadAll().stream()
                .filter(e -> e.content().toLowerCase().contains(lowerQuery))
                .collect(Collectors.toList());
    }

    public boolean delete(String id) {
        boolean deletedFromGlobal = globalMemory.delete(id);
        boolean deletedFromProject = projectMemory.delete(id);
        return deletedFromGlobal || deletedFromProject;
    }

    public void clearAll() {
        globalMemory.clear();
        projectMemory.clear();
    }
}
