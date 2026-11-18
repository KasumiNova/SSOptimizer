package github.kasuminova.ssoptimizer.standalone.remap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

class NamedCompatibilityTest {
    @TempDir Path root;

    @Test void inheritedFieldInterfaceDefaultBridgeAndObjectMethodsResolve() throws Exception {
        Path game = game();
        Path mod = calls("ok", v -> {
            v.visitFieldInsn(GETSTATIC, "mod/Sub", "VERSION", "I"); v.visitInsn(POP);
            v.visitLdcInsn(new Handle(H_INVOKEVIRTUAL, "mod/Sub", "label", "()Ljava/lang/String;", false)); v.visitInsn(POP);
            v.visitLdcInsn(new Handle(H_INVOKEVIRTUAL, "mod/Sub", "bridge", "()Ljava/lang/Object;", false)); v.visitInsn(POP);
            v.visitLdcInsn(new Handle(H_INVOKEINTERFACE, "game/Feature", "toString", "()Ljava/lang/String;", true)); v.visitInsn(POP);
            v.visitLdcInsn("game.Missing"); v.visitInsn(POP);
        });
        assertDoesNotThrow(() -> NamedCompatibility.verify(List.of(game), List.of(mod), List.of()));
    }

    @Test void missingMemberIncludesCallerOwnerKindAndDescriptor() throws Exception {
        Path game = game();
        Path mod = calls("stale", v -> v.visitMethodInsn(INVOKEVIRTUAL, "mod/Sub", "label", "()I", false));
        IOException error = assertThrows(IOException.class, () -> NamedCompatibility.verify(List.of(game), List.of(mod), List.of()));
        assertTrue(error.getMessage().contains("mod/Sub.run()V"));
        assertTrue(error.getMessage().contains("mod/Sub.label()I"));
    }

    @Test void fieldStaticnessConstructorsAndInterfaceTagAreChecked() throws Exception {
        Path game = game();
        List<Consumer<MethodVisitor>> forms = List.of(
                v -> v.visitFieldInsn(GETFIELD, "game/Base", "VERSION", "I"),
                v -> v.visitMethodInsn(INVOKESPECIAL, "mod/Sub", "<init>", "(I)V", false),
                v -> v.visitMethodInsn(INVOKEVIRTUAL, "game/Feature", "label", "()Ljava/lang/String;", false),
                v -> v.visitMethodInsn(INVOKESTATIC, "game/Base", "label", "()Ljava/lang/String;", false),
                v -> v.visitTypeInsn(CHECKCAST, "[Lgame/Removed;"),
                v -> v.visitLdcInsn(new Handle(H_GETSTATIC, "game/Base", "VERSION", "J", false)));
        for (int i = 0; i < forms.size(); i++) {
            Path mod = calls("bad" + i, forms.get(i));
            assertThrows(IOException.class, () -> NamedCompatibility.verify(List.of(game), List.of(mod), List.of()));
        }
    }

    @Test void descriptorMarkersMustMatchButUnmarkedInputStillLinks() throws Exception {
        Map<String, String> markers = Map.of("SSOptimizer-Namespace", "named", "SSOptimizer-Mapping-SHA256", "current", "SSOptimizer-Remap-ABI", "v1");
        var empty = Map.of("game/Keep", type("game/Keep", "java/lang/Object", ACC_PUBLIC, new String[0], c -> {}));
        Path game = archive("marked-game", empty, markers);
        Path mod = archive("plain", Map.of(), Map.of());
        NamedCompatibility.verify(List.of(game), List.of(mod), List.of());
        for (String key : List.of("SSOptimizer-Mapping-SHA256", "SSOptimizer-Remap-ABI")) {
            Path bad = archive(key, Map.of(), Map.of(key, "stale"));
            assertTrue(assertThrows(IOException.class, () -> NamedCompatibility.verify(List.of(game), List.of(bad), List.of())).getMessage().contains(key));
        }
    }

