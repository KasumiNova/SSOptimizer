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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test void distinguishesRequiredAndOptionalNanoForgeDependencies() throws IOException {
        enabled("'game'");
        mod("game", "game", "Game", "\"dependencies\":[{\"id\":\"NanoForge\",\"optional\":true},{\"id\":\"nanoforge\",\"required\":false}],");
        scan();
        mod("game", "game", "Game", "\"dependencies\":[{\"id\":\"nanoforge\"}],");
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

    @Test void malformedOptionalClassDoesNotHideOtherEntriesOrRejectAnOrdinaryMod() throws IOException {
        enabled("'game'");
        Path m = mod("game", "game", "Game", "\"modPlugin\":\"g.Entry\",\"jars\":[\"mod.jar\"],");
        var entries = new java.util.LinkedHashMap<String, byte[]>();
        byte[] entry = klass("g.Entry", "java.lang.Object");
        entries.put("g/Broken.class", java.util.Arrays.copyOf(entry, 12));
        entries.put("g/Misplaced.class", klass("g.Other", "java.lang.Object"));
        entries.put("g/Entry.class", entry);
        Path path = jar(m.resolve("mod.jar"), "named", entries);
        assertEquals(List.of(new ModCatalog.Archive("game", path, true)), scan());
        entries.put("g/Entry.class", klass("g.Entry", "io.github.nanoforged.core.Base"));
        jar(path, null, entries);
        assertTrue(assertThrows(IOException.class, this::scan).getMessage().contains("NanoForge"));
        entries.put("g/Entry.class", java.util.Arrays.copyOf(entry, 8));
        jar(path, null, entries);
        mod("game", "game", "Game", "\"modPlugin\":\"g.Entry\",\"jars\":[\"mod.jar\",\"provider.jar\"],");
        Path provider = jar(m.resolve("provider.jar"), "named", Map.of("g/Entry.class", entry));
        assertEquals(List.of(new ModCatalog.Archive("game", path, false),
                new ModCatalog.Archive("game", provider, true)), scan());
    }

    @Test void classCrcFailureDoesNotRejectTheArchiveButTruncatedCentralDirectoryDoes() throws IOException {
        enabled("'game'");
        Path m = mod("game", "game", "Game", "\"jars\":[\"mod.jar\"],");
        Path path = m.resolve("mod.jar");
        byte[] bytes = klass("g.Optional", "java.lang.Object");
        var crc = new java.util.zip.CRC32();
        crc.update(bytes);
        try (var out = new JarOutputStream(Files.newOutputStream(path))) {
            JarEntry optional = new JarEntry("g/Optional.class");
            optional.setMethod(JarEntry.STORED);
            optional.setSize(bytes.length);
            optional.setCrc(crc.getValue());
            out.putNextEntry(optional);
            out.write(bytes);
        }
        byte[] zip = Files.readAllBytes(path);
        var header = java.nio.ByteBuffer.wrap(zip).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int data = 30 + Short.toUnsignedInt(header.getShort(26)) + Short.toUnsignedInt(header.getShort(28));
        zip[data] ^= 1;
        Files.write(path, zip);
        assertEquals(List.of(new ModCatalog.Archive("game", path, false)), scan());
        Files.write(path, java.util.Arrays.copyOf(zip, data));
        assertThrows(IOException.class, this::scan);
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

    @Test void preservesRelativePrivateArchivesWithoutAddingThemToTheDeclaredClasspath() throws Exception {
        Path cache = Files.createDirectory(root.resolve("cache with spaces"));
        Path mod = mod("Mod With Spaces", "generic", "Generic", "\"jars\":[\"jars/entry archive.jar\",\"jars/other.jar\"],");
        Path jars = Files.createDirectories(mod.resolve("jars"));
        Path entry = jar(jars.resolve("entry archive.jar"), null, Map.of());
        Path other = jar(jars.resolve("other.jar"), null, Map.of());
        Path companion = jar(jars.resolve("private archive.jar"), null, Map.of("private.txt", new byte[]{1}));
        Files.writeString(jars.resolve("local settings.json"), "player data");
        Path nested = Files.createDirectories(jars.resolve("sub dir"));
        Path helper = jar(nested.resolve("manifest helper.jar"), null, Map.of("manifest.txt", new byte[]{2}));
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, "sub%20dir/manifest%20helper.jar");
        Path prepared = root.resolve("prepared.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(prepared), manifest)) {
            out.putNextEntry(new JarEntry("entry.txt"));
            out.write(3);
        }
        Path preparedOther = jar(root.resolve("prepared-other.jar"), "named", Map.of("other.txt", new byte[]{4}));
        enabled("'generic'");
        var archives = scan();
        var inputs = Map.of(archives.get(0), prepared, archives.get(1), preparedOther);
        Map<Path, Path> layout = ModArchiveLayout.prepare(cache, inputs);
        Path mounted = layout.get(entry);

        assertEquals(java.util.Set.of(entry, other), layout.keySet());
        assertEquals(entry.getFileName(), mounted.getFileName());
        assertEquals(mounted.getParent(), layout.get(other).getParent());
        assertEquals(-1L, Files.mismatch(preparedOther, layout.get(other)));
        assertEquals(-1L, Files.mismatch(companion, mounted.resolveSibling(companion.getFileName())));
        assertEquals(-1L, Files.mismatch(helper, mounted.getParent().resolve(jars.relativize(helper))));
        assertFalse(Files.exists(mounted.resolveSibling("local settings.json")));
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{mounted.toUri().toURL()}, null)) {
            assertNotNull(loader.getResource("manifest.txt"));
            assertNull(loader.getResource("private.txt"));
        }
        assertEquals(layout, ModArchiveLayout.prepare(cache, inputs));
        jar(companion, null, Map.of("private.txt", new byte[]{9}));
        Path updated = ModArchiveLayout.prepare(cache, inputs).get(entry);
        assertNotEquals(mounted, updated);
        Files.write(updated.resolveSibling(companion.getFileName()), new byte[]{99});
        assertThrows(IOException.class, () -> ModArchiveLayout.prepare(cache, inputs));
    }

    @Test void preservesDirectoryClassesAndResourcesThroughRecursiveManifests() throws Exception {
        Path cache = Files.createDirectory(root.resolve("directory cache"));
        Path mod = mod("layout", "layout", "Layout", "\"jars\":[\"entry.jar\"],");
        Path entry = jar(mod.resolve("entry.jar"), null, Map.of());
        Path libraries = Files.createDirectory(mod.resolve("libraries"));
        Path classes = Files.createDirectories(libraries.resolve("compiled classes/example"));
        Files.write(classes.resolve("DirectoryType.class"), klass("example.DirectoryType", "java.lang.Object"));
        Files.writeString(classes.resolve("message.txt"), "directory resource");
        Files.createDirectory(classes.resolve("empty"));
        Files.writeString(mod.resolve("player-settings.json"), "local settings");
        manifestJar(libraries.resolve("helper.jar"), "compiled%20classes/", Map.of());
        jar(libraries.resolve("private-helper.jar"), null, Map.of("private-helper.txt", new byte[]{1}));
        manifestJar(mod.resolve("private.jar"), "libraries/helper.jar libraries/private-helper.jar",
                Map.of("private.txt", new byte[]{2}));
        Path prepared = manifestJar(root.resolve("prepared-entry.jar"), "./ libraries/helper.jar", Map.of());
        enabled("'layout'");
        var inputs = Map.of(scan().getFirst(), prepared);
        Path layout = ModArchiveLayout.prepare(cache, inputs).get(entry);

        assertFalse(Files.exists(layout.resolveSibling("player-settings.json")));
        assertTrue(Files.isDirectory(layout.getParent().resolve("libraries/compiled classes/example/empty")));
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{layout.toUri().toURL()}, null)) {
            assertEquals(loader, loader.loadClass("example.DirectoryType").getClassLoader());
            assertNotNull(loader.getResource("example/message.txt"));
            assertNull(loader.getResource("private.txt"));
            assertNull(loader.getResource("private-helper.txt"));
        }
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{layout.resolveSibling("private.jar").toUri().toURL()}, null)) {
            assertEquals(loader, loader.loadClass("example.DirectoryType").getClassLoader());
            assertNotNull(loader.getResource("example/message.txt"));
            assertNotNull(loader.getResource("private-helper.txt"));
        }
        Files.writeString(classes.resolve("message.txt"), "updated resource");
        assertNotEquals(layout, ModArchiveLayout.prepare(cache, inputs).get(entry));
    }

    @Test void rootDirectoryClassPathResolvesLiveResourcesWithoutCopyingThem() throws Exception {
        Path cache = Files.createDirectory(root.resolve("root cache"));
        Path mod = mod("root with spaces", "layout", "Layout", "\"jars\":[\"entry.jar\"],");
        Path entry = jar(mod.resolve("entry.jar"), null, Map.of());
        Files.writeString(mod.resolve("resource.properties"), "figure=icon.png");
        Files.write(mod.resolve("icon.png"), new byte[]{1, 2});
        enabled("'layout'");
        var archive = scan().getFirst();
        for (String token : List.of("./", ".")) {
            Path prepared = manifestJar(root.resolve("prepared-root.jar"), token, Map.of());
            var inputs = Map.of(archive, prepared);
            Path layout = ModArchiveLayout.prepare(cache, inputs).get(entry);
            assertFalse(Files.exists(layout.resolveSibling("resource.properties")));
            assertEquals(layout, ModArchiveLayout.prepare(cache, inputs).get(entry));
            try (var loader = new java.net.URLClassLoader(new java.net.URL[]{layout.toUri().toURL()}, null)) {
                assertEquals(mod.resolve("resource.properties"), Path.of(loader.getResource("resource.properties").toURI()));
                assertEquals(mod.resolve("icon.png"), Path.of(loader.getResource("icon.png").toURI()));
            }
        }
    }

    @Test void signedPrivateJarRetainsItsSignatureAndRootDirectoryResources() throws Exception {
        Path cache = Files.createDirectory(root.resolve("signed cache"));
        Path mod = mod("signed mod", "layout", "Layout", "\"jars\":[\"entry.jar\"],");
        Path entry = jar(mod.resolve("entry.jar"), null, Map.of());
        Path companion = manifestJar(mod.resolve("signed companion.jar"), "./", Map.of(
                "example/PrivateClass.class", klass("example.PrivateClass", "java.lang.Object")));
        Files.writeString(mod.resolve("signed-resource.properties"), "live=true");
        Path keystore = root.resolve("fixture.p12");
        runJdkTool("keytool", "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-dname", "CN=Fixture",
                "-keystore", keystore.toString(), "-storepass", "changeit");
        runJdkTool("jarsigner", "-keystore", keystore.toString(), "-storepass", "changeit", companion.toString(), "fixture");
        enabled("'layout'");
        Path layout = ModArchiveLayout.prepare(cache, Map.of(scan().getFirst(), entry)).get(entry);
        Path cachedCompanion = layout.resolveSibling(companion.getFileName());
        assertEquals(-1L, Files.mismatch(companion, cachedCompanion));
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{cachedCompanion.toUri().toURL()}, null)) {
            assertNotNull(loader.loadClass("example.PrivateClass").getSigners());
            assertEquals("live=true", Files.readString(Path.of(loader.getResource("signed-resource.properties").toURI())));
        }
    }

    private static void runJdkTool(String name, String... arguments) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", name).toString());
        command.addAll(List.of(arguments));
        Process tool = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(tool.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, tool.waitFor(), output);
    }

    @Test void nestedSelfDirectoryRetainsResourcesAndUnexpectedCacheDirectoriesFailValidation() throws Exception {
        Path cache = Files.createDirectory(root.resolve("cache"));
        Path mod = mod("layout", "layout", "Layout", "\"jars\":[\"jars/entry.jar\"],");
        Path jars = Files.createDirectory(mod.resolve("jars"));
        Path entry = jar(jars.resolve("entry.jar"), null, Map.of());
        Files.writeString(jars.resolve("marker.properties"), "inside=true");
        Files.writeString(mod.resolve("settings.properties"), "outside=true");
        Path prepared = manifestJar(root.resolve("prepared.jar"), "./", Map.of());
        enabled("'layout'");
        var inputs = Map.of(scan().getFirst(), prepared);
        Path layout = ModArchiveLayout.prepare(cache, inputs).get(entry);
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{layout.toUri().toURL()}, null)) {
            assertEquals(layout.resolveSibling("marker.properties"), Path.of(loader.getResource("marker.properties").toURI()));
            assertNull(loader.getResource("settings.properties"));
        }
        Files.createDirectory(layout.resolveSibling("injected-empty-directory"));
        assertThrows(IOException.class, () -> ModArchiveLayout.prepare(cache, inputs));
    }

    @Test @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void directoryManifestLinksIncludeOnlyFilesInsideTheMod() throws Exception {
        Path cache = Files.createDirectory(root.resolve("cache"));
        Path mod = mod("layout", "layout", "Layout", "\"jars\":[\"entry.jar\"],");
        Path entry = jar(mod.resolve("entry.jar"), null, Map.of());
        Path classes = Files.createDirectory(mod.resolve("classes"));
        Path internal = Files.writeString(mod.resolve("internal.txt"), "inside");
        Path external = Files.writeString(root.resolve("external.txt"), "outside");
        Files.createSymbolicLink(classes.resolve("alias.txt"), internal);
        Files.createSymbolicLink(classes.resolve("external.txt"), external);
        Files.createSymbolicLink(classes.resolve("root-alias"), mod);
        Files.createSymbolicLink(classes.resolve("loop"), classes);
        Path prepared = manifestJar(root.resolve("prepared-entry.jar"), "classes/", Map.of());
        enabled("'layout'");
        Path layout = ModArchiveLayout.prepare(cache, Map.of(scan().getFirst(), prepared)).get(entry);
        Path copied = layout.getParent().resolve("classes");
        assertEquals("inside", Files.readString(copied.resolve("alias.txt")));
        assertFalse(Files.exists(copied.resolve("external.txt")));
        assertFalse(Files.exists(copied.resolve("root-alias")));
        assertFalse(Files.exists(copied.resolve("loop")));
    }

    private static Path manifestJar(Path path, String classPath, Map<String, byte[]> entries) throws IOException {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, classPath);
        return writeJar(path, manifest, entries);
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

    private static byte[] klass(String name, String parent) {
        return ClassFile.of().build(ClassDesc.of(name), c -> c.withSuperclass(ClassDesc.of(parent)));
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
