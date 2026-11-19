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

    @Test void honorsLiteralClasspathWhenSeveralSerializersAreInstalled() throws IOException {
        var game = game();
        Path stock = Files.createFile(game.core().resolve("xstream-1.4.10.jar"));
        Files.writeString(game.root().resolve("vmparams"), "java.exe -classpath game.jar;xstream-1.4.10.jar Main");
        assertEquals(stock.toRealPath(), game.serializer(null));
        Files.createFile(game.core().resolve("xstream-1.4.21_miko.jar"));
        assertEquals(stock.toRealPath(), game.serializer(null));
        assertEquals(stock.toRealPath(), game.serializer("stock"));
        Files.writeString(game.root().resolve("starsector.sh"), "java -cp xstream-1.4.10.jar Main");
        assertEquals(stock.toRealPath(), game.serializer(null));
        Path upstream = Files.createFile(game.core().resolve("xstream-1.4.21.jar"));
        Files.writeString(game.root().resolve("launch_nanoforge_ss.sh"), "# -cp xstream-1.4.10.jar\njava \\\n -classpath \"${NANOFORGE_CP}janino.jar:xstream-1.4.21.jar\" Main # -cp xstream-1.4.10.jar");
        assertEquals(upstream.toRealPath(), game.serializer(null));
    }

    @Test void requiresBundleInstallMarkerAndResolvesOfficialArgumentFileSeparators() throws IOException {
        var game = game();
        Path stock = Files.createFile(game.core().resolve("xstream-1.4.10.jar"));
        Path bundle = Files.createFile(Files.createDirectory(game.root().resolve("mikohime")).resolve("xstream-1.4.21_miko.jar"));
        assertEquals(stock.toRealPath(), game.serializer(null));
        assertThrows(IOException.class, () -> game.serializer("miko"));
        Files.createFile(game.root().resolve("Miko_Rouge.bat"));
        assertEquals(bundle.toRealPath(), game.serializer(null));
        Files.writeString(game.root().resolve("Miko_Simple.txt"), "-classpath janino.jar;..\\\\mikohime/xstream-1.4.21_miko.jar;xstream-1.4.10.jar");
        assertThrows(IOException.class, () -> game.serializer(null));
        assertEquals(bundle.toRealPath(), game.serializer("miko"));
        Files.writeString(game.root().resolve("Miko_Simple.txt"), "-classpath janino.jar;..\\\\mikohime/xstream-1.4.21_miko.jar");
        assertEquals(bundle.toRealPath(), game.serializer(null));
    }

    @Test @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void rejectsExternalScriptAndArchiveSymlinks() throws IOException {
        var game = game();
        Path outside = Files.createFile(temp.resolve("external.jar"));
        Files.createSymbolicLink(game.core().resolve("xstream-1.4.21_miko.jar"), outside);
        assertThrows(IOException.class, () -> game.serializer("core-miko"));
        Files.createFile(game.core().resolve("xstream-1.4.10.jar"));
        Files.createSymbolicLink(game.root().resolve("launch_nanoforge_ss.sh"), outside);
        assertThrows(IOException.class, () -> game.serializer(null));
        assertThrows(IOException.class, () -> game.serializer(outside.toString()));
    }
}