    @Test void externalInheritedMembersUseActualDependencyAndOnlyEffectiveClassesAreChecked() throws Exception {
        Path game = archive("subgame", Map.of("game/Child", type("game/Child", "vendor/Parent", ACC_PUBLIC, new String[0], c -> {})), Map.of());
        byte[] parent = type("vendor/Parent", "java/lang/Object", ACC_PUBLIC, new String[0], c ->
                c.visitField(ACC_PUBLIC | ACC_STATIC, "state", "I", null, null).visitEnd());
        Path dep = archive("dependency", Map.of("vendor/Parent", parent), Map.of());
        byte[] primary = type("mod/Shared", "game/Child", ACC_PUBLIC, new String[0], c -> {
            var m = c.visitMethod(ACC_PUBLIC | ACC_STATIC, "test", "()I", null, null);
            m.visitCode(); m.visitFieldInsn(GETSTATIC, "game/Child", "state", "I"); m.visitInsn(IRETURN); m.visitMaxs(1, 0); m.visitEnd();
        });
        Path first = archive("first", Map.of("mod/Shared", primary), Map.of());
        Path shadowed = archive("second", Map.of("mod/Shared", type("mod/Shared", "game/Removed", ACC_PUBLIC, new String[0], c -> {})), Map.of());
        NamedCompatibility.verify(List.of(game), List.of(first, shadowed), List.of(game, dep, first, shadowed));
    }

    @Test void concreteModMustImplementTheGamesCurrentAbstractMethods() throws Exception {
        Path game = game();
        Path stale = archive("old-implementor", Map.of("mod/Impl", type("mod/Impl", "game/Base", ACC_PUBLIC,
                new String[0], c -> c.visitMethod(ACC_PUBLIC | ACC_NATIVE, "renamedBridge", "()Ljava/lang/Object;", null, null).visitEnd())), Map.of());
        assertTrue(assertThrows(IOException.class, () -> NamedCompatibility.verify(List.of(game), List.of(stale), List.of()))
                .getMessage().contains("game/Base.bridge()Ljava/lang/Object;"));
    }

    @Test void publicMethodCanExposePackagePrivateTypeWithoutAccessingIt() throws Exception {
        Path game = archive("hidden-signature", Map.of("game/Hidden", type("game/Hidden", "java/lang/Object", 0, new String[0], c -> {}),
                "game/Base", type("game/Base", "java/lang/Object", ACC_PUBLIC, new String[0], c ->
                        c.visitMethod(ACC_PUBLIC | ACC_NATIVE | ACC_STATIC, "detail", "()Lgame/Hidden;", null, null).visitEnd())), Map.of());
        Path mod = calls("public-call", v -> { v.visitMethodInsn(INVOKESTATIC, "game/Base", "detail", "()Lgame/Hidden;", false); v.visitInsn(POP); });
        NamedCompatibility.verify(List.of(game), List.of(mod), List.of());
    }

    private Path game() throws IOException {
        byte[] iface = type("game/Feature", "java/lang/Object", ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, new String[0], c -> {
            c.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "VERSION", "I", null, 1).visitEnd();
            var m = c.visitMethod(ACC_PUBLIC, "label", "()Ljava/lang/String;", null, null);
            m.visitCode(); m.visitLdcInsn("ok"); m.visitInsn(ARETURN); m.visitMaxs(1, 1); m.visitEnd();
        });
        byte[] base = type("game/Base", "java/lang/Object", ACC_PUBLIC | ACC_ABSTRACT, new String[]{"game/Feature"}, c -> {
            c.visitMethod(ACC_PUBLIC | ACC_ABSTRACT | ACC_BRIDGE | ACC_SYNTHETIC, "bridge", "()Ljava/lang/Object;", null, null).visitEnd();
            var init = c.visitMethod(ACC_PUBLIC, "<init>", "(I)V", null, null); init.visitCode();
            init.visitVarInsn(ALOAD, 0); init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            init.visitInsn(RETURN); init.visitMaxs(1, 2); init.visitEnd();
        });
        return archive("game", Map.of("game/Base", base, "game/Feature", iface), Map.of());
    }

    private Path calls(String file, Consumer<MethodVisitor> calls) throws IOException {
        byte[] b = type("mod/Sub", "game/Base", ACC_PUBLIC | ACC_ABSTRACT, new String[0], c -> {
            var m = c.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "()V", null, null);
            m.visitCode(); calls.accept(m); m.visitInsn(RETURN); m.visitMaxs(8, 1); m.visitEnd();
        });
        return archive(file, Map.of("mod/Sub", b), Map.of());
    }

    private Path archive(String name, Map<String, byte[]> classes, Map<String, String> attributes) throws IOException {
        Path path = root.resolve(name + ".jar");
        var manifest = new Manifest(); manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.forEach(manifest.getMainAttributes()::putValue);
        try (var out = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            for (var entry : classes.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey() + ".class")); out.write(entry.getValue()); out.closeEntry();
            }
        }
        return path;
    }

    private static byte[] type(String name, String parent, int access, String[] interfaces, Consumer<ClassWriter> body) {
        ClassWriter writer = new ClassWriter(0); writer.visit(Opcodes.V17, access, name, null, parent, interfaces);
        body.accept(writer); writer.visitEnd(); return writer.toByteArray();
    }
}
