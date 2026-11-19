package github.kasuminova.ssoptimizer.asm.render;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class UrlClassDefinitionTest {
    private static final String PAYLOAD = "fixture/DrawPayload";
    private static final String LWJGL_SYNC = "org.lwjgl.opengl.GLSync";
    private static final String BRIDGE_SYNC = "github.kasuminova.ssoptimizer.bridge.opengl.GLSync";

    @TempDir
    Path directory;

    @Test
    void inheritedFindClassRedirectsOpenGlAndRetainsItsDefiningLoaderAndSource() throws Throwable {
        final URL archive = classArchive();
        try (var loader = loadTransformedLoader(archive, getClass().getClassLoader(), false)) {
            final Class<?> definition = resolve(loader);
            assertSame(loader, definition.getClassLoader());
            assertEquals(archive, definition.getProtectionDomain().getCodeSource().getLocation());
            assertEquals(BRIDGE_SYNC, readOpenGlType(definition).getName());
        }
    }

    @Test
    void overriddenFindClassKeepsVirtualDispatch() throws Throwable {
        try (var loader = loadTransformedLoader(classArchive(), getClass().getClassLoader(), true)) {
            assertSame(String.class, resolve(loader));
        }
    }

    @Test
    void loaderWithoutBridgeKeepsOriginalOpenGlType() throws Throwable {
        final URL lwjgl = org.lwjgl.opengl.GLSync.class.getProtectionDomain().getCodeSource().getLocation();
        try (var parent = new URLClassLoader(new URL[]{lwjgl}, null);
             var loader = loadTransformedLoader(classArchive(), parent, false)) {
            assertSame(parent.loadClass(LWJGL_SYNC), readOpenGlType(resolve(loader)));
        }
    }

    private URL classArchive() throws Exception {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, PAYLOAD, null, "java/lang/Object",
                new String[]{"java/util/function/Supplier"});
        final GeneratorAdapter constructor = constructor(writer);
        constructor.loadThis();
        constructor.invokeConstructor(Type.getType(Object.class), new Method("<init>", "()V"));
        constructor.returnValue();
        constructor.endMethod();
        final GeneratorAdapter getter = new GeneratorAdapter(Opcodes.ACC_PUBLIC,
                new Method("get", "()Ljava/lang/Object;"), null, null, writer);
        getter.push(Type.getObjectType(LWJGL_SYNC.replace('.', '/')));
        getter.returnValue();
        getter.endMethod();
        writer.visitEnd();
        final Path archive = directory.resolve("classes.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(archive))) {
            jar.putNextEntry(new JarEntry(PAYLOAD + ".class"));
            jar.write(writer.toByteArray());
        }
        return archive.toUri().toURL();
    }

    private static GeneratorAdapter constructor(final ClassWriter writer) {
        return new GeneratorAdapter(Opcodes.ACC_PUBLIC, new Method("<init>", "()V"), null, null, writer);
    }

    private URLClassLoader loadTransformedLoader(final URL archive, final ClassLoader parent,
                                                  final boolean overridden) throws Throwable {
        final ClassLoader testLoader = getClass().getClassLoader();
        final String name = Type.getInternalName(FixtureLoader.class);
        final byte[] original;
        try (var input = testLoader.getResourceAsStream(name + ".class")) {
            original = input.readAllBytes();
        }
        final byte[] input = overridden ? addFindClassOverride(original) : original;
        final byte[] redirected = ClassDefinitionRedirector.rewrite(input, testLoader);
        assertNotSame(input, redirected);
        final Class<?> transformed = new ByteDefinition(testLoader).load(redirected);
        final var create = MethodHandles.publicLookup().findConstructor(transformed,
                MethodType.methodType(void.class, URL[].class, ClassLoader.class));
        return (URLClassLoader) create.invoke(new URL[]{archive}, parent);
    }

    private static byte[] addFindClassOverride(final byte[] bytes) {
        final ClassReader reader = new ClassReader(bytes);
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public void visitEnd() {
                final GeneratorAdapter method = new GeneratorAdapter(Opcodes.ACC_PROTECTED,
                        new Method("findClass", "(Ljava/lang/String;)Ljava/lang/Class;"), null, null, writer);
                method.push(Type.getType(String.class));
                method.returnValue();
                method.endMethod();
                super.visitEnd();
            }
        }, 0);
        return writer.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static Class<?> resolve(final URLClassLoader loader) {
        return ((Function<String, Class<?>>) loader).apply(PAYLOAD.replace('/', '.'));
    }

    private static Class<?> readOpenGlType(final Class<?> definition) throws Throwable {
        final var constructor = MethodHandles.publicLookup().findConstructor(definition, MethodType.methodType(void.class));
        return (Class<?>) ((Supplier<?>) constructor.invoke()).get();
    }

    public static class FixtureLoader extends URLClassLoader implements Function<String, Class<?>> {
        public FixtureLoader(final URL[] urls, final ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        public Class<?> apply(final String name) {
            try {
                return findClass(name);
            } catch (ClassNotFoundException failure) {
                throw new AssertionError(failure);
            }
        }
    }

    private static final class ByteDefinition extends ClassLoader {
        private ByteDefinition(final ClassLoader parent) {
            super(parent);
        }

        private Class<?> load(final byte[] bytes) {
            return defineClass(null, bytes, 0, bytes.length);
        }
    }
}
