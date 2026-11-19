package github.kasuminova.ssoptimizer.common.render.engine;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;

public final class ParticleParameters {
    enum Kind {
        SMOOTH(4), NEGATIVE(4), SMOKE(7), GENERIC(12), NEBULA(12);

        final int words;
        final ArrayBlockingQueue<ParticleParameters> idle = new ArrayBlockingQueue<>(16);

        Kind(int words) {
            this.words = words;
        }

        ParticleParameters acquire() {
            ParticleParameters batch = idle.poll();
            return batch == null ? new ParticleParameters(this) : batch;
        }
    }

    public record Vertices(ByteBuffer colors, FloatBuffer positions, FloatBuffer texCoords) {
        private Vertices(int quads) {
            this(buffer(quads * 16), buffer(quads * 32).asFloatBuffer(), buffer(quads * 32).asFloatBuffer());
        }

        public void drawNativeImmediate() {
            ParticleBatchHelper.nativeRenderImmediateQuads(colors, positions, texCoords, positions.remaining() / 2);
        }

        private static ByteBuffer buffer(int bytes) {
            return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        }
    }

    private static final ThreadLocal<Vertices> SCRATCH = new ThreadLocal<>();
    private final Kind kind;
    private int[] words;
    private int used;
    private int quads;

    private ParticleParameters(Kind kind) {
        this.kind = kind;
        words = new int[16 * kind.words];
    }

    static int rgba(int r, int g, int b, int a) {
        return r << 24 | (g & 255) << 16 | (b & 255) << 8 | (a & 255);
    }

    public void release() {
        used = 0;
        quads = 0;
        kind.idle.offer(this);
    }

    public boolean smooth() {
        return kind == Kind.SMOOTH;
    }

    int quadCount() {
        return quads;
    }

    private int append(int color, int count) {
        int start = used;
        int end = start + kind.words;
        if (end > words.length) {
            words = Arrays.copyOf(words, words.length * 2);
        }
        words[start] = color;
        used = end;
        quads += count;
        return start + 1;
    }

    void axis(int color, float x, float y, float size) {
        int i = append(color, 1);
        words[i] = Float.floatToRawIntBits(x);
        words[i + 1] = Float.floatToRawIntBits(y);
        words[i + 2] = Float.floatToRawIntBits(size);
    }

    private int rotated(int color, int count, float x, float y, float angle, float ox, float oy, float width) {
        int i = append(color, count);
        words[i] = Float.floatToRawIntBits(x);
        words[i + 1] = Float.floatToRawIntBits(y);
        words[i + 2] = Float.floatToRawIntBits(angle);
        words[i + 3] = Float.floatToRawIntBits(ox);
        words[i + 4] = Float.floatToRawIntBits(oy);
        words[i + 5] = Float.floatToRawIntBits(width);
        return i + 6;
    }

    void smoke(int color, float x, float y, float angle, float ox, float oy, float size) {
        rotated(color, 1, x, y, angle, ox, oy, size);
    }

    void generic(int color, float x, float y, float angle, float ox, float oy, float width, float height,
                 float tw, float th, int first, int count) {
        int i = rotated(color, count, x, y, angle, ox, oy, width);
        words[i] = Float.floatToRawIntBits(height);
        words[i + 1] = Float.floatToRawIntBits(tw);
        words[i + 2] = Float.floatToRawIntBits(th);
        words[i + 3] = first;
        words[i + 4] = count;
    }

    void nebula(int color, float x, float y, float angle, float ox, float oy, float width, float height,
                float u0, float v0, float u1, float v1) {
        int i = rotated(color, 1, x, y, angle, ox, oy, width);
        words[i] = Float.floatToRawIntBits(height);
        words[i + 1] = Float.floatToRawIntBits(u0);
        words[i + 2] = Float.floatToRawIntBits(v0);
        words[i + 3] = Float.floatToRawIntBits(u1);
        words[i + 4] = Float.floatToRawIntBits(v1);
    }

    public Vertices expand() {
        Vertices out = SCRATCH.get();
        if (out == null || out.colors.capacity() < quads * 16) {
            out = new Vertices(Integer.highestOneBit(Math.max(16, quads) - 1) << 1);
            SCRATCH.set(out);
        }
        out.colors.clear();
        out.positions.clear();
        out.texCoords.clear();
        for (int i = 0; i < used; i += kind.words) {
            int color = words[i];
            float x = at(i + 1), y = at(i + 2);
            if (kind == Kind.SMOOTH || kind == Kind.NEGATIVE) {
                float size = at(i + 3);
                out.positions.put(x).put(y).put(x).put(y + size).put(x + size).put(y + size).put(x + size).put(y);
                attributes(out, color, 0, 0, 1, 1);
                continue;
            }
            float radians = (float) Math.toRadians(at(i + 3));
            float cos = (float) Math.cos(radians), sin = (float) Math.sin(radians);
            float ox = at(i + 4), oy = at(i + 5), width = at(i + 6);
            switch (kind) {
                case SMOKE -> {
                    positions(out.positions, x, y, cos, sin, ox, oy, width, width);
                    attributes(out, color, 0, 0, 1, 1);
                }
                case NEBULA -> {
                    positions(out.positions, x, y, cos, sin, ox, oy, width, at(i + 7));
                    attributes(out, color, at(i + 8), at(i + 9), at(i + 10), at(i + 11));
                }
                case GENERIC -> {
                    float height = at(i + 7), tw = at(i + 8), th = at(i + 9);
                    int first = words[i + 10], end = first + words[i + 11];
                    for (int repeat = first; repeat < end; repeat++) {
                        positions(out.positions, x, y, cos, sin, ox + repeat, oy, width, height);
                        attributes(out, color, 0, 0, tw, th);
                    }
                }
                default -> throw new AssertionError(kind);
            }
        }
        out.colors.flip();
        out.positions.flip();
        out.texCoords.flip();
        return out;
    }

    private float at(int index) {
        return Float.intBitsToFloat(words[index]);
    }

    private static void positions(FloatBuffer out, float x, float y, float cos, float sin,
                                  float ox, float oy, float width, float height) {
        float right = ox + width, top = oy + height;
        out.put(x + ox * cos - oy * sin).put(y + ox * sin + oy * cos);
        out.put(x + ox * cos - top * sin).put(y + ox * sin + top * cos);
        out.put(x + right * cos - top * sin).put(y + right * sin + top * cos);
        out.put(x + right * cos - oy * sin).put(y + right * sin + oy * cos);
    }

    private static void attributes(Vertices out, int color, float u0, float v0, float u1, float v1) {
        int rgba = ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN ? color : Integer.reverseBytes(color);
        out.colors.putInt(rgba).putInt(rgba).putInt(rgba).putInt(rgba);
        out.texCoords.put(u0).put(v0).put(u0).put(v1).put(u1).put(v1).put(u1).put(v0);
    }
}
