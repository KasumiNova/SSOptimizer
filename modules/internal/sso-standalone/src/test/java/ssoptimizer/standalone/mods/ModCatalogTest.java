package ssoptimizer.standalone.mods;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        for (String json : List.of("{}", "{enabledMods:'a'}", "{enabledMods:['a','a']}", "{enabledMods:[,]}",
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

    @Test void resolvesManifestAndModNamespacesAndRejectsContradictions() throws IOException {
        Path m = mod("mixed", "a", "A", "\"jars\":[\"n.jar\",\"o.jar\"],");
        jar(m.resolve("n.jar"), "named", Map.of());
        jar(m.resolve("o.jar"), null, Map.of());
        enabled("'a'");
        assertEquals(List.of(true, false), scan().stream().map(ModCatalog.Archive::named).toList());
        mod("mixed", "a", "A", "\"ssoptimizerNamespace\":\"obf\",\"jars\":[\"n.jar\"],");
        assertThrows(IOException.class, this::scan);
        mod("mixed", "a", "A", "\"ssoptimizerNamespace\":\"named\",\"jars\":[\"o.jar\"],");
        assertTrue(scan().getFirst().named());
    }

    @Test void matchesPayloadCopiesAndRejectsWrongOwnerOrVersion() throws IOException {
        Path m = mod("installed", "ssoptimizer", "Optimizer", "\"jars\":[\"self.jar\"],");
        Path local = Files.copy(payload, m.resolve("self.jar"));
        enabled("'ssoptimizer'");
        assertEquals(List.of(new ModCatalog.Archive("ssoptimizer", local, true)), scan());
        mod("installed", "another", "Another", "\"jars\":[\"self.jar\"],");
        enabled("'another'");
        assertThrows(IOException.class, this::scan);
        mod("installed", "ssoptimizer", "Optimizer", "\"jars\":[\"self.jar\"],");
        jar(local, null, Map.of("other-version", new byte[0]));
        enabled("'ssoptimizer'");
        assertThrows(IOException.class, this::scan);
    }

    @Test void checksOnlyRequiredDependenciesAndActiveConflicts() throws IOException {
        enabled("'game'");
        mod("game", "game", "Game", "\"dependencies\":[{\"id\":\"NanoForge\",\"optional\":true},{\"id\":\"other\",\"required\":false}],"
                + "\"optionalDependencies\":[{\"id\":\"nanoforge\"}],\"conflicts\":[\"absent\"],");
        assertTrue(scan().isEmpty());
        for (String metadata : List.of("\"dependencies\":[{\"id\":\"nanoforge\"}],", "\"dependencies\":[{\"id\":\"absent\"}],",
                "\"dependencies\":[{\"id\":\"none\",\"optional\":true,\"required\":true}],", "\"customClassLoader\":\"example.Loader\",")) {
            mod("game", "game", "Game", metadata);
            assertThrows(IOException.class, this::scan, metadata);
        }
        mod("mod2", "second", "Second", "");
        enabled("'game','second'");
        mod("game", "game", "Game", "\"conflicts\":[{\"id\":\"second\"}],");
        assertThrows(IOException.class, this::scan);
    }

    @Test void distinguishesGameEntrypointLinkageFromOptionalIntegrationAndUtilityLoaders() throws IOException {
        enabled("'game'");
        Path m = mod("game", "game", "Game", "\"modPlugin\":\"game.Entry\",\"jars\":[\"mod.jar\"],");
        byte[] bridge = klass("game.Integration", "io.github.nanoforged.core.OptionalBase");
        byte[] loader = klass("game.HelperLoader", "java.net.URLClassLoader");
        jar(m.resolve("mod.jar"), null, Map.of("game/HelperLoader.class", loader, "game/Integration.class", bridge,
                "game/Entry.class", klass("game.Entry", "java.lang.Object")));
        assertEquals(1, scan().size());
        mod("game", "game", "Game", "\"modPlugin\":\"game.Integration\",\"jars\":[\"mod.jar\"],");
        assertThrows(IOException.class, this::scan);
    }

    @Test void rejectsExternalCoremodsButDoesNotActivateOrdinaryJarsOrDisabledMods() throws IOException {
        Path host = Files.createDirectory(mods.resolve("coremods"));
        jar(host.resolve("library.jar"), null, Map.of());
        Path off = mod("off", "off", "Off", "\"jars\":[\"plugin.jar\"],");
        var descriptor = Map.of("coremod.toml", new byte[0]);
        jar(off.resolve("plugin.jar"), null, descriptor);
        assertTrue(scan().isEmpty());
        enabled("'off'");
        assertThrows(IOException.class, this::scan);
        enabled("");
        Path external = jar(host.resolve("external.jar"), null, descriptor);
        assertEquals(List.of(external), ModCatalog.externalCoremods(mods));
        assertTrue(assertThrows(IOException.class, this::scan).getMessage().contains(external.toString()));
    }

    @Test void rejectsRepeatedArchiveAndPathsOutsideModRoot() throws IOException {
        Path m = mod("game", "game", "A", "\"jars\":[\"base.jar\",\"./base.jar\"],");
        jar(m.resolve("base.jar"), null, Map.of());
        enabled("'game'");
        assertThrows(IOException.class, this::scan);
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

    @Test @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void parentAfterSymlinkCannotSelectAnotherArchiveInsideTheSameMod() throws IOException {
        Path dir = mod("game", "case", "Case", "\"jars\":[\"link/../X.jar\"],");
        Path child = Files.createDirectories(dir.resolve("libs/sub"));
        Files.createSymbolicLink(dir.resolve("link"), child);
        Path actual = jar(dir.resolve("libs/X.jar"), null, Map.of());
        Path normalized = jar(dir.resolve("X.jar"), null, Map.of());
        enabled("'case'");
        String message = assertThrows(IOException.class, this::scan).getMessage();
        assertTrue(message.contains(actual.toString()) && message.contains(normalized.toString()), message);
        mod("game", "case", "Case", "\"jars\":[\"libs/../libs/X.jar\"],");
        assertEquals(actual, scan().getFirst().path());
    }

    @Test void missingFileKeyDistinguishesCopiesFromHardLinksInTheSameSizeBucket() throws IOException {
        Path a = Files.write(root.resolve("one.jar"), new byte[]{1});
        Path copy = Files.copy(a, root.resolve("copy.jar"));
        Path link = Files.createLink(root.resolve("two.jar"), a);
        var original = new ModCatalog.Archive("one", a, false);
        var distinct = new ModCatalog.Archive("copy", copy, false);
        var duplicate = new ModCatalog.Archive("two", link, false);
        for (int mode = 0; mode < 3; mode++) {
            var index = new ModCatalog.ArchiveIdentities();
            index.add(original, mode == 1 ? "one" : null, Files.size(a));
            index.add(distinct, null, Files.size(copy));
            Object linkKey = mode == 2 ? "one" : null;
            assertThrows(IOException.class, () -> index.add(duplicate, linkKey, Files.size(link)));
        }
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

    @Test @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void foldedSymlinkCannotEscapeMod() throws IOException {
        Path dir = mod("game", "case", "Case", "\"jars\":[\"linked/payload.jar\"],");
        Files.createSymbolicLink(dir.resolve("Linked"), root);
        enabled("'case'");
        assertThrows(IOException.class, this::scan);
        mod("game", "case", "Case", "\"jars\":[\"alias.jar\"],");
        Files.createSymbolicLink(dir.resolve("Alias.jar"), payload);
        assertThrows(IOException.class, this::scan);
    }

    @Test void selectsPrivateCoreByOrdinalFileNameAndKeysCacheByAllMountedOrPrivateBytes() throws IOException {
        Path cache = Files.createDirectory(root.resolve("cache"));
        Path jars = Files.createDirectories(mods.resolve("AI Tweaks/jars"));
        Path launcher = jars.resolve("aitweaks-launcher.jar");
        Path named = jar(root.resolve("0016-mod.jar"), "named", Map.of(
                "com/genir/aitweaks/launcher/loading/CoreLoaderManager$BootstrapLoader.class", new byte[]{1}));
        Files.copy(named, launcher);
        var owner = new ModCatalog.Archive("aitweaks", launcher, false);
        String type = "com/genir/aitweaks/core/Plugin.class";
        jar(jars.resolve("aitweaks-core.jar"), null, Map.of(type, new byte[]{1}));
        jar(jars.resolve("aitweaks-core-dev-B.jar"), null, Map.of(type, new byte[]{2}));
        Path latest = jar(jars.resolve("aitweaks-core-dev-a.jar"), null, Map.of(type, new byte[]{3}));
        Path asm = jar(jars.resolve("asm-9.1.jar"), null, Map.of("org/objectweb/asm/ClassReader.class", new byte[]{1}));
        Path layout = ModArchiveLayout.prepare(cache, owner, named);
        assertEquals("aitweaks-launcher.jar", layout.getFileName().toString());
        assertEquals(-1L, Files.mismatch(named, layout));
        assertEquals(-1L, Files.mismatch(latest, layout.resolveSibling("aitweaks-core.jar")));
        assertEquals(layout, ModArchiveLayout.prepare(cache, owner, named));
        jar(asm, null, Map.of("org/objectweb/asm/ClassReader.class", new byte[]{2}));
        Path updated = ModArchiveLayout.prepare(cache, owner, named);
        assertNotEquals(layout, updated);
        jar(latest, null, Map.of(type, new byte[]{4}));
        Path newer = ModArchiveLayout.prepare(cache, owner, named);
        assertNotEquals(updated, newer);
        Files.write(newer.resolveSibling("aitweaks-core.jar"), new byte[]{99});
        assertThrows(IOException.class, () -> ModArchiveLayout.prepare(cache, owner, named));
    }

    private List<ModCatalog.Archive> scan() throws IOException { return ModCatalog.scan(mods, payload); }

    private void enabled(String elements) throws IOException {
        Files.writeString(mods.resolve("enabled_mods.json"), "{\"enabledMods\":[" + elements + "]}");
    }

    private Path mod(String dir, String id, String name, String fields) throws IOException {
        Path result = Files.createDirectories(mods.resolve(dir));
        Files.writeString(result.resolve("mod_info.json"), "{" + fields + "\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"description\":\"\"}");
        return result;
    }

    private static byte[] klass(String name, String parent) {
        return ClassFile.of().build(ClassDesc.of(name), c -> c.withSuperclass(ClassDesc.of(parent)));
    }

    private static Path jar(Path path, String namespace, Map<String, byte[]> entries) throws IOException {
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (namespace != null) mf.getMainAttributes().putValue("SSOptimizer-Namespace", namespace);
        try (var out = new JarOutputStream(Files.newOutputStream(path), mf)) {
            for (var item : entries.entrySet()) {
                out.putNextEntry(new JarEntry(item.getKey()));
                out.write(item.getValue());
                out.closeEntry();
            }
        }
        return path;
    }
}
