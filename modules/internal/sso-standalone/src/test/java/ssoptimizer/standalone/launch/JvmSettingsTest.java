package ssoptimizer.standalone.launch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JvmSettingsTest {
    @TempDir Path temp;

    @Test void parsesLegacyPropertiesAndExplicitArguments() throws Exception {
        GameInstall game = game();
        Path instance = Files.createDirectory(temp.resolve("实例 一"));
        Path old = instance.resolve("jvm.properties");
        String legacy = "heap.minMiB=96\nheap.maxMiB=9000\nstack.KiB=384\nssoptimizer.econ.advance.parallel=false\nmod.custom=中文 值\n";
        Files.writeString(old, legacy);
        var migrated = JvmSettings.read(game, instance);
        assertTrue(migrated.vm().containsAll(List.of("-Xms96m", "-Xmx9000m", "-Xss384k", "-Dmod.custom=中文 值", "-Dssoptimizer.econ.advance.parallel=false")));
        assertFalse(migrated.vm().contains("-Dssoptimizer.econ.advance.parallel=true"));
        Path file = instance.resolve("jvm.args");
        Files.writeString(file, "\ufeff # 测试\r\n-Xms700m\r\n-Dmod.debug=false\r\n-Dfoo=含 空格\r\n--\r\n-skip\r\nship name\r\n", StandardCharsets.UTF_8);
        var custom = JvmSettings.read(game, instance);
        assertEquals(List.of("-Xms700m", "-Dmod.debug=false", "-Dfoo=含 空格"), custom.vm());
        assertEquals(List.of("-skip", "ship name"), custom.game());
    }

    @Test void directiveTokenUsesGamePathsInPriorityOrder() throws Exception {
        GameInstall game = game();
        Path instance = Files.createDirectory(temp.resolve("实例"));
        Path fallback = instance.resolve("compiler-directives.json");
        var first = JvmSettings.read(game, instance);
        assertTrue(first.vm().contains("-XX:CompilerDirectivesFile=" + fallback));
        Path atRoot = Files.writeString(game.root().resolve("compiler_directives.txt"), "root");
        assertTrue(JvmSettings.read(game, instance).vm().contains("-XX:CompilerDirectivesFile=" + atRoot));
        Path atCore = Files.writeString(game.core().resolve("compiler_directives.txt"), "core");
        assertTrue(JvmSettings.read(game, instance).vm().contains("-XX:CompilerDirectivesFile=" + atCore));
    }

    @Test void invalidHostOverrideReportsTheLine() throws Exception {
        GameInstall game = game();
        Path instance = Files.createDirectory(temp.resolve("实例"));
        Path file = JvmSettings.configure(game, instance);
        for (String invalid : List.of("-javaagent:other.jar", "-Dssoptimizer.standalone.plan=other", "-cp", "@other.txt", "-Dcom.fs.starfarer.settings.paths.saves=other")) {
            String text = "# 保留\n" + invalid + "\n";
            Files.writeString(file, text);
            assertTrue(assertThrows(java.io.IOException.class, () -> JvmSettings.read(game, instance)).getMessage().contains(":2"));
        }
    }

    private GameInstall game() throws java.io.IOException {
        Path root = Files.createDirectory(temp.resolve("game"));
        return new GameInstall(root, Files.createDirectory(root.resolve("starsector-core")), "windows-en-rc8", List.of());
    }
}
