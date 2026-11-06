package github.kasuminova.ssoptimizer.modopt.dcr;

import com.thoughtworks.xstream.XStream;
import data.scripts.combatanalytics.data.CombatResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DcrCompressionHelperTest {

    private static final String SAMPLE =
            "<list><CombatResult id=\"abc-123\" t=\"1700000000000\"><Ship dp=\"12\" rhp=\"0.5\"/>"
                    + "<WSD s=\"a\" t=\"b\" w=\"reaper\" h=\"3\" sd=\"0.0\" ad=\"1200.5\" hd=\"3400.0\"/></CombatResult></list>";

    @Test
    void zstdRoundTripReturnsOriginal() {
        final byte[] compressed = DcrCompressionHelper.compress(SAMPLE);
        assertEquals(SAMPLE, DcrCompressionHelper.decompress(compressed));
    }

    @Test
    void compressEmitsZstdFrameMagic() {
        final byte[] compressed = DcrCompressionHelper.compress(SAMPLE);
        assertTrue(compressed.length >= 4, "zstd frame too short");
        assertEquals((byte) 0x28, compressed[0]);
        assertEquals((byte) 0xB5, compressed[1]);
        assertEquals((byte) 0x2F, compressed[2]);
        assertEquals((byte) 0xFD, compressed[3]);
    }

    @Test
    void decompressReadsLegacyDeflateBytes() {
        final byte[] legacy = dcrLegacyCompress(SAMPLE);
        assertFalse(looksLikeZstd(legacy), "legacy deflate must not collide with zstd magic");
        assertEquals(SAMPLE, DcrCompressionHelper.decompress(legacy));
    }

    @Test
    void emptyStringRoundTrips() {
        assertEquals("", DcrCompressionHelper.decompress(DcrCompressionHelper.compress("")));
    }

    @Test
    void decompressReadsLegacyEmpty() {
        assertEquals("", DcrCompressionHelper.decompress(dcrLegacyCompress("")));
    }


    @Test
    void decompressRejectsSubFourByteGarbage() {
        assertThrows(IllegalStateException.class,
                () -> DcrCompressionHelper.decompress(new byte[]{1, 2, 3}));
    }

    @Test
    void decompressRejectsTruncatedLegacyDeflate() {
        final byte[] full = dcrLegacyCompress(SAMPLE);
        final byte[] truncated = Arrays.copyOf(full, full.length / 2);
        assertThrows(IllegalStateException.class,
                () -> DcrCompressionHelper.decompress(truncated));
    }

    @Test
    void decompressRejectsRandomNonZstdBytes() {
        assertThrows(IllegalStateException.class,
                () -> DcrCompressionHelper.decompress(new byte[]{(byte) 0xAB, (byte) 0xCD, (byte) 0xEF, 0x12, 0x34}));
    }


    @Test
    void flushShapedXStreamXmlSurvivesZstdRoundTrip() {
        final XStream xstream = new XStream();
        final List<CombatResult> results = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            results.add(new CombatResult("battle-" + i));
        }
        final String xml = xstream.toXML(results);
        assertEquals(xml, DcrCompressionHelper.decompress(DcrCompressionHelper.compress(xml)),
                "flush 序列化产物经 Zstd 压缩→解压应无损");
    }

    private static byte[] dcrLegacyCompress(final String text) {
        final byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        final Deflater deflater = new Deflater(9);
        deflater.setInput(raw);
        deflater.finish();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[raw.length + 100];
        while (!deflater.finished()) {
            final int written = deflater.deflate(buffer);
            out.write(buffer, 0, written);
        }
        deflater.end();
        return out.toByteArray();
    }

    private static boolean looksLikeZstd(final byte[] b) {
        return b.length >= 4
                && b[0] == (byte) 0x28 && b[1] == (byte) 0xB5 && b[2] == (byte) 0x2F && b[3] == (byte) 0xFD;
    }
}
