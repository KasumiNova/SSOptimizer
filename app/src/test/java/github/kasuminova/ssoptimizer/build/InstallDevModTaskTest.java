package github.kasuminova.ssoptimizer.build;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallDevModTaskTest {
    @Test
    void rootBuildScriptDeclaresInstallDevModTask() throws Exception {
        Path buildScript = Path.of(System.getProperty("project.rootDir"), "build.gradle.kts");
        String content = Files.readString(buildScript);

        assertTrue(content.contains("tasks.register<Copy>(\"installDevMod\")"), "installDevMod task must be registered");
        assertTrue(content.contains("dependsOn(\":app:jar\")"), "installDevMod must depend on app jar");
        assertTrue(content.contains("dependsOn(\":native:assemble\")"), "installDevMod must depend on native assemble");
        assertTrue(content.contains("starsector.gameDir"), "installDevMod must resolve the Starsector game directory");
    }
}