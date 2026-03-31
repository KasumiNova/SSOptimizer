package github.kasuminova.ssoptimizer.common.loading;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
public class PngDecodeBenchmark {
    private static void consume(final NativeDecodedImage decoded, final Blackhole blackhole) {
        blackhole.consume(decoded.width());
        blackhole.consume(decoded.height());
        blackhole.consume(decoded.argbPixels().length);
        blackhole.consume(decoded.argbPixels()[decoded.argbPixels().length / 2]);
    }

    private static void consume(final BufferedImage image, final Blackhole blackhole) {
        blackhole.consume(image.getWidth());
        blackhole.consume(image.getHeight());
        blackhole.consume(image.getRGB(image.getWidth() / 2, image.getHeight() / 2));
    }

    @Benchmark
    public void nativeBridgeStub(final DecodeState state, final Blackhole blackhole) {
        NativeDecodedImage decoded = NativePngDecoder.benchmarkBridge(state.pngBytes, state.size, state.size);
        consume(decoded, blackhole);
    }

    @Benchmark
    public void nativeDecodeRaw(final DecodeState state, final Blackhole blackhole) throws IOException {
        NativeDecodedImage decoded = NativePngDecoder.decodeRaw(state.pngBytes);
        consume(decoded, blackhole);
    }

    @Benchmark
    public void nativeDecodeBufferedImage(final DecodeState state, final Blackhole blackhole) throws IOException {
        BufferedImage image = NativePngDecoder.decode(state.pngBytes);
        consume(image, blackhole);
    }

    @Benchmark
    public void imageIo(final DecodeState state, final Blackhole blackhole) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(state.pngBytes));
        consume(image, blackhole);
    }

    @State(Scope.Thread)
    public static class DecodeState {
        @Param({"64", "256", "1024"})
        int size;

        byte[] pngBytes;

        @Setup(Level.Trial)
        public void setup() throws IOException {
            if (!NativePngDecoder.isAvailable()) {
                throw new IllegalStateException("Native PNG decoder is unavailable for benchmark execution");
            }

            BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int alpha = 96 + ((x * 31 + y * 17) & 0x3F);
                    int red = (x * 13 + y * 7) & 0xFF;
                    int green = (x * 5 + y * 19) & 0xFF;
                    int blue = ((x ^ y) * 23) & 0xFF;
                    int argb = (alpha << 24) | (red << 16) | (green << 8) | blue;
                    image.setRGB(x, y, argb);
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            pngBytes = out.toByteArray();
        }
    }
}