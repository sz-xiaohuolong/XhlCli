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

    /** 全局和当前项目由构造时绑定，查询不能扩大可见范围。 */
    public MemoryRecallResult recall(MemoryQuery query) {
        var global = globalMemory.read();
        var project = projectMemory.read();
        List<String> diagnostics = new ArrayList<>();
        global.diagnostics().forEach(d -> diagnostics.add("global: " + d));
        project.diagnostics().forEach(d -> diagnostics.add("project: " + d));
        if (global.readFailed() || project.readFailed()) {
            return MemoryRecallResult.of(List.of(), true, false, diagnostics);
        }
        List<MemoryEntry> candidates = new ArrayList<>();
        for (var entry : global.entries()) {
            if (entry.scope().equals("global")) candidates.add(entry);
            else diagnostics.add("global: 记录范围不匹配，已跳过");
        }
        for (var entry : project.entries()) {
            if (entry.scope().equals("project")) candidates.add(entry);
            else diagnostics.add("project: 记录范围不匹配，已跳过");
        }
        var result = new MemoryRetriever().recall(candidates, query);
        return MemoryRecallResult.of(result.selected(), false, result.budgetLimited(), diagnostics);
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
