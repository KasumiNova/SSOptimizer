package ssoptimizer.standalone.host;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class DeclaredSourceTest {
    @TempDir Path root;

    @Test void resolvesDeclaredSpellingAndTracksOriginalSource() throws Exception {
        var p = new Properties();
        Path source = jar("Frontier Archive.jar"), alias = root.resolve("frontier archive.jar"), named = jar("Named Archive.jar");
        p.setProperty("game.count", "1");
        Path game = jar("game.jar"), originalGame = jar("original game.jar");
        p.setProperty("game.0", game.toString());
        p.setProperty("game.source.0", originalGame.toString());
        p.setProperty("mod.count", "1");
        p.setProperty("enabled.count", "0");
        p.setProperty("library.count", "0");
        p.setProperty("mod.0", named.toString());
        p.setProperty("mod.source.0", source.toString());
        p.setProperty("mod.declared.0", alias.toString());
        LaunchPlan plan = new LaunchPlan(p);
        assertEquals(originalGame.toRealPath(), plan.origins.get(game.toRealPath()));
        assertEquals(game.toRealPath(), plan.namedSource(originalGame));
        assertEquals(named.toRealPath(), plan.namedSource(source));
        assertEquals(named.toRealPath(), plan.namedSource(alias));
        assertNull(plan.namedSource(root.resolve("unregistered.jar")));
        assertEquals(source.toRealPath(), plan.origins.get(named.toRealPath()));
    }

    @Test @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void existingSourceResolvesSymlinksBeforeParentComponents() throws IOException {
        Path nested = Files.createDirectories(root.resolve("nested/sub"));
        Files.createSymbolicLink(root.resolve("link"), nested);
        var p = new Properties();
        p.setProperty("game.count", "1");
        p.setProperty("game.0", jar("game.jar").toString());
        p.setProperty("enabled.count", "0");
        p.setProperty("library.count", "0");
        p.setProperty("mod.count", "2");
        Path outer = jar("outer-named.jar"), inner = jar("inner-named.jar");
        p.setProperty("mod.0", outer.toString());
        p.setProperty("mod.source.0", jar("x.jar").toString());
        p.setProperty("mod.1", inner.toString());
        p.setProperty("mod.source.1", jar("nested/x.jar").toString());
        LaunchPlan plan = new LaunchPlan(p);
        assertEquals(inner.toRealPath(), plan.namedSource(root.resolve("link/../x.jar")));
        assertEquals(outer.toRealPath(), plan.namedSource(root.resolve("x.jar")));
    }

    private Path jar(String name) throws IOException {
        return Files.createFile(root.resolve(name));
    }
}
