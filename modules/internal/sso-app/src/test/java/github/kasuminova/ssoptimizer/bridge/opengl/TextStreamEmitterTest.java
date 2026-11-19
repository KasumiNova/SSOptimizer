package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.font.layout.GlyphQuad;
import github.kasuminova.ssoptimizer.common.font.layout.TextPass;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_QUADS;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;

class TextStreamEmitterTest {

    static final class RecordingSink implements VertexSink {
        final List<String> calls = new ArrayList<>();

        @Override
        public void begin(int mode) {
            calls.add("begin:" + mode);
        }

        @Override
        public void end() {
            calls.add("end");
        }

        @Override
        public void vertex2f(float x, float y) {
            calls.add("v:" + x + "," + y);
        }

        @Override
        public void vertex3f(float x, float y, float z) {
            calls.add("v3:" + x + "," + y + "," + z);
        }

        @Override
        public void vertex2d(double x, double y) {
            calls.add("vd:" + x + "," + y);
        }

        @Override
        public void vertex3d(double x, double y, double z) {
            calls.add("vd3:" + x + "," + y + "," + z);
        }

        @Override
        public void texCoord2f(float s, float t) {
            calls.add("t:" + s + "," + t);
        }

        @Override
        public void texCoord2d(double s, double t) {
            calls.add("td:" + s + "," + t);
        }

        @Override
        public void color4ub(byte red, byte green, byte blue, byte alpha) {
            calls.add("c:" + red + "," + green + "," + blue + "," + alpha);
        }

        @Override
        public void color3ub(byte red, byte green, byte blue) {
            calls.add("c3:" + red + "," + green + "," + blue);
        }

        @Override
        public void color3f(float red, float green, float blue) {
            calls.add("c3f");
        }

        @Override
        public void color4f(float red, float green, float blue, float alpha) {
            calls.add("c4f");
        }

        @Override
        public void color3d(double red, double green, double blue) {
            calls.add("c3d");
        }

        @Override
        public void normal3f(float nx, float ny, float nz) {
            calls.add("n");
        }

        @Override
        public void enable(int cap) {
            calls.add("enable:" + cap);
        }

        @Override
        public void disable(int cap) {
            calls.add("disable:" + cap);
        }

        @Override
        public void blendFunc(int src, int dst) {
            calls.add("blend:" + src + "," + dst);
        }

        @Override
        public void bindTexture(int texture) {
            calls.add("bind:" + texture);
        }
    }

    private static GlyphQuad quad(float x, int argb) {
        return new GlyphQuad(
                x, 0f, 0f, 1f,
                x, 10f, 0f, 0f,
                x + 8f, 10f, 1f, 0f,
                x + 8f, 0f, 1f, 1f,
                argb,
                0);
    }

    private static List<String> replay(final FontTextDraw draw) {
        final RecordingSink sink = new RecordingSink();
        draw.replay(sink);
        return sink.calls;
    }

    @Test
    void decodesBitmapPassesWithIndependentPositionAndUvPairs() {
        final GlyphQuad tilted = new GlyphQuad(
                4f, 1f, 0.12f, 0.83f,
                3f, 12f, 0.14f, -0.0f,
                17f, 13f, 0.98f, 0.17f,
                16f, 0f, 0.91f, 0.86f,
                0x80EF1203, 27);
        final List<TextPass> passes = List.of(new TextPass(List.of(tilted)),
                new TextPass(List.of(quad(20f, 0x80EF1203))));
        final List<String> calls = replay(FontTextDraw.capture(passes, TextPass::quads, false, GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA));
        final List<String> expected = List.of(
                "enable:" + GL_BLEND, "blend:" + GL_SRC_ALPHA + "," + GL_ONE_MINUS_SRC_ALPHA,
                "begin:" + GL_QUADS, "c:-17,18,3,-128",
                "t:0.12,0.83", "v:4.0,1.0", "t:0.14,-0.0", "v:3.0,12.0",
                "t:0.98,0.17", "v:17.0,13.0", "t:0.91,0.86", "v:16.0,0.0", "end",
                "begin:" + GL_QUADS,
                "t:0.0,1.0", "v:20.0,0.0", "t:0.0,0.0", "v:20.0,10.0",
                "t:1.0,0.0", "v:28.0,10.0", "t:1.0,1.0", "v:28.0,0.0", "end",
                "disable:" + GL_BLEND);
        assertEquals(expected, calls);
    }

    @Test
    void snapshotsPassAndGlyphListsBeforeReuse() {
        final List<GlyphQuad> glyphs = new ArrayList<>(List.of(quad(0f, 0xFF00FF00), quad(10f, 0xFF0000FF)));
        final List<TextPass> passes = new ArrayList<>(List.of(new TextPass(glyphs)));
        final FontTextDraw draw = FontTextDraw.capture(passes, TextPass::quads, false, GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        glyphs.set(0, quad(100f, 0xFFFFFFFF));
        glyphs.removeLast();
        passes.clear();

        final List<String> calls = replay(draw);
        assertEquals(List.of("v:0.0,0.0", "v:0.0,10.0", "v:8.0,10.0", "v:8.0,0.0",
                        "v:10.0,0.0", "v:10.0,10.0", "v:18.0,10.0", "v:18.0,0.0"),
                calls.stream().filter(call -> call.startsWith("v:")).toList());
        assertEquals(List.of("c:0,-1,0,-1", "c:0,0,-1,-1"),
                calls.stream().filter(call -> call.startsWith("c:")).toList());
    }
}
