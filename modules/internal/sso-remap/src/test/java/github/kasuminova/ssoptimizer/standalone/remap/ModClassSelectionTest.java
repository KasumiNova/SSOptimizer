package github.kasuminova.ssoptimizer.standalone.remap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.tree.IntInsnNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static github.kasuminova.ssoptimizer.standalone.remap.ArchivePreparationTest.node;
import static github.kasuminova.ssoptimizer.standalone.remap.ArchivePreparationTest.type;
import static org.junit.jupiter.api.Assertions.*;

class ModClassSelectionTest {
    @TempDir Path temp;

    @Test
    void gameDefinitionWinsAcrossNamespacesAndLeavesModResourcesAndSourcesIntact() throws Exception {
        for (boolean namedGame : List.of(false, true)) {
            for (boolean namedMod : List.of(false, true)) {
                String gameName = namedGame ? "api/Game" : "a/A";
                String modName = namedMod ? "api/Game" : "a/A";
                Path game = jar("game.jar", Map.of(gameName + ".class", definition(gameName, 3),
                        "META-INF/MANIFEST.MF", manifest(namedGame, false)));
                Path mod = jar("mod.jar", Map.of(modName + ".class", definition(modName, 9),
                        "pack/Own.class", type("pack/Own", modName, false, 0), "data/asset", new byte[]{4, 7}));
                String originalGame = Digests.file(game), originalMod = Digests.file(mod);
                var prepared = prepare(game, List.of(mod), namedMod ? Set.of(mod) : Set.of());
                assertEquals(3, value(prepared.get(game), "api/Game.class"));
                assertNull(entry(prepared.get(mod), "api/Game.class"));
                assertNull(entry(prepared.get(mod), "a/A.class"));
                assertEquals("api/Game", node(entry(prepared.get(mod), "pack/Own.class")).superName);
                assertArrayEquals(new byte[]{4, 7}, entry(prepared.get(mod), "data/asset"));
                assertEquals(originalGame, Digests.file(game));
                assertEquals(originalMod, Digests.file(mod));
            }
        }
    }

    @Test
    void firstHealthyModOwnsTheClassAndReversingOrderChangesTheWinner() throws Exception {
        Path game = game();
        byte[] valid = definition("lib/Shared", 11);
        byte[] truncated = java.util.Arrays.copyOf(valid, valid.length - 8);
        assertEquals("lib/Shared", new org.objectweb.asm.ClassReader(truncated).getClassName());
        Path malformed = jar("broken.jar", Map.of("lib/Shared.class", truncated, "data/asset", new byte[]{5}));
        Path named = jar("named.jar", Map.of("lib/Shared.class", definition("lib/Shared", 4)));
        Path obf = jar("obf.jar", Map.of("lib/Shared.class", definition("lib/Shared", 7)));
        for (var order : List.of(List.of(malformed, named, obf), List.of(malformed, obf, named))) {
            Path winner = order.get(1), loser = order.get(2);
            var cold = prepare(game, order, Set.of(named));
            assertEquals(winner.equals(named) ? 4 : 7, value(cold.get(winner), "lib/Shared.class"));
            assertNull(entry(cold.get(loser), "lib/Shared.class"));
            assertNull(entry(cold.get(malformed), "lib/Shared.class"));
            assertArrayEquals(new byte[]{5}, entry(cold.get(malformed), "data/asset"));
            Path report = cold.get(malformed).resolveSibling("class-filter.log");
            var warnings = Files.readAllLines(report);
            assertTrue(warnings.stream().anyMatch(line -> line.contains(malformed.toString()) && line.contains("lib/Shared.class")), warnings::toString);
            assertTrue(warnings.stream().anyMatch(line -> line.contains(loser.toString()) && line.contains(winner.toString()) && line.contains("lib/Shared.class")), warnings::toString);
            var hot = prepare(game, order, Set.of(named));
            assertEquals(cold, hot);
            assertEquals(warnings, Files.readAllLines(hot.get(malformed).resolveSibling("class-filter.log")));
        }
    }

    @Test
    void duplicateZipClassNamesKeepTheEntryWithValidCrcInEitherOrder() throws Exception {
        Path game = game();
        String name = "lib/Shared";
        for (int damaged : List.of(0, 1)) {
            Path mod = duplicateZip(name + ".class", definition(name, 4), definition(name, 7), damaged);
            Path output = prepare(game, List.of(mod), Set.of()).get(mod);
            assertEquals(damaged == 0 ? 7 : 4, value(output, name + ".class"));
            var report = Files.readAllLines(output.resolveSibling("class-filter.log"));
            assertTrue(report.stream().anyMatch(line -> line.contains(mod.toString()) && line.contains(name) && line.contains("CRC")), report::toString);
        }
    }

    @Test
    void duplicateResourceNamesRemainAnArchiveError() throws Exception {
        Path game = game();
        Path mod = duplicateZip("data/asset", new byte[]{1}, new byte[]{2}, -1);
        var ex = assertThrows(IOException.class, () -> prepare(game, List.of(mod), Set.of()));
        assertTrue(ex.getMessage().contains("data/asset") && ex.getMessage().contains(mod.toString()), ex::getMessage);
    }

