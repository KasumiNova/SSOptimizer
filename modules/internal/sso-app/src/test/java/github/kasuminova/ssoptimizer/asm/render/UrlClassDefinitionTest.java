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

import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.SecureClassLoader;
import java.security.cert.Certificate;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void parentFirstLoaderWithoutLookupCallsRedirectsAfterParentMiss() throws Throwable {
        final URL archive = classArchive();
        final var types = new ByteDefinition(getClass().getClassLoader());
        try (var loader = types.create(DefaultLoader.class, archive, getClass().getClassLoader())) {
            assertSame(String.class, loader.loadClass("java.lang.String"));
            final Class<?> definition = loader.loadClass(PAYLOAD.replace('/', '.'));
            assertSame(loader, definition.getClassLoader());
            assertEquals(BRIDGE_SYNC, readOpenGlType(definition).getName());
        }
    }

    @Test
    void scopedNormalizerRepairsInvalidMembersInInheritedJarLookup() throws Throwable {
        final String name = "fixture.Unnormalized";
        final var writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name.replace('.', '/'), null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "bad.field", "I", null, null).visitEnd();
        writer.visitEnd();
        final byte[] original = writer.toByteArray();
        final Path file = directory.resolve("unnormalized.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(file))) {
            jar.putNextEntry(new JarEntry(name.replace('.', '/') + ".class"));
            jar.write(original);
        }
        final URL archive = file.toUri().toURL();
        UrlClassDefinition.installClassNormalizer((source, bytes) -> {
            if (!archive.getPath().equals(source.getPath())) return bytes;
            final var fixed = new ClassWriter(0);
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, fixed) {
                @Override
                public org.objectweb.asm.FieldVisitor visitField(int access, String fieldName, String descriptor,
                                                                 String signature, Object value) {
                    return super.visitField(access, fieldName.replace('.', '_'), descriptor, signature, value);
                }
            }, 0);
            return fixed.toByteArray();
        });
        final ClassLoader parent = getClass().getClassLoader();
        try (var direct = new URLClassLoader(new URL[]{archive}, parent);
             var managed = new ByteDefinition(parent).create(DefaultLoader.class, archive, parent)) {
            assertThrows(ClassFormatError.class, () -> direct.loadClass(name));
            final Class<?> defined = managed.loadClass(name);
            assertSame(managed, defined.getClassLoader());
            assertEquals(archive, defined.getProtectionDomain().getCodeSource().getLocation());
            assertSame(int.class, defined.getDeclaredField("bad_field").getType());
            try (var input = managed.getResourceAsStream(name.replace('.', '/') + ".class")) {
                org.junit.jupiter.api.Assertions.assertArrayEquals(original, input.readAllBytes());
            }
        }
        final Class<?> explicit = new ByteDefinition(parent).transform(ExplicitSourceLoader.class);
        final var lookup = MethodHandles.publicLookup();
        final var ctor = lookup.findConstructor(explicit, MethodType.methodType(void.class));
        final var array = lookup.findVirtual(explicit, "fromArray", MethodType.methodType(Class.class,
                String.class, byte[].class, int.class, int.class, CodeSource.class));
        final var buffer = lookup.findVirtual(explicit, "fromBuffer", MethodType.methodType(Class.class,
                String.class, ByteBuffer.class, CodeSource.class));
        final var source = new CodeSource(archive, (Certificate[]) null);
        final byte[] padded = new byte[original.length + 8];
        System.arraycopy(original, 0, padded, 3, original.length);
        final Object arrayLoader = ctor.invoke();
        final Class<?> fromArray = (Class<?>) array.invoke(arrayLoader, name, padded, 3, original.length, source);
        assertSame(arrayLoader, fromArray.getClassLoader());
        assertSame(source, fromArray.getProtectionDomain().getCodeSource());
        assertSame(int.class, fromArray.getDeclaredField("bad_field").getType());
        for (var data : new ByteBuffer[]{ByteBuffer.wrap(padded), ByteBuffer.allocateDirect(padded.length).put(padded)}) {
            data.position(3).limit(3 + original.length);
            final Object bufferLoader = ctor.invoke();
            final Class<?> fromBuffer = (Class<?>) buffer.invoke(bufferLoader, name, data, source);
            assertSame(bufferLoader, fromBuffer.getClassLoader());
            assertSame(source, fromBuffer.getProtectionDomain().getCodeSource());
            assertSame(int.class, fromBuffer.getDeclaredField("bad_field").getType());
            assertEquals(3, data.position());
            assertEquals(3 + original.length, data.limit());
        }
        final Object rawLoader = ctor.invoke();
        assertThrows(ClassFormatError.class, () -> array.invoke(rawLoader, name, padded, 3, original.length, (CodeSource) null));
        assertThrows(ClassFormatError.class, () -> array.invoke(rawLoader, name, padded, 3, original.length + 1, source));
        final byte[] different = padded.clone();
        different[3 + 7] = (byte) Opcodes.V21;
        assertThrows(ClassFormatError.class, () -> array.invoke(rawLoader, name, different, 3, original.length, source));
    }

    @Test
    void parentHitKeepsTheClassDefinedByParent() throws Throwable {
        final URL archive = classArchive();
        final var types = new ByteDefinition(getClass().getClassLoader());
        final String name = PAYLOAD.replace('/', '.');
        try (var parent = new URLClassLoader(new URL[]{archive}, getClass().getClassLoader());
             var loader = types.create(DefaultLoader.class, archive, parent)) {
            assertSame(parent.loadClass(name), loader.loadClass(name));
            assertEquals(LWJGL_SYNC, readOpenGlType(loader.loadClass(name)).getName());
        }
    }

    @Test
    void indirectDefaultLookupAndCustomSuperLookupRetainTheirDispatch() throws Throwable {
        final URL archive = classArchive();
        final var types = new ByteDefinition(getClass().getClassLoader());
        types.transform(DefaultLoader.class);
        final String name = PAYLOAD.replace('/', '.');
        try (var implicit = types.create(InheritedLoader.class, archive, getClass().getClassLoader());
             var custom = types.create(CustomLoader.class, archive, getClass().getClassLoader())) {
            assertEquals(BRIDGE_SYNC, readOpenGlType(implicit.loadClass(name)).getName());
            assertSame(Integer.class, custom.loadClass("fixture.Marker"));
            assertEquals(LWJGL_SYNC, readOpenGlType(custom.loadClass(name)).getName());
        }
    }

    @Test
    void unrelatedLoadersWithoutLookupCallsDoNotNeedABridge() throws Throwable {
        final URL archive = classArchive();
        final var types = new ByteDefinition(getClass().getClassLoader());
        final URL lwjgl = org.lwjgl.opengl.GLSync.class.getProtectionDomain().getCodeSource().getLocation();
        try (var parent = new URLClassLoader(new URL[]{lwjgl}, null);
             var loader = types.create(DefaultLoader.class, archive, parent)) {
            assertSame(parent.loadClass(LWJGL_SYNC), readOpenGlType(loader.loadClass(PAYLOAD.replace('/', '.'))));
        }
    }

    @Test
    void serializableLoadersKeepTheirDefaultAndExplicitSerialVersions() throws Throwable {
        final URL archive = classArchive();
        final var types = new ByteDefinition(getClass().getClassLoader());
        types.transform(DefaultLoader.class);
        for (final Class<?> loaderType : new Class<?>[]{SerializableLoader.class, ExplicitSerialLoader.class}) {
            try (var loader = types.create(loaderType, archive, getClass().getClassLoader())) {
                assertEquals(ObjectStreamClass.lookup(loaderType).getSerialVersionUID(),
                        ObjectStreamClass.lookup(loader.getClass()).getSerialVersionUID());
                assertEquals(BRIDGE_SYNC, readOpenGlType(loader.loadClass(PAYLOAD.replace('/', '.'))).getName());
            }
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
        final var definitions = new ByteDefinition(testLoader);
        final byte[] redirected = ClassDefinitionRedirector.rewrite(input, definitions);
        assertNotSame(input, redirected);
        final Class<?> transformed = definitions.load(redirected);
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

    public static final class ExplicitSourceLoader extends SecureClassLoader {
        public Class<?> fromArray(String name, byte[] bytes, int offset, int length, CodeSource source) {
            return defineClass(name, bytes, offset, length, source);
        }

        public Class<?> fromBuffer(String name, ByteBuffer bytes, CodeSource source) {
            return defineClass(name, bytes, source);
        }
    }

    public static class DefaultLoader extends URLClassLoader {
        public DefaultLoader(final URL[] urls, final ClassLoader parent) {
            super(urls, parent);
        }
    }

    public static final class InheritedLoader extends DefaultLoader {
        public InheritedLoader(final URL[] urls, final ClassLoader parent) {
            super(urls, parent);
        }
    }

    public static final class SerializableLoader extends DefaultLoader implements Serializable {
        public SerializableLoader(final URL[] urls, final ClassLoader parent) {
            super(urls, parent);
        }
    }

    public static final class ExplicitSerialLoader extends URLClassLoader implements Serializable {
        private static final long serialVersionUID = 42L;

        public ExplicitSerialLoader(final URL[] urls, final ClassLoader parent) {
            super(urls, parent);
        }
    }

    public static final class CustomLoader extends DefaultLoader {
        public CustomLoader(final URL[] urls, final ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> findClass(final String name) throws ClassNotFoundException {
            if (name.equals("fixture.Marker")) return Integer.class;
            return super.findClass(name);
        }
    }

    private static final class ByteDefinition extends ClassLoader {
        private ByteDefinition(final ClassLoader parent) {
            super(parent);
        }

        private Class<?> transform(final Class<?> fixture) throws Exception {
            final String name = Type.getInternalName(fixture);
            try (var input = getResourceAsStream(name + ".class")) {
                return load(ClassDefinitionRedirector.rewrite(input.readAllBytes(), this));
            }
        }

        private URLClassLoader create(final Class<?> fixture, final URL archive, final ClassLoader parent) throws Throwable {
            final var constructor = MethodHandles.publicLookup().findConstructor(transform(fixture),
                    MethodType.methodType(void.class, URL[].class, ClassLoader.class));
            return (URLClassLoader) constructor.invoke(new URL[]{archive}, parent);
        }

        private Class<?> load(final byte[] bytes) {
            return defineClass(null, bytes, 0, bytes.length);
        }
    }
}
