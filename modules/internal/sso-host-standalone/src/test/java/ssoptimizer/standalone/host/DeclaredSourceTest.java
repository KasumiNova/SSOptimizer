package ssoptimizer.standalone.host;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class DeclaredSourceTest {
    @TempDir Path root;

    @Test void resolvesOnlyTheRecordedSpellingsAndChecksThatThePhysicalJarStillExists() throws IOException {
        var p = new Properties();
        Path source = jar("Frontier.jar"), alias = root.resolve("frontier.jar"), named = jar("named.jar");
        p.setProperty("game.count", "1");
        p.setProperty("game.0", jar("game.jar").toString());
        p.setProperty("mod.count", "1");
        p.setProperty("enabled.count", "0");
        p.setProperty("library.count", "0");
        p.setProperty("mod.0", named.toString());
        p.setProperty("mod.source.0", source.toString());
        p.setProperty("mod.declared.0", alias.toString());
        LaunchPlan plan = new LaunchPlan(p);
        assertEquals(named.toRealPath(), plan.namedSource(source));
        assertEquals(named.toRealPath(), plan.namedSource(alias));
        assertNull(plan.namedSource(root.resolve("unregistered.jar")));
        p.setProperty("mod.declared.0", root.resolve("different.jar").toString());
        assertThrows(IOException.class, () -> new LaunchPlan(p));
        Files.delete(source);
        assertThrows(IOException.class, () -> plan.namedSource(alias));
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
        Path file = root.resolve(name);
        try (var stream = new JarOutputStream(Files.newOutputStream(file))) { stream.finish(); }
        return file;
    }
}
