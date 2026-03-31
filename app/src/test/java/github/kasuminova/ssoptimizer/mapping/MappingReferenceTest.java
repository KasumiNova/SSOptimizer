package github.kasuminova.ssoptimizer.mapping;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MappingReferenceTest {
    @Test
    void referenceNoteExists() {
        Path doc = Path.of(System.getProperty("project.rootDir"), ".github", "superpower", "brainstorm", "2026-03-28-bytecode-injection-hybrid-weaver-design.md");
        assertTrue(Files.exists(doc));
    }
}
