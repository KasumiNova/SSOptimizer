package github.kasuminova.ssoptimizer;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchConfigAgentArgTest {
    @Test
    void launchConfigContainsJavaAgent() throws Exception {
        Path config = Path.of(System.getProperty("project.rootDir"), "launch-config.json");
        String content = Files.readString(config);
        assertTrue(content.contains("-javaagent:./mods/ssoptimizer/jars/SSOptimizer.jar"));
        assertTrue(content.contains("-Dssoptimizer.font.ttf.enable=true"));
        assertTrue(content.contains("-Dlog4j.configuration=file:./log4j.properties"));
    }
}
