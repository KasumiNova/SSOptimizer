package github.kasuminova.ssoptimizer.asm.loading;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ShipMasteryReflectionLoaderProcessorTest {

    private static final String FIXTURE_CLASS = "shipmastery/plugin/ModPlugin$ReflectionEnabledClassLoader";
    private static final String FACTORY_CLASS = FIXTURE_CLASS + "$TestFactory";

    @TempDir
    Path tempDir;

    @Test
    void injectedFindClassDefinesClassThroughFullChainAndFallsBackWhenAbsent() throws Exception {
        final byte[] rewritten = new ShipMasteryReflectionLoaderProcessor().process(fixtureClassBytes());
        assertNotNull(rewritten, "目标类应被注入");

        final ByteArrayLoader definingLoader = new ByteArrayLoader(getClass().getClassLoader());
        definingLoader.define(FIXTURE_CLASS.replace('/', '.'), rewritten);
        final String factoryName = FACTORY_CLASS.replace('/', '.');
        final Class<?> factoryClass = definingLoader.define(factoryName,
                github.kasuminova.ssoptimizer.testing.ClassInstances.publishInstanceOnInit(factoryClassBytes()));
        final LoaderFactory factory = github.kasuminova.ssoptimizer.testing.ClassInstances.instantiate(
                LoaderFactory.class, factoryClass);

        final Path probePath = tempDir.resolve("probe/Target.class");
        Files.createDirectories(probePath.getParent());
        Files.write(probePath, probeClassBytes());
        try (URLClassLoader loader = factory.create(
                new URL[]{tempDir.toUri().toURL()}, getClass().getClassLoader())) {
            final Class<?> loaded = loader.loadClass("probe.Target");
            assertEquals("probe.Target", loaded.getName());
            assertSame(loader, loaded.getClassLoader(), "probe.Target 应由注入后的 loader 自行定义");

            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("probe.Missing"));
        }
    }

    @Test
    void supportReadsBytesFromLoaderOwnClasspath() throws Exception {
        final Path probePath = tempDir.resolve("probe/Target.class");
        Files.createDirectories(probePath.getParent());
        Files.write(probePath, probeClassBytes());

        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{tempDir.toUri().toURL()}, getClass().getClassLoader())) {
            final byte[] bytes = ShipMasteryLoaderSupport.loadTransformedBytes(loader, "probe.Target");
            assertNotNull(bytes);
            assertArrayEquals(Files.readAllBytes(probePath), bytes);

            assertNull(ShipMasteryLoaderSupport.loadTransformedBytes(loader, "probe.Missing"));
        }
    }

    @Test
    void skipsClassThatAlreadyOverridesFindClass() {
        final ClassWriter writer = fixtureWriter();
        final MethodVisitor existing = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "findClass", "(Ljava/lang/String;)Ljava/lang/Class;",
                null, new String[]{"java/lang/ClassNotFoundException"});
        existing.visitCode();
        existing.visitVarInsn(Opcodes.ALOAD, 0);
        existing.visitVarInsn(Opcodes.ALOAD, 1);
        existing.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/net/URLClassLoader",
                "findClass", "(Ljava/lang/String;)Ljava/lang/Class;", false);
        existing.visitInsn(Opcodes.ARETURN);
        existing.visitMaxs(0, 0);
        existing.visitEnd();
        writer.visitEnd();

        assertNull(new ShipMasteryReflectionLoaderProcessor().process(writer.toByteArray()));
    }

    @Test
    void ignoresUnrelatedClasses() {
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "some/Other", null, "java/lang/Object", null);
        writer.visitEnd();
        assertNull(new ShipMasteryReflectionLoaderProcessor().process(writer.toByteArray()));
    }

    private static byte[] fixtureClassBytes() {
        final ClassWriter writer = fixtureWriter();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassWriter fixtureWriter() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, FIXTURE_CLASS, null, "java/net/URLClassLoader", null);
        final MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                "([Ljava/net/URL;Ljava/lang/ClassLoader;)V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.ALOAD, 1);
        constructor.visitVarInsn(Opcodes.ALOAD, 2);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/net/URLClassLoader",
                "<init>", "([Ljava/net/URL;Ljava/lang/ClassLoader;)V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        return writer;
    }

    private static byte[] factoryClassBytes() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                FACTORY_CLASS, null, "java/lang/Object", new String[]{Type.getInternalName(LoaderFactory.class)});
        final MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        final MethodVisitor create = writer.visitMethod(Opcodes.ACC_PUBLIC, "create",
                "([Ljava/net/URL;Ljava/lang/ClassLoader;)Ljava/net/URLClassLoader;", null, null);
        create.visitCode();
        create.visitTypeInsn(Opcodes.NEW, FIXTURE_CLASS);
        create.visitInsn(Opcodes.DUP);
        create.visitVarInsn(Opcodes.ALOAD, 1);
        create.visitVarInsn(Opcodes.ALOAD, 2);
        create.visitMethodInsn(Opcodes.INVOKESPECIAL, FIXTURE_CLASS,
                "<init>", "([Ljava/net/URL;Ljava/lang/ClassLoader;)V", false);
        create.visitInsn(Opcodes.ARETURN);
        create.visitMaxs(0, 0);
        create.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] probeClassBytes() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "probe/Target", null, "java/lang/Object", null);
        final MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    public interface LoaderFactory {
        URLClassLoader create(URL[] urls, ClassLoader parent);
    }

    private static final class ByteArrayLoader extends ClassLoader {
        ByteArrayLoader(final ClassLoader parent) {
            super(parent);
        }

        Class<?> define(final String name, final byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
