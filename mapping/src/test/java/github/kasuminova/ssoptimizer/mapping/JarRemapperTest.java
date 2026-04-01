package github.kasuminova.ssoptimizer.mapping;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JAR 重映射器测试。
 * <p>
 * 该测试验证编译期依赖 jar 的 remap 不只是改文件名，还会同步改写类名、字段名、方法名
 * 与资源保留策略，为 Gradle 的 mapped 编译链路提供最小回归保障。
 */
class JarRemapperTest {

    @Test
    void remapsJarEntriesAndMembersToNamedNamespace() throws Exception {
        TinyV2MappingRepository repository = TinyV2MappingRepository.of(List.of(
                MappingEntry.classEntry("example/ObfTexture", "example/NamedTexture"),
                MappingEntry.classEntry("example/ObfLoader", "example/NamedLoader"),
                MappingEntry.fieldEntry("example/ObfLoader", "example/NamedLoader", "a", "cacheSize", "I"),
                MappingEntry.methodEntry("example/ObfLoader", "example/NamedLoader", "b", "reloadCache", "(I)V"),
                MappingEntry.methodEntry(
                        "example/ObfLoader",
                        "example/NamedLoader",
                        "c",
                        "convert",
                        "(Lexample/ObfTexture;)Lexample/ObfTexture;")
        ));

        Path inputJar = Files.createTempFile("ssoptimizer-remap-input", ".jar");
        Path outputJar = Files.createTempFile("ssoptimizer-remap-output", ".jar");
        writeJar(inputJar, createObfuscatedLoader(), "payload".getBytes(StandardCharsets.UTF_8));

        new JarRemapper(repository, MappingDirection.OBFUSCATED_TO_NAMED).remapJar(inputJar, outputJar);

        try (JarFile jarFile = new JarFile(outputJar.toFile())) {
            assertNotNull(jarFile.getJarEntry("example/NamedLoader.class"));
            assertNotNull(jarFile.getJarEntry("notes.txt"));
            assertFalse(jarFile.stream().anyMatch(entry -> entry.getName().equals("example/ObfLoader.class")));

            byte[] classBytes = jarFile.getInputStream(jarFile.getJarEntry("example/NamedLoader.class")).readAllBytes();
            ClassNode node = readClass(classBytes);
            assertEquals("example/NamedLoader", node.name);
            assertTrue(node.fields.stream().anyMatch(field -> "cacheSize".equals(field.name)));
            assertTrue(node.methods.stream().anyMatch(method -> "reloadCache".equals(method.name)));
            assertTrue(node.methods.stream().anyMatch(method -> "convert".equals(method.name)
                    && "(Lexample/NamedTexture;)Lexample/NamedTexture;".equals(method.desc)));

            String resourceText = new String(jarFile.getInputStream(jarFile.getJarEntry("notes.txt")).readAllBytes(), StandardCharsets.UTF_8);
            assertEquals("payload", resourceText);
        }
    }

    @Test
    void reobfuscatesNamedJarBackToOriginalNamespace() throws Exception {
        TinyV2MappingRepository repository = TinyV2MappingRepository.of(List.of(
                MappingEntry.classEntry("example/ObfThing", "example/NamedThing"),
                MappingEntry.fieldEntry("example/ObfThing", "example/NamedThing", "a", "cacheSize", "I"),
                MappingEntry.methodEntry("example/ObfThing", "example/NamedThing", "b", "reloadCache", "(I)V")
        ));

        Path inputJar = Files.createTempFile("ssoptimizer-reobf-input", ".jar");
        Path outputJar = Files.createTempFile("ssoptimizer-reobf-output", ".jar");
        writeNamedJar(inputJar, createNamedThing());

        new JarRemapper(repository, MappingDirection.NAMED_TO_OBFUSCATED).remapJar(inputJar, outputJar);

        try (JarFile jarFile = new JarFile(outputJar.toFile())) {
            assertNotNull(jarFile.getJarEntry("example/ObfThing.class"));
            byte[] classBytes = jarFile.getInputStream(jarFile.getJarEntry("example/ObfThing.class")).readAllBytes();
            ClassNode node = readClass(classBytes);
            assertEquals("example/ObfThing", node.name);
            assertTrue(node.fields.stream().anyMatch(field -> "a".equals(field.name)));
            assertTrue(node.methods.stream().anyMatch(method -> "b".equals(method.name)));
        }
    }

    private static void writeJar(Path jarPath, byte[] classBytes, byte[] resourceBytes) throws IOException {
        try (JarOutputStream outputStream = new JarOutputStream(Files.newOutputStream(jarPath))) {
            outputStream.putNextEntry(new JarEntry("example/ObfLoader.class"));
            outputStream.write(classBytes);
            outputStream.closeEntry();

            outputStream.putNextEntry(new JarEntry("notes.txt"));
            outputStream.write(resourceBytes);
            outputStream.closeEntry();
        }
    }

    private static void writeNamedJar(Path jarPath, byte[] classBytes) throws IOException {
        try (JarOutputStream outputStream = new JarOutputStream(Files.newOutputStream(jarPath))) {
            outputStream.putNextEntry(new JarEntry("example/NamedThing.class"));
            outputStream.write(classBytes);
            outputStream.closeEntry();
        }
    }

    private static byte[] createObfuscatedLoader() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "example/ObfLoader", null, "java/lang/Object", null);

        writer.visitField(Opcodes.ACC_PRIVATE, "a", "I", null, null).visitEnd();

        MethodVisitor ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();

        MethodVisitor reload = writer.visitMethod(Opcodes.ACC_PUBLIC, "b", "(I)V", null, null);
        reload.visitCode();
        reload.visitInsn(Opcodes.RETURN);
        reload.visitMaxs(0, 2);
        reload.visitEnd();

        MethodVisitor convert = writer.visitMethod(Opcodes.ACC_PUBLIC, "c", "(Lexample/ObfTexture;)Lexample/ObfTexture;", null, null);
        convert.visitCode();
        convert.visitInsn(Opcodes.ACONST_NULL);
        convert.visitInsn(Opcodes.ARETURN);
        convert.visitMaxs(1, 2);
        convert.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] createNamedThing() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "example/NamedThing", null, "java/lang/Object", null);

        writer.visitField(Opcodes.ACC_PRIVATE, "cacheSize", "I", null, null).visitEnd();

        MethodVisitor ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();

        MethodVisitor reload = writer.visitMethod(Opcodes.ACC_PUBLIC, "reloadCache", "(I)V", null, null);
        reload.visitCode();
        reload.visitInsn(Opcodes.RETURN);
        reload.visitMaxs(0, 2);
        reload.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode readClass(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
