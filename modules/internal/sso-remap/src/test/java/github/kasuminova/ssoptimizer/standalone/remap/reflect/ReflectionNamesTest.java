package github.kasuminova.ssoptimizer.standalone.remap.reflect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;

import java.lang.invoke.MethodType;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

class ReflectionNamesTest {
    @TempDir Path root;

    @Test void mapsOnlyExactMountedDeclarationsWithoutInitializingOrInvokingTheTarget() throws Exception {
        Path game = root.resolve("game jar.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(game))) {
            write(out, "game/Face", "java/lang/Object", ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, new String[0], w ->
                    w.visitMethod(ACC_ABSTRACT | ACC_PUBLIC, "display", "()Ljava/lang/String;", null, null).visitEnd());
            write(out, "game/Base", "java/lang/Object", ACC_PUBLIC | ACC_ABSTRACT, new String[]{"game/Face"}, w -> {
                w.visitField(ACC_PUBLIC, "count", "I", null, null).visitEnd();
                w.visitField(ACC_PUBLIC, "value", "J", null, null).visitEnd();
                w.visitField(ACC_PRIVATE, "secret", "I", null, null).visitEnd();
                w.visitField(ACC_PUBLIC, "q", "I", null, null).visitEnd();
                w.visitField(ACC_PUBLIC, "alternate", "I", null, null).visitEnd();
                for (String n : List.of("key", "other")) w.visitMethod(ACC_ABSTRACT | ACC_PUBLIC, n, "(I)Lgame/Base;", null, null).visitEnd();
                w.visitMethod(ACC_ABSTRACT | ACC_PUBLIC, "overloaded", "(J)V", null, null).visitEnd();
                w.visitMethod(ACC_ABSTRACT | ACC_PUBLIC, "wide", "(I)Ljava/lang/Object;", null, null).visitEnd();
            });
            write(out, "game/Sub", "game/Base", ACC_PUBLIC | ACC_ABSTRACT, new String[0], w -> {});
            out.putNextEntry(new JarEntry("game/Damaged.class")); out.write(new byte[]{1}); out.closeEntry();
            out.putNextEntry(new JarEntry("custom/Unmapped.class")); out.write(new byte[]{1}); out.closeEntry();
        }
        Path external = root.resolve("mod-child.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(external))) {
            write(out, "mod/Sub", "game/Base", ACC_PUBLIC | ACC_ABSTRACT, new String[0], w -> {});
            write(out, "mod/Shadow", "game/Base", ACC_PUBLIC | ACC_ABSTRACT, new String[0], w -> {
                w.visitField(ACC_PRIVATE, "count", "I", null, null).visitEnd();
                w.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "b", "(J)V", null, null).visitEnd();
            });
        }
        Path spoof = Files.copy(game, root.resolve("mod.jar"));
        Path tiny = root.resolve("mapping.gz");
        try (var out = new GZIPOutputStream(Files.newOutputStream(tiny))) {
            out.write(("tiny\t2\t0\tobf\tnamed\nc\tx/F\tgame/Face\n\tm\t()Ljava/lang/String;\td\tdisplay\n"
                    + "c\tx/B\tgame/Base\n\tf\tI\ta\tcount\n\tf\tJ\ta\tvalue\n\tf\tI\ts\tsecret\n\tf\tI\tq\talternate\n"
                    + "\tm\t(I)Lx/B;\tb\tkey\n\tm\t(J)V\tb\toverloaded\n\tm\t(I)Ljava/lang/Object;\tb\twide\n"
                    + "c\tx/S\tgame/Sub\nc\tx/Absent\tgame/Absent\nc\tx/Bad\tgame/Damaged\n").getBytes(StandardCharsets.UTF_8));
        }
        URL archive = game.toUri().toURL();
        try (var launch = new URLClassLoader(new URL[]{archive}, ClassLoader.getPlatformClassLoader());
             var foreign = new URLClassLoader(new URL[]{archive}, ClassLoader.getPlatformClassLoader());
             var mod = new URLClassLoader(new URL[]{external.toUri().toURL()}, launch);
             var wrongSource = new URLClassLoader(new URL[]{spoof.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            ReflectionNames.initialize(tiny, List.of(game), launch);
            Class<?> base = Class.forName("game.Base", false, launch), sub = Class.forName("game.Sub", false, launch);
            assertEquals("game.Base", ReflectionNames.className("x.B"));
            assertEquals("game.Base", ReflectionNames.className("game.Base"));
            assertEquals("[[Lgame.Base;", ReflectionNames.className("[[Lx.B;"));
            assertEquals("x.Absent", ReflectionNames.className("x.Absent"));
            assertEquals("x.Bad", ReflectionNames.className("x.Bad"));
            var child = mod.loadClass("mod.Sub");
            assertEquals("count", ReflectionNames.fieldName(child, "a", int.class));
            assertEquals("overloaded", ReflectionNames.methodName(child, "b", new Class<?>[]{long.class}));
            var shadow = mod.loadClass("mod.Shadow");
            assertEquals("a", ReflectionNames.fieldName(shadow, "a", int.class));
            assertEquals("b", ReflectionNames.methodName(shadow, "b", new Class<?>[]{long.class}));
            assertEquals("count", ReflectionNames.fieldName(sub, "a", int.class));
            assertEquals("value", ReflectionNames.fieldName(base, "a", long.class));
            assertEquals("a", ReflectionNames.fieldName(base, "a"));
            assertEquals("q", ReflectionNames.fieldName(base, "q", int.class));
            assertEquals("secret", ReflectionNames.declaredFieldName(base, "s"));
            assertEquals("s", ReflectionNames.fieldName(base, "s"));
            assertEquals("s", ReflectionNames.declaredFieldName(sub, "s"));
            assertEquals("d", ReflectionNames.declaredMethodName(sub, "d", new Class<?>[0]));
            assertEquals("b", ReflectionNames.declaredMethodName(child, "b", new Class<?>[]{long.class}));
            assertEquals("s", ReflectionNames.fieldName(sub, "s"));
            assertEquals("display", ReflectionNames.methodName(sub, "d", (Class<?>[]) null));
            assertEquals("key", ReflectionNames.methodName(sub, "b", MethodType.methodType(base, int.class)));
            assertEquals("b", ReflectionNames.methodName(sub, "b", new Class<?>[]{int.class}));
            assertEquals("overloaded", ReflectionNames.methodName(sub, "b", new Class<?>[]{long.class}));
            var member = base.getMethod("key", int.class);
            assertTrue(ReflectionNames.matches(member, "b"));
            assertTrue(ReflectionNames.comparison(member, "b", "key", false));
            assertFalse(ReflectionNames.comparison(member, "b", "custom", false));
            assertFalse(ReflectionNames.comparison(member, "b", null, false));
            assertTrue(ReflectionNames.comparison(null, null, null, true));
            assertFalse(ReflectionNames.matches("not a member", "b"));
            assertEquals("a", ReflectionNames.fieldName(foreign.loadClass("game.Base"), "a", int.class));
            assertFalse(ReflectionIndex.read(tiny, List.of(game), wrongSource).owns(wrongSource.loadClass("game.Base")));
        }
    }

    private static void write(JarOutputStream out, String name, String parent, int flags, String[] interfaces, Consumer<ClassWriter> body) throws Exception {
        var writer = new ClassWriter(0);
        writer.visit(V17, flags, name, null, parent, interfaces);
        body.accept(writer);
        writer.visitEnd();
        out.putNextEntry(new JarEntry(name + ".class")); out.write(writer.toByteArray()); out.closeEntry();
    }
}
