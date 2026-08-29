package com.xhlcli.tool.local.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class JavaCodeSearchEngineTest {
    @Test
    void testSearchEngine(@TempDir Path tempDir) throws Exception {
        JavaCodeSearchEngine engine = new JavaCodeSearchEngine();
        
        Path file1 = tempDir.resolve("test1.txt");
        Files.writeString(file1, "line1\nhello world\nline3\nHELLO WORLD\n");

        CodeSearchRequest req1 = new CodeSearchRequest(
                "hello", tempDir, tempDir, null, false, true, 0, 50, 20
        );
        CodeSearchResult res1 = engine.search(req1);
        assertEquals(1, res1.matches().size());
        assertEquals(2, res1.matches().get(0).lineNumber());

        CodeSearchRequest req2 = new CodeSearchRequest(
                "hello", tempDir, tempDir, null, false, false, 0, 50, 20
        );
        CodeSearchResult res2 = engine.search(req2);
        assertEquals(2, res2.matches().size());

        CodeSearchRequest req3 = new CodeSearchRequest(
                "hello", tempDir, tempDir, null, false, false, 1, 50, 20
        );
        CodeSearchResult res3 = engine.search(req3);
        assertEquals(3, res3.matches().get(0).context().size());
    }
}
