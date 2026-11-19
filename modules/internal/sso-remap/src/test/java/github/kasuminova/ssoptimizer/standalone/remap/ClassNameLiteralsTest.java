package github.kasuminova.ssoptimizer.standalone.remap;

import github.kasuminova.ssoptimizer.standalone.remap.reflect.ReflectionNames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

class ClassNameLiteralsTest {
    @TempDir Path directory;

    @Test void comparesGameAliasesAndKeepsOriginalMatchesFromPrivateLoaders() throws Throwable {
        Path gameJar = jar("game.jar", "game/NamedOne", "game/NamedTwo");
        Path privateJar = jar("private.jar", "game/NamedOne", "legacy/Aa");
        Path tiny = mapping();
        try (var game = new URLClassLoader(new URL[]{gameJar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
             var foreign = new URLClassLoader(new URL[]{privateJar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
             var scope = new FixtureLoader()) {
            Class<?> gameOne = game.loadClass("game.NamedOne");
            Class<?> gameTwo = game.loadClass("game.NamedTwo");
            Class<?> foreignNamed = foreign.loadClass("game.NamedOne");
            Class<?> foreignOld = foreign.loadClass("legacy.Aa");
            Class<?> names = scope.loadClass(ReflectionNames.class.getName());
            MethodHandles.publicLookup().findStatic(names, "initialize",
                    MethodType.methodType(void.class, Path.class, List.class, ClassLoader.class))
                    .invokeExact(tiny, List.of(gameJar), (ClassLoader) game);
            var fixture = fixture(scope, tiny);
            var compare = fixture.findStatic(fixture.lookupClass(), "compare", MethodType.methodType(boolean.class, Class.class, String[].class));
            var values = new String[3];
            assertTrue((boolean) compare.invokeExact(gameOne, values));
            assertEquals("legacy.Aa", values[0]);
            assertEquals(gameOne.getName(), values[1]);
            assertEquals(gameOne.getName(), values[2]);
            assertFalse((boolean) compare.invokeExact(gameTwo, values));
            assertTrue((boolean) compare.invokeExact(foreignOld, values));
            assertFalse((boolean) compare.invokeExact(foreignNamed, values));

            var choose = fixture.findStatic(fixture.lookupClass(), "choose", MethodType.methodType(int.class, Class.class));
            assertEquals(1, (int) choose.invokeExact(gameOne));
            assertEquals(2, (int) choose.invokeExact(gameTwo));
            assertEquals(1, (int) choose.invokeExact(foreignOld));
            assertEquals(3, (int) choose.invokeExact(foreignNamed));
            var mixedName = fixture.findStatic(fixture.lookupClass(), "mixedName",
                    MethodType.methodType(boolean.class, Class.class, boolean.class, String.class));
            var mixedLiteral = fixture.findStatic(fixture.lookupClass(), "mixedLiteral",
                    MethodType.methodType(boolean.class, Class.class, boolean.class, String.class));
            assertTrue((boolean) mixedName.invokeExact(gameOne, false, "legacy.Aa"));
            assertFalse((boolean) mixedName.invokeExact(gameOne, true, gameOne.getName()));
            assertTrue((boolean) mixedLiteral.invokeExact(gameOne, false, gameOne.getName()));
            assertFalse((boolean) mixedLiteral.invokeExact(gameOne, false, "legacy.Aa"));
            assertFalse((boolean) mixedLiteral.invokeExact(gameOne, true, "unused"));
        }
    }

    @Test void matchesObjectArrayNamesWithoutMatchingDescriptorsOrTextFragments() throws Exception {
        MappingProjection mapping = MappingProjection.read(mapping());
        assertEquals("[[Lgame.NamedOne;", ClassNameLiterals.binaryName("[[Llegacy.Aa;", mapping));
        assertEquals("Llegacy/Aa;", ClassNameLiterals.binaryName("Llegacy/Aa;", mapping));
        assertEquals("[I", ClassNameLiterals.binaryName("[I", mapping));
        assertEquals("[Llegacy.Aa;;", ClassNameLiterals.binaryName("[Llegacy.Aa;;", mapping));
        assertEquals("selected legacy.Aa", ClassNameLiterals.binaryName("selected legacy.Aa", mapping));
    }

    private Path mapping() throws Exception {
        Path file = directory.resolve("mapping.tiny");
        Files.writeString(file, "tiny\t2\t0\tobf\tnamed\nc\tlegacy/Aa\tgame/NamedOne\nc\tlegacy/BB\tgame/NamedTwo\n");
        return file;
    }

    private Path jar(String file, String... classes) throws Exception {
        Path path = directory.resolve(file);
        try (var out = new JarOutputStream(Files.newOutputStream(path))) {
            for (String name : classes) {
                var writer = new ClassWriter(0);
                writer.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
                writer.visitEnd();
                out.putNextEntry(new JarEntry(name + ".class"));
                out.write(writer.toByteArray());
                out.closeEntry();
            }
        }
        return path;
    }

    private MethodHandles.Lookup fixture(FixtureLoader scope, Path tiny) throws Exception {
        try (var classFile = Fixture.class.getResourceAsStream("ClassNameLiteralsTest$Fixture.class")) {
            byte[] rewritten = BytecodeRepair.rewrite(classFile.readAllBytes(), new ClassHierarchy(), 0, MappingProjection.read(tiny), true);
            return MethodHandles.privateLookupIn(scope.defineFixture(rewritten), MethodHandles.lookup());
        }
    }

    private static final class FixtureLoader extends URLClassLoader {
        FixtureLoader() {
            super(new URL[]{ReflectionNames.class.getProtectionDomain().getCodeSource().getLocation()}, FixtureLoader.class.getClassLoader());
        }

        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith(ReflectionNames.class.getPackageName() + ".")) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name);
                if (type == null) type = findClass(name);
                if (resolve) resolveClass(type);
                return type;
            }
        }

        Class<?> defineFixture(byte[] bytes) { return defineClass(Fixture.class.getName(), bytes, 0, bytes.length); }
    }

    static class Fixture {
        static boolean compare(Class<?> type, String[] values) {
            String id = "legacy.Aa";
            String raw = type.getName();
            values[0] = id;
            values[1] = raw;
            Function<Class<?>, String> reference = Class::getName;
            values[2] = reference.apply(type);
            return Objects.equals(id, raw) && type.getName().equals(id);
        }

        static int choose(Class<?> type) {
            return switch (type.getName()) {
                case "legacy.Aa" -> 1;
                case "legacy.BB" -> 2;
                default -> 3;
            };
        }

        static boolean mixedName(Class<?> type, boolean inputClassName, String supplied) {
            String name = inputClassName ? type.getName() : supplied;
            return name.equals("legacy.Aa");
        }

        static boolean mixedLiteral(Class<?> type, boolean inputLiteral, String supplied) {
            String literal = inputLiteral ? "legacy.Aa" : supplied;
            return type.getName().equals(literal);
        }
    }
}