    private Path duplicateZip(String name, byte[] first, byte[] second, int badCrc) throws IOException {
        var entries = new java.util.LinkedHashMap<String, byte[]>();
        entries.put(name, first);
        entries.put(name.substring(0, name.length() - 1) + "2", second);
        Path archive = jar("duplicates.jar", entries);
        byte[] bytes = Files.readAllBytes(archive);
        var zip = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int central = zip.getInt(bytes.length - 22 + 16);
        int secondHeader = central + 46 + Short.toUnsignedInt(zip.getShort(central + 28))
                + Short.toUnsignedInt(zip.getShort(central + 30)) + Short.toUnsignedInt(zip.getShort(central + 32));
        int local = zip.getInt(secondHeader + 42);
        // 用等长文件名覆盖第二个条目，保留两套独立的中央目录和本地文件头。
        int last = name.length() - 1;
        bytes[secondHeader + 46 + last] = bytes[local + 30 + last] = (byte) name.charAt(last);
        if (badCrc >= 0) {
            int crc = (badCrc == 0 ? central : secondHeader) + 16;
            zip.putInt(crc, ~zip.getInt(crc));
        }
        Files.write(archive, bytes);
        return archive;
    }

    @Test
    void hierarchyDefinitionPrecedesModArchives() throws Exception {
        String name = "library/Base";
        String key = name + ".class";
        Path library = jar("library.jar", Map.of(key, definition(name, 5)));
        Path mod = jar("mod.jar", Map.of(key, definition(name, 8), "pack/User.class", type("pack/User", name, false, 0)));
        var result = prepare(game(), List.of(mod), Set.of(), List.of(library));
        assertNull(entry(result.get(mod), key));
        assertEquals(name, node(entry(result.get(mod), "pack/User.class")).superName);
        var report = Files.readAllLines(result.get(mod).resolveSibling("class-filter.log"));
        assertTrue(report.stream().anyMatch(line -> line.contains(key) && line.contains(library.toString())
                && line.contains(mod.toString())), report::toString);
        assertEquals(5, value(library, key));
    }

    @Test
    void multiReleaseVariantsStayWithTheFirstVisibleOwner() throws Exception {
        String name = "lib/Shared";
        String root = name + ".class", version = "META-INF/versions/17/" + root;
        Path game = game();
        Path inert = jar("plain.jar", Map.of(version, definition(name, 1)));
        Path mr = jar("multi.jar", Map.of("META-INF/MANIFEST.MF", manifest(false, true),
                root, definition(name, 3), version, definition(name, 4)));
        Path plain = jar("root.jar", Map.of(root, definition(name, 9)));
        var mrFirst = prepare(game, List.of(inert, mr, plain), Set.of());
        assertEquals(3, value(mrFirst.get(mr), root));
        assertEquals(4, value(mrFirst.get(mr), version));
        assertNull(entry(mrFirst.get(plain), root));
        var plainFirst = prepare(game, List.of(inert, plain, mr), Set.of());
        assertEquals(9, value(plainFirst.get(plain), root));
        assertNull(entry(plainFirst.get(mr), root));
        assertNull(entry(plainFirst.get(mr), version));
    }

    private Map<Path, Path> prepare(Path game, List<Path> mods, Set<Path> named) throws IOException {
        return prepare(game, mods, named, List.of());
    }

    private Map<Path, Path> prepare(Path game, List<Path> mods, Set<Path> named, List<Path> hierarchy) throws IOException {
        Path mapping = temp.resolve("names.tiny");
        Files.writeString(mapping, "tiny\t2\t0\tobf\tnamed\nc\ta/A\tapi/Game\n");
        return ArchivePreparation.prepare(temp.resolve("cache"), mapping, List.of(game), mods, hierarchy, named);
    }

    private Path game() throws IOException {
        return jar("game.jar", Map.of("a/A.class", definition("a/A", 2)));
    }

    private static byte[] definition(String name, int value) {
        return type(name, "java/lang/Object", true, value);
    }

    private static byte[] manifest(boolean named, boolean multiRelease) {
        return ("Manifest-Version: 1.0\r\n" + (named ? "SSOptimizer-Namespace: named\r\n" : "")
                + (multiRelease ? "Multi-Release: true\r\n" : "") + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    private Path jar(String name, Map<String, byte[]> entries) throws IOException {
        Path path = temp.resolve(name);
        try (var out = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var item : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(item.getKey()));
                out.write(item.getValue());
                out.closeEntry();
            }
        }
        return path;
    }

    private static byte[] entry(Path archive, String name) throws IOException {
        try (var zip = new ZipFile(archive.toFile())) {
            var item = zip.getEntry(name);
            if (item == null) return null;
            try (var in = zip.getInputStream(item)) { return in.readAllBytes(); }
        }
    }

    private static int value(Path archive, String name) throws IOException {
        byte[] bytes = entry(archive, name);
        assertNotNull(bytes, archive + "!" + name);
        for (var instruction : node(bytes).methods.getFirst().instructions) {
            if (instruction instanceof IntInsnNode literal) return literal.operand;
        }
        throw new AssertionError("Missing fixture return value in " + archive + "!" + name);
    }
}
