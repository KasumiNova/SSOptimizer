package github.kasuminova.ssoptimizer.standalone.remap;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

public class ClassLookupsTest {
    private static final String OLD = "isolated.Old", NEW = "named.Game";
    private static ClassLoader owned;
    private static int translated, initialized;

    public static String className(String name) {
        translated++;
        return name.replace(OLD, NEW);
    }

    public static boolean isGameClass(Class<?> cls) {
        if (cls == null) return false;
        while (cls.isArray()) cls = cls.getComponentType();
        return cls.getClassLoader() == owned;
    }

    public static void markInitialized() { initialized++; }

    static class Loader extends ClassLoader {
        private final List<String> provides;
        final List<String> requests = new ArrayList<>();
        Loader(String... provides) { super(ClassLookupsTest.class.getClassLoader()); this.provides = List.of(provides); }
        @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
            requests.add(name);
            if (!provides.contains(name)) throw new ClassNotFoundException(name);
            var w = new ClassWriter(0);
            w.visit(V17, name.equals("isolated.Private") ? 0 : ACC_PUBLIC, name.replace(".", "/"), null, "java/lang/Object", null);
            var m = w.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
            m.visitMethodInsn(INVOKESTATIC, Type.getInternalName(ClassLookupsTest.class), "markInitialized", "()V", false);
            m.visitInsn(RETURN); m.visitMaxs(0, 0);
            return define(name, w.toByteArray());
        }
        Class<?> define(String name, byte[] bytes) { return defineClass(name, bytes, 0, bytes.length); }
    }

    private static Class<?> fixture(Loader loader, boolean iface) {
        return fixture(loader, iface, V1_8);
    }

    private static Class<?> fixture(Loader loader, boolean iface, int version) {
        String name = iface ? "fixture.Interface" : "fixture.Probe";
        var node = new ClassNode();
        node.visit(version, ACC_PUBLIC | (iface ? ACC_INTERFACE | ACC_ABSTRACT : ACC_SUPER), name.replace(".", "/"), null,
                iface ? "java/lang/Object" : "java/lang/ClassLoader", null);
        String c = "Ljava/lang/Class;", s = "Ljava/lang/String;", l = "Ljava/lang/ClassLoader;", lookup = "java/lang/invoke/MethodHandles$Lookup";
        var existing = node.visitMethod(ACC_PUBLIC | ACC_STATIC, "$sso$classLookup$0", "(" + s + ")" + c, null, null);
        existing.visitInsn(ACONST_NULL); existing.visitInsn(ARETURN); existing.visitMaxs(1, 1);
        proxy(node, "implicit", INVOKESTATIC, "java/lang/Class", "forName", "(" + s + ")" + c, null);
        proxy(node, "explicit", INVOKESTATIC, "java/lang/Class", "forName", "(" + s + "Z" + l + ")" + c, null);
        proxy(node, "module", INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/Module;" + s + ")" + c, null);
        proxy(node, "loader", INVOKEVIRTUAL, "java/lang/ClassLoader", "loadClass", "(" + s + ")" + c, l);
        proxy(node, "lookup", INVOKEVIRTUAL, lookup, "findClass", "(" + s + ")" + c, "L" + lookup + ";");
        if (!iface) {
            var init = node.visitMethod(ACC_PUBLIC, "<init>", "(" + l + ")V", null, null);
            init.visitVarInsn(ALOAD, 0); init.visitVarInsn(ALOAD, 1);
            init.visitMethodInsn(INVOKESPECIAL, "java/lang/ClassLoader", "<init>", "(" + l + ")V", false);
            init.visitInsn(RETURN); init.visitMaxs(2, 2);
            proxy(node, "parent", INVOKESPECIAL, "java/lang/ClassLoader", "loadClass", "(" + s + "Z)" + c, "L" + node.name + ";");
        }
        ReflectionLookups.rewrite(node);
        for (var method : node.methods) for (var insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.endsWith("/reflect/ReflectionNames")) call.owner = Type.getInternalName(ClassLookupsTest.class);
        }
        var writer = new ClassWriter(0); node.accept(writer);
        return loader.define(name, writer.toByteArray());
    }

    private static void proxy(ClassNode node, String name, int op, String owner, String method, String descriptor, String receiver) {
        String desc = receiver == null ? descriptor : "(" + receiver + descriptor.substring(1);
        var m = node.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        Type[] args = Type.getArgumentTypes(desc);
        for (int i = 0; i < args.length; i++) m.visitVarInsn(args[i].getOpcode(ILOAD), i);
        m.visitMethodInsn(op, owner, method, descriptor, false); m.visitInsn(ARETURN); m.visitMaxs(args.length, args.length);
    }

    @Test void originalLookupWinsWithoutConsultingMappingAcrossClassAndInterfaceCallers() throws Exception {
        for (boolean iface : List.of(false, true)) {
            Loader foreign = new Loader(OLD, NEW);
            Class<?> caller = fixture(foreign, iface);
            owned = new Loader(NEW);
            translated = initialized = 0;
            Class<?> old = foreign.loadClass(OLD);
            assertSame(old, caller.getMethod("module", Module.class, String.class).invoke(null, foreign.getUnnamedModule(), OLD));
            assertSame(old, caller.getMethod("loader", ClassLoader.class, String.class).invoke(null, foreign, OLD));
            assertSame(old, caller.getMethod("explicit", String.class, boolean.class, ClassLoader.class).invoke(null, OLD, false, foreign));
            var lookup = MethodHandles.privateLookupIn(caller, MethodHandles.lookup());
            assertSame(old, caller.getMethod("lookup", MethodHandles.Lookup.class, String.class).invoke(null, lookup, OLD));
            assertEquals(0, initialized);
            if (!iface) {
                Object subclass = caller.getConstructor(ClassLoader.class).newInstance(foreign);
                assertSame(old, caller.getMethod("parent", caller, String.class, boolean.class).invoke(null, subclass, OLD, true));
            }
            assertSame(old, caller.getMethod("implicit", String.class).invoke(null, OLD));
            assertEquals(1, initialized);
            assertEquals(0, translated);
            assertFalse(foreign.requests.contains(NEW));
        }
    }

    @Test void fallbackChecksForeignIdentityWithoutInitializationAndHonorsRequestedInitialization() throws Exception {
        var game = new Loader(NEW);
        owned = game;
        var foreign = new Loader(NEW);
        Class<?> caller = fixture(game, false);
        var explicit = caller.getMethod("explicit", String.class, boolean.class, ClassLoader.class);
        initialized = 0;
        var failed = assertThrows(InvocationTargetException.class, () -> explicit.invoke(null, OLD, true, foreign));
        assertEquals(OLD, assertInstanceOf(ClassNotFoundException.class, failed.getCause()).getMessage());
        assertNull(caller.getMethod("module", Module.class, String.class).invoke(null, foreign.getUnnamedModule(), OLD));
        assertEquals(0, initialized);
        Class<?> arr = (Class<?>) explicit.invoke(null, "[[L" + OLD + ";", true, game);
        assertEquals(NEW, arr.getComponentType().getComponentType().getName());
        assertEquals(0, initialized);
        assertEquals(game.loadClass(NEW), explicit.invoke(null, OLD, false, game));
        assertEquals(0, initialized);
        Class<?> target = game.loadClass(NEW);
        assertSame(target, caller.getMethod("module", Module.class, String.class).invoke(null, game.getUnnamedModule(), OLD));
        assertSame(target, caller.getMethod("loader", ClassLoader.class, String.class).invoke(null, game, OLD));
        var lookup = MethodHandles.privateLookupIn(caller, MethodHandles.lookup());
        assertSame(target, caller.getMethod("lookup", MethodHandles.Lookup.class, String.class).invoke(null, lookup, OLD));
        Object subclass = caller.getConstructor(ClassLoader.class).newInstance(game);
        assertSame(target, caller.getMethod("parent", caller, String.class, boolean.class).invoke(null, subclass, OLD, true));
        assertEquals(0, initialized);
        assertSame(target, caller.getMethod("implicit", String.class).invoke(null, OLD));
        assertSame(target, explicit.invoke(null, OLD, true, game));
        assertNull(caller.getMethod("$sso$classLookup$0", String.class).invoke(null, OLD));
        assertEquals(1, initialized);
        foreign.requests.clear();
        assertThrows(InvocationTargetException.class, () -> explicit.invoke(null, "unmapped.Type", false, foreign));
        assertEquals(List.of("unmapped.Type"), foreign.requests);
    }

    @Test void linkageAndLookupAccessErrorsDoNotStartAnAliasLookup() throws Exception {
        var error = new NoClassDefFoundError("missing dependent type");
        var loader = new Loader("isolated.Private") {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (OLD.equals(name)) throw error;
                return super.findClass(name);
            }
        };
        Class<?> caller = fixture(loader, false);
        translated = 0;
        var failure = assertThrows(InvocationTargetException.class, () -> caller.getMethod("explicit", String.class, boolean.class, ClassLoader.class)
                .invoke(null, OLD, true, loader));
        assertSame(error, failure.getCause());
        var lookup = MethodHandles.privateLookupIn(caller, MethodHandles.lookup());
        var access = assertThrows(InvocationTargetException.class, () -> caller.getMethod("lookup", MethodHandles.Lookup.class, String.class)
                .invoke(null, lookup, "isolated.Private"));
        assertInstanceOf(IllegalAccessException.class, access.getCause());
        assertEquals(0, translated);
    }

    @Test void legacyCallerCanResolveAnImplicitAliasWithItsOwnLoader() throws Exception {
        var game = new Loader(NEW);
        owned = game;
        Class<?> caller = fixture(game, false, V1_4);
        assertSame(game.loadClass(NEW), caller.getMethod("implicit", String.class).invoke(null, OLD));
    }

    @Test void framedAndUnframedLegacyInterfaceInitializersStillVerify(@org.junit.jupiter.api.io.TempDir Path temp) throws Exception {
        Path tiny = Files.writeString(temp.resolve("test.tiny"), "tiny\t2\t0\tobf\tnamed\nc\tisolated/Old\tnamed/Game\n");
        for (int version : List.of(V1_6, V1_7)) {
            var w = new ClassWriter(0);
            w.visit(version, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, "old/Factory", null, "java/lang/Object", null);
            var init = w.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
            var end = new org.objectweb.asm.Label();
            init.visitInsn(ICONST_1); init.visitJumpInsn(IFEQ, end);
            init.visitLdcInsn(OLD);
            init.visitMethodInsn(INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false);
            init.visitInsn(POP); init.visitLabel(end);
            if (version == V1_7) init.visitFrame(F_SAME, 0, null, 0, null);
            init.visitInsn(RETURN); init.visitMaxs(1, 0);
            var loader = new Loader(OLD);
            byte[] result = BytecodeRepair.rewrite(w.toByteArray(), new ClassHierarchy(), 0, MappingProjection.read(tiny), true);
            loader.define("old.Factory", result);
            initialized = 0;
            Class.forName("old.Factory", true, loader);
            assertEquals(1, initialized);
        }
    }
}
