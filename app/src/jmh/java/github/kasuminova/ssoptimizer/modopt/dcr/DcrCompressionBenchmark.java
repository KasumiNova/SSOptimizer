package github.kasuminova.ssoptimizer.modopt.dcr;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;

/**
 * DCR 战报压缩内核基准：对比 DCR 原版 {@link Deflater} 级别 9 与 {@link DcrCompressionHelper} 的 Zstd，
 * 为 L2（读档/存档热点压缩段 Deflate→Zstd）的收益提供可量化依据。
 * <p>
 * 负载为重复性较高的伪战报 XML（接近真实序列化产物的可压缩特征），按规模参数化。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
public class DcrCompressionBenchmark {

    @State(Scope.Thread)
    public static class Payload {
        /** 战报条数（决定 XML 规模）。 */
        @Param({"50", "200", "800"})
        public int combatResults;

        private String xml;

        @Setup(Level.Trial)
        public void setup() {
            final StringBuilder sb = new StringBuilder(combatResults * 256);
            sb.append("<list>");
            for (int i = 0; i < combatResults; i++) {
                sb.append("<CombatResult id=\"battle-").append(i)
                        .append("\" t=\"17000000000").append(i % 100).append("\">")
                        .append("<Ship dp=\"").append(i % 60).append("\" rhp=\"0.").append(i % 100)
                        .append("\" mhp=\"12000.0\" cs=\"graphics/portraits/portrait").append(i % 32).append(".png\"/>")
                        .append("<WSD s=\"ship_a\" t=\"ship_b\" w=\"reaperlauncher\" h=\"")
                        .append(i % 9).append("\" sd=\"0.0\" ad=\"1200.5\" hd=\"3400.0\" ed=\"0.0\" p=\"0.")
                        .append(i % 100).append("\" k=\"false\"/>")
                        .append("</CombatResult>");
            }
            sb.append("</list>");
            this.xml = sb.toString();
        }
    }

    @Benchmark
    public void deflate9_dcrLegacy(final Payload p, final Blackhole bh) {
        bh.consume(legacyDeflate(p.xml));
    }

    @Benchmark
    public void zstd_ssoptimizer(final Payload p, final Blackhole bh) {
        bh.consume(DcrCompressionHelper.compress(p.xml));
    }

    /** 逐字节复刻 DCR {@code CompressionUtil.compress}（Deflater 级别 9）。 */
    private static byte[] legacyDeflate(final String text) {
        final byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        final Deflater deflater = new Deflater(9);
        deflater.setInput(raw);
        deflater.finish();
        final ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 3));
        final byte[] buffer = new byte[raw.length + 100];
        while (!deflater.finished()) {
            final int written = deflater.deflate(buffer);
            out.write(buffer, 0, written);
        }
        deflater.end();
        return out.toByteArray();
    }
}
