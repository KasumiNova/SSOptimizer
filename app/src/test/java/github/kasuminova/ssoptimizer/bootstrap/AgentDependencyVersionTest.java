package github.kasuminova.ssoptimizer.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentDependencyVersionTest {
    @Test
    void buildScriptDeclaresLatestBytecodeLibraries() throws Exception {
        Path buildScript = Path.of(System.getProperty("project.rootDir"), "app", "build.gradle.kts");
        String content = Files.readString(buildScript);
        assertTrue(content.contains("org.ow2.asm:asm:9.9.1"), "ASM must be upgraded to 9.9.1");
        assertTrue(content.contains("org.ow2.asm:asm-tree:9.9.1"), "ASM Tree must be upgraded to 9.9.1");
        assertTrue(content.contains("net.fabricmc:sponge-mixin:0.15.4+mixin.0.8.7"), "Mixin must use latest compatible Fabric artifact");
    }
}
