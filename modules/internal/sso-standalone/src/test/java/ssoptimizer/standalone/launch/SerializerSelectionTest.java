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
        return Files.createFile(path).toRealPath();
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

    @Test void reportsAmbiguousLibraries() throws IOException {
        var game = game();
        Path stock = serializerJar(game.core().resolve("xstream-original.jar"));
        assertEquals(stock, game.serializer(null));
        serializerJar(game.core().resolve("xstream-custom.jar"));
        assertThrows(IOException.class, () -> game.serializer(null));
        Path literal = serializerJar(game.core().resolve("stock"));
        assertEquals(literal, game.serializer("stock"));
        assertEquals(stock, game.serializer("xstream-original.jar"));
        Files.writeString(game.root().resolve("vmparams"), "-classpath xstream-original.jar;xstream-custom.jar");
        assertThrows(IOException.class, () -> game.serializer(null));
        Files.writeString(game.root().resolve("vmparams"), "-classpath xstream-missing.jar");
        assertThrows(IOException.class, () -> game.serializer(null));
    }

}
