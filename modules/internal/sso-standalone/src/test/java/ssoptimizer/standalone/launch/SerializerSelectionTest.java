package ssoptimizer.standalone.launch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SerializerSelectionTest {
    @TempDir Path temp;

    private GameInstall game() throws IOException {
        Path root = Files.createDirectory(temp.resolve("game space"));
        return new GameInstall(root, Files.createDirectory(root.resolve("starsector-core")), "test", List.of());
    }

    private static Path serializerJar(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        byte[] xstream = java.lang.classfile.ClassFile.of().build(java.lang.constant.ClassDesc.of("com.thoughtworks.xstream.XStream"), c -> c
                .withFlags(java.lang.classfile.ClassFile.ACC_PUBLIC | java.lang.classfile.ClassFile.ACC_ABSTRACT)
                .withMethod("fromXML", java.lang.constant.MethodTypeDesc.ofDescriptor("(Ljava/io/InputStream;)Ljava/lang/Object;"),
                        java.lang.classfile.ClassFile.ACC_PUBLIC | java.lang.classfile.ClassFile.ACC_ABSTRACT, m -> {})
                .withMethod("toXML", java.lang.constant.MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;Ljava/io/OutputStream;)V"),
                        java.lang.classfile.ClassFile.ACC_PUBLIC | java.lang.classfile.ClassFile.ACC_ABSTRACT, m -> {}));
        try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(path))) {
            out.putNextEntry(new java.util.jar.JarEntry("com/thoughtworks/xstream/XStream.class"));
            out.write(xstream);
            out.closeEntry();
        }
        return path.toRealPath();
    }

    @Test void resolvesQuotedClasspathAndIgnoresComments() throws IOException {
        var game = game();
        Path stock = serializerJar(game.core().resolve("xstream-original.jar"));
        Path customized = serializerJar(game.root().resolve("site libs #1/xstream-custom.jar"));
        Files.writeString(game.root().resolve("vmparams"), "java.exe -classpath \"janino.jar;..\\site libs #1\\xstream-custom.jar\" Main");
        assertEquals(customized, game.serializer(null));
        Files.writeString(game.root().resolve("launch_nanoforge_ss.sh"),
                "# -cp ../site-libs/xstream-custom.jar\njava \\\n -classpath \"${PREFIX}janino.jar:xstream-original.jar\" Main # -cp xstream-missing.jar");
        assertEquals(stock, game.serializer(null));
        assertEquals(customized, game.serializer(customized.toString()));
    }

    @Test void reportsAmbiguousLibrariesAndLegacyAliases() throws IOException {
        var game = game();
        Path stock = serializerJar(game.core().resolve("xstream-original.jar"));
        assertEquals(stock, game.serializer(null));
        serializerJar(game.core().resolve("xstream-custom.jar"));
        assertThrows(IOException.class, () -> game.serializer(null));
        assertTrue(assertThrows(IOException.class, () -> game.serializer("stock")).getMessage().contains("旧别名"));
        Path literal = serializerJar(game.core().resolve("stock"));
        assertEquals(literal, game.serializer("stock"));
        assertEquals(stock, game.serializer("xstream-original.jar"));
        Files.writeString(game.root().resolve("vmparams"), "-classpath xstream-original.jar;xstream-custom.jar");
        assertThrows(IOException.class, () -> game.serializer(null));
        Files.writeString(game.root().resolve("vmparams"), "-classpath xstream-missing.jar");
        assertThrows(IOException.class, () -> game.serializer(null));
    }

    @Test @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void rejectsExternalScriptAndArchiveSymlinks() throws IOException {
        var game = game();
        Path outside = serializerJar(temp.resolve("external.jar"));
        Files.createSymbolicLink(game.core().resolve("xstream-external.jar"), outside);
        assertThrows(IOException.class, () -> game.serializer("xstream-external.jar"));
        serializerJar(game.core().resolve("xstream-original.jar"));
        Files.createSymbolicLink(game.root().resolve("launch_nanoforge_ss.sh"), outside);
        assertThrows(IOException.class, () -> game.serializer(null));
        assertEquals(outside, game.serializer(outside.toString()));
        Path invalid = Files.createFile(temp.resolve("invalid.jar"));
        assertTrue(assertThrows(IOException.class, () -> game.serializer(invalid.toString())).getMessage().contains("ABI"));
    }
}
