package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.font.layout.GlyphQuad;
import github.kasuminova.ssoptimizer.common.font.layout.TextPass;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_QUADS;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;

class TextStreamEmitterAtlasTest {

    private static GlyphQuad quad(final float x, final int argb, final int textureId) {
        return new GlyphQuad(
                x, 0f, 0f, 1f,
                x, 10f, 0f, 0f,
                x + 8f, 10f, 1f, 0f,
                x + 8f, 0f, 1f, 1f,
                argb, textureId);
    }

    private static List<String> replay(final List<TextPass> passes) {
        final var sink = new TextStreamEmitterTest.RecordingSink();
        FontTextDraw.capture(passes, TextPass::quads, true, GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA).replay(sink);
        return sink.calls;
    }

    @Test
    void groupsAdjacentQuadsByTextureWithinEachNonemptyPass() {
        final List<TextPass> passes = List.of(
                new TextPass(List.of(quad(0f, 0xFFFFFFFF, 5), quad(10f, 0xFFFFFFFF, 5),
                        quad(20f, 0xFFFFFFFF, 7), quad(30f, 0xFFFFFFFF, 5))),
                new TextPass(List.of()),
                new TextPass(List.of(quad(40f, 0xFFFFFFFF, 5))));

        final List<String> structure = replay(passes).stream()
                .filter(call -> call.startsWith("bind:") || call.startsWith("begin:") || call.equals("end"))
                .toList();
        final String begin = "begin:" + GL_QUADS;
        assertEquals(List.of("bind:5", begin, "end", "bind:7", begin, "end", "bind:5", begin, "end",
                "bind:5", begin, "end"), structure);
    }

    @Test
    void keepsColorUntilItChangesAcrossTextureSegments() {
        final List<String> calls = replay(List.of(new TextPass(List.of(
                quad(0f, 0xFFFF0000, 5), quad(10f, 0xFFFF0000, 7), quad(20f, 0xFFFFFFFF, 9)))));

        assertEquals(List.of("c:-1,0,0,-1", "c:-1,-1,-1,-1"),
                calls.stream().filter(call -> call.startsWith("c:")).toList());
        assertTrue(calls.lastIndexOf("c:-1,-1,-1,-1") > calls.lastIndexOf("begin:" + GL_QUADS));
    }
}
