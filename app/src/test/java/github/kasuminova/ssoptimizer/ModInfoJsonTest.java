package github.kasuminova.ssoptimizer;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModInfoJsonTest {
    @Test
    void modInfoJsonExists() {
        Path modInfo = Path.of(System.getProperty("project.rootDir"), "mod_info.json");
        assertTrue(Files.exists(modInfo), "mod_info.json must exist at project root");
    }

    @Test
    void modInfoJsonContainsRequiredFields() throws Exception {
        Path modInfo = Path.of(System.getProperty("project.rootDir"), "mod_info.json");
        String content = Files.readString(modInfo);
        assertAll(
                () -> assertTrue(content.contains("\"id\""), "must have id field"),
                () -> assertTrue(content.contains("\"name\""), "must have name field"),
                () -> assertTrue(content.contains("\"version\""), "must have version field"),
                () -> assertTrue(content.contains("\"gameVersion\""), "must have gameVersion field"),
                () -> assertTrue(content.contains("\"jars\""), "must have jars field"),
                () -> assertTrue(content.contains("\"modPlugin\""), "must have modPlugin field"),
                () -> assertTrue(content.contains("\"jars\": [\"jars/SSOptimizer.jar\"]"), "must point runtime jar to canonical SSOptimizer.jar")
        );
    }
}