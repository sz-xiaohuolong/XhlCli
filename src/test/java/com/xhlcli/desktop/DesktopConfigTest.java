package com.xhlcli.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DesktopConfigTest {
    @TempDir Path home;
    @Test void usesSavedDefaultAndDoesNotReturnKey() throws Exception {
        var config=new DesktopConfig(home,Map.of());
        var settings=DesktopJson.MAPPER.createObjectNode().put("provider","deepseek")
                .put("model","custom-model").put("baseUrl","http://localhost:18791").put("apiKey","local-secret");
        config.save(settings);
        try(var service=new DesktopService(home,Map.of(),event->{})) {
            var project=service.handle("project.add",DesktopJson.MAPPER.createObjectNode().put("path",home.toString()));
            var session=service.handle("session.create",DesktopJson.MAPPER.createObjectNode().put("projectId",project.path("id").asText()));
            assertEquals("deepseek:custom-model",session.path("model").asText());
            assertFalse(service.handle("config.get",DesktopJson.MAPPER.createObjectNode()).toString().contains("local-secret"));
        }
        settings.put("apiKey","");config.save(settings);
        assertEquals("local-secret",config.environment(home).get("DEEPSEEK_API_KEY"));
    }
    @Test void preservesUnrelatedConfigurationAndEnvironmentPriority() throws Exception {
        Path dir=Files.createDirectories(home.resolve(".xhlcli"));
        Files.writeString(dir.resolve("config.json"),"{\"maxIterations\":17,\"providers\":{\"openai\":{\"apiKey\":\"saved-key\"}}}");
        var config=new DesktopConfig(home,Map.of("OPENAI_API_KEY","environment-key"));
        config.save(DesktopJson.MAPPER.createObjectNode().put("provider","openai").put("model","custom-openai")
                .put("baseUrl","http://localhost:18791").put("apiKey","changed-key"));
        assertEquals("environment-key",config.environment(home).get("OPENAI_API_KEY"));
        assertEquals(17,DesktopJson.MAPPER.readTree(dir.resolve("config.json").toFile()).path("maxIterations").asInt());
    }
}
