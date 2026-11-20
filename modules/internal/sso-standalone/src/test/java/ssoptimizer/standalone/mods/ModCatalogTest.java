package ssoptimizer.standalone.mods;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModCatalogTest {
    @TempDir Path root;
    Path mods;
    Path payload;

    @BeforeEach void setup() throws IOException {
        mods = Files.createDirectory(root.resolve("mods"));
        payload = jar(root.resolve("payload.jar"), null, Map.of("payload.txt", new byte[]{1}));
    }

    @Test void reversesGameSortThenPreservesJarOrderAndResourceOnlyDependencies() throws IOException {
        Path z = mod("highest", "z", "Z", "\"sortString\":\"M\",\"jars\":[\"1.jar\",\"2.jar\"],");
        Path a = mod("other", "a", "A", "\"sortString\":\"M\",\"jars\":[\"a.jar\"],");
        Path b = mod("first", "b", "B", "\"sortString\":\"\",\"dependencies\":[{\"id\":\"data \"}],\"jars\":[\"b.jar\"],");
        mod("data", "data ", "DATA", "");
        mod("off", "off", "OFF", "\"jars\":[\"missing.jar\"],");
        for (Path p : List.of(z.resolve("1.jar"), z.resolve("2.jar"), a.resolve("a.jar"), b.resolve("b.jar"))) jar(p, null, Map.of());
        enabled("'z','data ','b','a'");
        assertEquals(List.of(z.resolve("1.jar"), z.resolve("2.jar"), a.resolve("a.jar"), b.resolve("b.jar")),
                scan().stream().map(ModCatalog.Archive::path).toList());
    }

    @Test void preservesGameStringsAndReadsNestedTrailingCommas() throws IOException {
        Files.writeString(mods.resolve("enabled_mods.json"), """
                { # metadata list
                  "enabledMods": ["quoted\\\"#mod", 'trailing space ', /* end */ ],
                }
                """);
        assertEquals(List.of("quoted\"#mod", "trailing space "), ModCatalog.enabledIds(mods));
        Path dir = mod("game", "game", "Game", "\"version\":{\"major\":'1',\"minor\":'2',},\"dependencies\":[],");
        assertEquals("game", ModCatalog.modId(dir));
    }

    @Test void rejectsMalformedListsAndDuplicateKeys() throws IOException {
        assertTrue(scan().isEmpty());
        for (String json : List.of("{}", "{enabledMods:'a'}", "{enabledMods:[,]}",
                "{enabledMods:['a',,'b']}", "{enabledMods:[], enabledMods:[]}", "{enabledMods:[]} {}", "{enabledMods:[")) {
            Files.writeString(mods.resolve("enabled_mods.json"), json);
            assertThrows(IOException.class, this::scan, json);
        }
    }

    @Test void rejectsMissingAndAmbiguousEnabledIds() throws IOException {
        enabled("'dup'");
        assertTrue(assertThrows(IOException.class, this::scan).getMessage().contains("dup"));
        Path one = mod("one", "dup", "A", "");
        Path two = mod("two", "dup", "B", "");
        String message = assertThrows(IOException.class, this::scan).getMessage();
        assertTrue(message.contains(one.toString()) && message.contains(two.toString()), message);
    }

    @Test void resolvesDuplicatePathsOnceAndRejectsPathsOutsideModRoot() throws IOException {
        Path m = mod("game", "game", "A", "\"jars\":[\"base.jar\",\"./base.jar\"],");
        jar(m.resolve("base.jar"), null, Map.of());
        enabled("'game'");
        assertEquals(List.of(m.resolve("base.jar")), scan().stream().map(ModCatalog.Archive::path).toList());
        mod("game", "game", "A", "\"jars\":[\"../../payload.jar\"],");
        assertThrows(IOException.class, this::scan);
    }

    @Test void resolvesEachJarComponentAndKeepsItsDeclaredAlias() throws IOException {
        Path dir = mod("game", "case", "Case", "\"jars\":[\"libs/../libs/inner/frontier.jar\"],");
        Path jars = Files.createDirectories(dir.resolve("Libs/Inner"));
        Path physical = jar(jars.resolve("Frontier.jar"), null, Map.of());
        enabled("'case'");
        var selected = scan().getFirst();
        assertTrue(Files.isSameFile(physical, selected.path()));
        assertEquals(dir.resolve("libs/inner/frontier.jar"), selected.declaredPath());
    }

    @Test void exactComponentWinsButFoldedFileAndDirectoryCollisionsFail() throws IOException {
        Path dir = mod("game", "case", "Case", "\"jars\":[\"Libs/Frontier.jar\"],");
        Path jars = Files.createDirectory(dir.resolve("Libs"));
        org.junit.jupiter.api.Assumptions.assumeFalse(Files.exists(dir.resolve("LIBS")));
        Files.createDirectory(dir.resolve("LIBS"));
        Path exact = jar(jars.resolve("Frontier.jar"), null, Map.of());
        jar(jars.resolve("FRONTIER.jar"), null, Map.of());
        enabled("'case'");
        assertEquals(exact, scan().getFirst().path());
        mod("game", "case", "Case", "\"jars\":[\"Libs/frontier.jar\"],");
        String file = assertThrows(IOException.class, this::scan).getMessage();
        assertTrue(file.contains("Frontier.jar") && file.contains("FRONTIER.jar"), file);
        mod("game", "case", "Case", "\"jars\":[\"libs/Frontier.jar\"],");
        String parent = assertThrows(IOException.class, this::scan).getMessage();
        assertTrue(parent.contains("LIBS") && parent.contains("Libs"), parent);
    }

    private List<ModCatalog.Archive> scan() throws IOException {
        return ModCatalog.scan(mods, payload, ModCatalog.enabledIds(mods));
    }

    private void enabled(String elements) throws IOException {
        Files.writeString(mods.resolve("enabled_mods.json"), "{\"enabledMods\":[" + elements + "]}");
    }

    private Path mod(String dir, String id, String name, String fields) throws IOException {
        Path result = Files.createDirectories(mods.resolve(dir));
        Files.writeString(result.resolve("mod_info.json"), "{" + fields + "\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"description\":\"\"}");
        return result;
    }

    private static Path jar(Path path, String namespace, Map<String, byte[]> entries) throws IOException {
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (namespace != null) mf.getMainAttributes().putValue("SSOptimizer-Namespace", namespace);
        return writeJar(path, mf, entries);
    }

    private static Path writeJar(Path path, Manifest manifest, Map<String, byte[]> entries) throws IOException {
        try (var out = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            for (var item : entries.entrySet()) {
                out.putNextEntry(new JarEntry(item.getKey()));
                out.write(item.getValue());
                out.closeEntry();
            }
        }
        return path;
    }
}
