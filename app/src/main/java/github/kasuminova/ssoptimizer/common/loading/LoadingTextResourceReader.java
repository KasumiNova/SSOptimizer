package github.kasuminova.ssoptimizer.common.loading;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Fast UTF-8 reader used to replace LoadingUtils' per-line text-loading path.
 */
public final class LoadingTextResourceReader {
    private static final byte UTF8_BOM_0 = (byte) 0xEF;
    private static final byte UTF8_BOM_1 = (byte) 0xBB;
    private static final byte UTF8_BOM_2 = (byte) 0xBF;

    private LoadingTextResourceReader() {
    }

    public static String read(final InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return null;
        }

        try (InputStream in = inputStream) {
            final byte[] bytes = in.readAllBytes();
            int offset = hasUtf8Bom(bytes) ? 3 : 0;
            String text = new String(bytes, offset, bytes.length - offset, StandardCharsets.UTF_8);
            if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
                text = text.substring(1);
            }
            return normalizeLineEndings(text);
        }
    }

    private static boolean hasUtf8Bom(final byte[] bytes) {
        return bytes.length >= 3
                && bytes[0] == UTF8_BOM_0
                && bytes[1] == UTF8_BOM_1
                && bytes[2] == UTF8_BOM_2;
    }

    private static String normalizeLineEndings(final String text) {
        if (text.indexOf('\r') < 0) {
            return text;
        }
        return text.replace("\r\n", "\n").replace("\r", "\n");
    }
}