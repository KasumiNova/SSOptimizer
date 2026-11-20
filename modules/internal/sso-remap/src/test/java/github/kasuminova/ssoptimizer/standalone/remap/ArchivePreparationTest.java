package github.kasuminova.ssoptimizer.standalone.remap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ArchivePreparationTest {
    @TempDir
    Path temp;

    @Test
    void inheritedMembersAndCrossArchiveCallsUseTheSameMappedName() throws Exception {
        final Path mapping = mapping("tiny\t2\t0\tobf\tnamed\nc\ta/A\tnamed/Base\n\tm\t()I\ta\tvalue\nc\ta/B\tnamed/Sub\n");
        final Path base = jar("base.jar", Map.of("a/A.class", type("a/A", "java/lang/Object", true, 7)));
        final Path child = jar("child.jar", Map.of("a/B.class", type("a/B", "a/A", true, 8)));
        final Path mod = jar("mod.jar", Map.of("mod/Caller.class", caller(), "translation.txt", "中文资源".getBytes(StandardCharsets.UTF_8),
                "META-INF/services/a.A", "a.B # provider\n".getBytes(StandardCharsets.UTF_8), "META-INF/OLD.SF", new byte[]{1}));
        final Map<Path, Path> result = ArchivePreparation.prepare(temp.resolve("cache"), mapping, List.of(base, child), List.of(mod), List.of());
        final ClassNode childNode = node(entry(result.get(child), "named/Sub.class"));
        assertEquals("named/Base", childNode.superName);
        assertTrue(childNode.methods.stream().anyMatch(method -> method.name.equals("value") && method.desc.equals("()I")));
        final ClassNode caller = node(entry(result.get(mod), "mod/Caller.class"));
        final var method = caller.methods.stream().filter(candidate -> candidate.name.equals("run")).findFirst().orElseThrow();
        final MethodInsnNode invoke = java.util.Arrays.stream(method.instructions.toArray())
                .filter(instruction -> instruction instanceof MethodInsnNode)
                .map(instruction -> (MethodInsnNode) instruction).findFirst().orElseThrow();
        assertEquals("named/Sub", invoke.owner);
        assertEquals("value", invoke.name);
        assertArrayEquals("中文资源".getBytes(StandardCharsets.UTF_8), entry(result.get(mod), "translation.txt"));
        assertEquals("named.Sub # provider\n\n", new String(entry(result.get(mod), "META-INF/services/named.Base"), StandardCharsets.UTF_8));
        try (ZipFile zip = new ZipFile(result.get(mod).toFile())) {
            assertNull(zip.getEntry("META-INF/OLD.SF"));
        }
        assertEquals(result, ArchivePreparation.prepare(temp.resolve("cache"), mapping, List.of(base, child), List.of(mod), List.of()));
    }

    @Test
    void namedMetadataKeepsOriginalArchivesAndInputOrderAcrossColdAndHotPreparation() throws Exception {
        final Path mapping = mapping("tiny\t2\t0\tobf\tnamed\nc\ta/A\tnamed/Base\n\tm\t()I\ta\tvalue\nc\ta/B\tnamed/Sub\n");
        final ClassNode baseNode = node(type("named/Base", "java/lang/Object", true, 7));
        baseNode.methods.getFirst().name = "value";
        final ClassWriter writer = new ClassWriter(0);
        baseNode.accept(writer);
        final Path named = jar("named.jar", Map.of("META-INF/MANIFEST.MF", namedManifest(), "named/Base.class", writer.toByteArray()));
        final byte[] original = Files.readAllBytes(named);
        final Path child = jar("child.jar", Map.of("a/B.class", type("a/B", "a/A", true, 8)));
        final Path mod = jar("mod.jar", Map.of("mod/Caller.class", caller()));
        final Map<Path, Path> first = ArchivePreparation.prepare(temp.resolve("cache"), mapping, List.of(named, child), List.of(mod), List.of());
        assertEquals(List.of(named, child, mod), List.copyOf(first.keySet()));
        assertEquals(named, first.get(named));
        assertArrayEquals(original, Files.readAllBytes(named));
        final var mappedChild = node(entry(first.get(child), "named/Sub.class"));
        assertEquals("named/Base", mappedChild.superName);
        assertTrue(mappedChild.methods.stream().anyMatch(method -> method.name.equals("value")));
        final var mappedMod = node(entry(first.get(mod), "mod/Caller.class"));
        final var call = java.util.Arrays.stream(mappedMod.methods.getFirst().instructions.toArray())
                .filter(instruction -> instruction instanceof MethodInsnNode).map(instruction -> (MethodInsnNode) instruction).findFirst().orElseThrow();
        assertEquals("value", call.name);
        assertEquals(first, ArchivePreparation.prepare(temp.resolve("cache"), mapping, List.of(named, child), List.of(mod), List.of()));
        final Map<Path, Path> allNamed = ArchivePreparation.prepare(temp.resolve("all-named"), mapping,
                List.of(named, first.get(child)), List.of(first.get(mod)), List.of());
        assertEquals(List.of(named, first.get(child), first.get(mod)), List.copyOf(allNamed.values()));
        assertEquals(List.copyOf(allNamed.keySet()), List.copyOf(allNamed.values()));
        final Map<Path, Path> namedCore = ArchivePreparation.prepare(temp.resolve("named-core"), mapping,
                List.of(named, first.get(child)), List.of(mod), List.of());
        assertEquals(named, namedCore.get(named));
        assertEquals(first.get(child), namedCore.get(first.get(child)));
        final var namedCoreMod = node(entry(namedCore.get(mod), "mod/Caller.class"));
        final var namedCoreCall = java.util.Arrays.stream(namedCoreMod.methods.getFirst().instructions.toArray())
                .filter(instruction -> instruction instanceof MethodInsnNode).map(instruction -> (MethodInsnNode) instruction).findFirst().orElseThrow();
        assertEquals("named/Sub", namedCoreCall.owner);
        assertEquals("value", namedCoreCall.name);
        jar("named.jar", Map.of("META-INF/MANIFEST.MF", namedManifest(), "named/Base.class", writer.toByteArray(), "changed.txt", new byte[]{1}));
        final Map<Path, Path> changed = ArchivePreparation.prepare(temp.resolve("cache"), mapping, List.of(named, child), List.of(mod), List.of());
        assertEquals(named, changed.get(named));
        assertNotEquals(first.get(child), changed.get(child));
    }

    @Test
    void explicitlyNamedClassReferencesParticipateInNormalConversion() throws Exception {
        final Path mapping = mapping("tiny\t2\t0\tobf\tnamed\nc\ta/A\tnamed/Base\n");
        final Path game = jar("game.jar", Map.of("a/A.class", type("a/A", "java/lang/Object", false, 0)));
        final Path mod = jar("mixed.jar", Map.of("mod/Mixed.class", type("mod/Mixed", "named/Base", false, 0)));
        final var result = ArchivePreparation.prepare(temp.resolve("cache"), mapping, List.of(game), List.of(mod), List.of());
        assertEquals("named/Base", node(entry(result.get(mod), "mod/Mixed.class")).superName);
    }

    private static byte[] namedManifest() {
        return "Manifest-Version: 1.0\r\nSSOptimizer-Namespace: named\r\n\r\n".getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void manifestCasePreservesNamespaceResourcesAndMultiReleaseSelection() throws Exception {
        Path mapping = mapping("tiny\t2\t0\tobf\tnamed\nc\ta/A\tnamed/Base\n");
        String lower = "meta-inf/manifest.mf";
        String header = "Manifest-Version: 1.0\r\nMulti-Release: true\r\nX-Source: selected\r\n";
        Path game = jar("game.jar", Map.of("a/A.class", type("a/A", "java/lang/Object", false, 0),
                lower, (header + "\r\n").getBytes(StandardCharsets.UTF_8),
                "META-INF/versions/17/a/A.class", type("a/A", "java/lang/Object", true, 17)));
        Path result = ArchivePreparation.prepare(temp.resolve("cache"), mapping, List.of(game), List.of(), List.of()).get(game);
        try (var zip = new java.util.jar.JarFile(result.toFile(), false, ZipFile.OPEN_READ, Runtime.version())) {
            assertTrue(zip.isMultiRelease());
            assertEquals("selected", zip.getManifest().getMainAttributes().getValue("X-Source"));
            assertEquals("META-INF/versions/17/named/Base.class", zip.getJarEntry("named/Base.class").getRealName());
        }
    }

    @Test
    void collisionsAndUnsafeEntryPathsAreRejected() throws Exception {
        final Path mapping = mapping("tiny\t2\t0\tobf\tnamed\nc\ta/A\tnamed/Base\nc\ta/B\tnamed/Base\n");
        assertThrows(PreparationException.Conflict.class, () -> MappingProjection.read(mapping));
        final Path valid = mapping("tiny\t2\t0\tobf\tnamed\nc\ta/A\tnamed/Base\n");
        final Path malformed = jar("malformed.jar", Map.of("../outside", new byte[]{1}, "a/A.class", type("a/A", "java/lang/Object", false, 0)));
        assertThrows(PreparationException.Input.class,
                () -> ArchivePreparation.prepare(temp.resolve("cache"), valid, List.of(malformed), List.of(), List.of()));
        final Path one = jar("one.jar", Map.of("a/A.class", type("a/A", "java/lang/Object", true, 1)));
        final Path two = jar("two.jar", Map.of("a/A.class", type("a/A", "java/lang/Object", true, 2)));
        final var selected = ArchivePreparation.prepare(temp.resolve("other-cache"), valid, List.of(one), List.of(two), List.of());
        try (ZipFile mod = new ZipFile(selected.get(two).toFile())) { assertNull(mod.getEntry("named/Base.class")); }
    }

    @Test
    void gzipMappingEscapesAndMultiReleaseEntriesKeepTheirMeaning() throws Exception {
        final Path mapping = temp.resolve("mapping.data");
        try (var gzip = new java.util.zip.GZIPOutputStream(Files.newOutputStream(mapping))) {
            gzip.write("tiny\t2\t0\tobf\tnamed\n\tescaped-names\nc\ta/A\tnamed/Base\nc\ta/Weird\\tType\tnamed/Normal\n".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals("named/Normal", MappingProjection.read(mapping).mapClass("a/Weird\tType"));
        final Path input = jar("multi.jar", Map.of("META-INF/MANIFEST.MF",
                "Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n".getBytes(StandardCharsets.UTF_8),
                "a/A.class", type("a/A", "java/lang/Object", true, 1),
                "META-INF/versions/17/a/A.class", type("a/A", "java/lang/Object", true, 2),
                "data/", new byte[0]));
        final Path result = ArchivePreparation.prepare(temp.resolve("cache"), mapping, List.of(input), List.of(), List.of()).get(input);
        assertEquals("named/Base", node(entry(result, "named/Base.class")).name);
        assertEquals("named/Base", node(entry(result, "META-INF/versions/17/named/Base.class")).name);
        try (ZipFile zip = new ZipFile(result.toFile())) {
            assertNotNull(zip.getEntry("data/"));
            assertNotNull(zip.getEntry("named/"));
        }
    }

    @Test
    void independentModuleDescriptorsStayWithTheirOriginArchive() throws Exception {
        final Path mapping = mapping("tiny\t2\t0\tobf\tnamed\nc\ta/A\tnamed/Base\nc\ta/B\tnamed/Sub\n");
        final Path first = jar("first.jar", Map.of("a/A.class", type("a/A", "java/lang/Object", false, 0),
                "META-INF/versions/9/module-info.class", module("first.module", "a", "a/A")));
        final Path second = jar("second.jar", Map.of("a/B.class", type("a/B", "java/lang/Object", false, 0),
                "META-INF/versions/9/module-info.class", module("second.module", "a", "a/B")));
        final Map<Path, Path> result = ArchivePreparation.prepare(temp.resolve("cache"), mapping, List.of(first), List.of(second), List.of());
        final var one = node(entry(result.get(first), "META-INF/versions/9/module-info.class")).module;
        final var two = node(entry(result.get(second), "META-INF/versions/9/module-info.class")).module;
        assertEquals("first.module", one.name);
        assertEquals("second.module", two.name);
        assertEquals("named", one.exports.getFirst().packaze);
        assertEquals(List.of("named/Base"), one.uses);
        assertEquals(List.of("named/Sub"), two.uses);
    }

    private static byte[] module(final String name, final String exported, final String used) {
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V9, Opcodes.ACC_MODULE, "module-info", null, null, null);
        final var module = writer.visitModule(name, 0, null);
        module.visitRequire("java.base", Opcodes.ACC_MANDATED, null);
        module.visitPackage(exported);
        module.visitExport(exported, 0);
        module.visitUse(used);
        module.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    void repeatedMappingSourcesAreRejectedBeforeTheyCanOverwriteAnotherRule() throws Exception {
        final Path duplicateClass = mapping("tiny\t2\t0\tobf\tnamed\nc\ta/A\tnamed/First\nc\ta/A\tnamed/Second\n");
        assertThrows(PreparationException.Conflict.class, () -> MappingProjection.read(duplicateClass));
        final Path duplicateMethod = mapping("tiny\t2\t0\tobf\tnamed\nc\ta/A\tnamed/First\n\tm\t()V\ta\tone\n\tm\t()V\ta\ttwo\n");
        assertThrows(PreparationException.Conflict.class, () -> MappingProjection.read(duplicateMethod));
    }

    private Path mapping(final String text) throws Exception {
        final Path path = temp.resolve("mapping.tiny");
        Files.writeString(path, text);
        return path;
    }

    private Path jar(final String name, final Map<String, byte[]> entries) throws Exception {
        final Path path = temp.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var entry : new java.util.TreeMap<>(entries).entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return path;
    }

    static byte[] type(final String name, final String parent, final boolean method, final int value) {
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, parent, null);
        if (method) {
            final var visitor = writer.visitMethod(Opcodes.ACC_PUBLIC, "a", "()I", null, null);
            visitor.visitCode();
            visitor.visitIntInsn(Opcodes.BIPUSH, value);
            visitor.visitInsn(Opcodes.IRETURN);
            visitor.visitMaxs(1, 1);
            visitor.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] caller() {
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/Caller", null, "java/lang/Object", null);
        final var visitor = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "(La/B;)I", null, null);
        visitor.visitCode();
        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "a/B", "a", "()I", false);
        visitor.visitInsn(Opcodes.IRETURN);
        visitor.visitMaxs(1, 1);
        visitor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] entry(final Path archive, final String name) throws Exception {
        try (ZipFile zip = new ZipFile(archive.toFile()); var stream = zip.getInputStream(zip.getEntry(name))) {
            return stream.readAllBytes();
        }
    }

    static ClassNode node(final byte[] bytes) {
        final ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
