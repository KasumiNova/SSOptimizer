package github.kasuminova.ssoptimizer.common.loading;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LoadingTextResourceReaderTest {
    @Test
    void stripsUtf8BomAndNormalizesWindowsLineEndings() throws IOException {
        byte[] text = {
                (byte) 0xEF, (byte) 0xBB, (byte) 0xBF,
                'a', '\r', '\n', 'b', '\r', 'c', '\n'
        };

        String loaded = LoadingTextResourceReader.read(new ByteArrayInputStream(text));

        assertEquals("a\nb\nc\n", loaded);
    }

    @Test
    void returnsNullForNullStreams() throws IOException {
        assertNull(LoadingTextResourceReader.read(null));
    }

    @Test
    void keepsUtf8ContentIntact() throws IOException {
        String source = "超空间\n贴图\n";

        String loaded = LoadingTextResourceReader.read(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)));

        assertEquals(source, loaded);
    }
}