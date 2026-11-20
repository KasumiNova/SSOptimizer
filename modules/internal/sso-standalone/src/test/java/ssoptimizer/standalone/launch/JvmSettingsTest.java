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

    @Test void parsesExplicitArguments() throws Exception {
        GameInstall game = game();
        Path work = Files.createDirectory(temp.resolve("工作目录 一"));
        Path file = work.resolve("jvm.args");
        Files.writeString(file, "\ufeff # 测试\r\n-Xms700m\r\n-Dmod.debug=false\r\n-Dfoo=含 空格\r\n--\r\n-skip\r\nship name\r\n", StandardCharsets.UTF_8);
        var custom = JvmSettings.read(game, work);
        assertEquals(List.of("-Xms700m", "-Dmod.debug=false", "-Dfoo=含 空格"), custom.vm());
        assertEquals(List.of("-skip", "ship name"), custom.game());
    }

    @Test void directiveTokenUsesGamePathsInPriorityOrder() throws Exception {
        GameInstall game = game();
        Path work = Files.createDirectory(temp.resolve("工作目录"));
        Path fallback = work.resolve("compiler-directives.json");
        var first = JvmSettings.read(game, work);
        assertTrue(first.vm().contains("-XX:CompilerDirectivesFile=" + fallback));
        Path atRoot = Files.writeString(game.root().resolve("compiler_directives.txt"), "root");
        assertTrue(JvmSettings.read(game, work).vm().contains("-XX:CompilerDirectivesFile=" + atRoot));
        Path atCore = Files.writeString(game.core().resolve("compiler_directives.txt"), "core");
        assertTrue(JvmSettings.read(game, work).vm().contains("-XX:CompilerDirectivesFile=" + atCore));
    }

    private GameInstall game() throws java.io.IOException {
        Path root = Files.createDirectory(temp.resolve("game"));
        return new GameInstall(root, Files.createDirectory(root.resolve("starsector-core")), "windows-en-rc8", List.of());
    }
}
