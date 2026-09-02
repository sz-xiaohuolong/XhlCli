package com.xhlcli.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryManagerTest {

    @Test
    void testMemoryManagerScopes(@TempDir Path globalDir, @TempDir Path projectDir) {
        MemoryManager manager = new MemoryManager(globalDir, projectDir);
        
        manager.saveGlobal("Global rules", "user");
        manager.saveProject("Project specific config", "user");
        
        List<MemoryEntry> all = manager.loadAll();
        assertEquals(2, all.size());
        
        List<MemoryEntry> searchResult = manager.search("project");
        assertEquals(1, searchResult.size());
        assertEquals("Project specific config", searchResult.get(0).content());
        
        assertTrue(manager.delete(searchResult.get(0).id()));
        assertEquals(1, manager.loadAll().size());
        
        manager.clearAll();
        assertTrue(manager.loadAll().isEmpty());
    }
}
