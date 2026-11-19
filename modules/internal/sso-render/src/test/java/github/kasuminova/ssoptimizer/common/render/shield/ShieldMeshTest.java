package github.kasuminova.ssoptimizer.common.render.shield;

import github.kasuminova.ssoptimizer.common.render.EffectMeshSamples;
import github.kasuminova.ssoptimizer.common.render.EffectMeshSamples.Vertex;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShieldMeshTest {
    @Test
    void fansShareTheirPerimeterAndRotateTextureInOppositeDirections() {
        var out = new EffectMeshSamples();
        float[] alpha = {0, 0.25f, 1f, 0.5f, 0};
        new ShieldRenderHelper.Fan(120f, 32f, 0.6f, 0.75f, 1f, new Color(10, 20, 30, 180))
                .emit(out, alpha, alpha.length);
        assertEquals(2, out.primitives.size());
        for (int pass = 0; pass < 2; pass++) {
            var fan = out.primitives.get(pass);
            assertEquals(GL11.GL_TRIANGLE_FAN, fan.mode());
            assertEquals(alpha.length + 1, fan.vertices().size());
            assertEquals(new Vertex(0, 0, 0.5f, 0.5f, 0x0a141e00), fan.vertices().getFirst());
            for (int i = 0; i < alpha.length; i++) {
                var vertex = fan.vertices().get(i + 1);
                double a = Math.toRadians(30 * i);
                double phase = a - Math.toRadians(60) + (pass == 0 ? 0.6 : -0.6);
                assertEquals(32 * 1.07 * Math.cos(a), vertex.x(), 1e-5);
                assertEquals(32 * 1.07 * Math.sin(a), vertex.y(), 1e-5);
                assertEquals(0.5 + 0.5 * Math.cos(phase), vertex.u(), 1e-6);
                assertEquals(0.5 + 0.5 * Math.sin(phase), vertex.v(), 1e-6);
                int opacity = i == 0 || i == alpha.length - 1 ? 0 : (int) (0.75f * (1 - 0.45f * alpha[i]) * 180);
                assertEquals(0x0a141e00 | opacity, vertex.rgba());
            }
        }
    }

    @Test
    void bandPairsShareAngleAndSeparateByBandWidth() {
        var out = new EffectMeshSamples();
        float[] alpha = {1, 0.5f, 0};
        new ShieldRenderHelper.Band(1.2f, 12f, 2.5f, 1.5f, 0.7f, 0.5f, 1, Color.BLUE)
                .emit(out, alpha, alpha.length);
        var strip = out.primitives.getFirst();
        assertEquals(GL11.GL_QUAD_STRIP, strip.mode());
        assertEquals(2 * alpha.length, strip.vertices().size());
        for (int i = 0; i < alpha.length; i++) {
            double angle = 0.6 * i;
            double radius = 12 + 1.5 * Math.sin(7 + 10 * angle);
            var outer = strip.vertices().get(2 * i);
            var inner = strip.vertices().get(2 * i + 1);
            assertEquals(radius * Math.cos(angle), outer.x(), 1e-5);
            assertEquals(radius * Math.sin(angle), outer.y(), 1e-5);
            assertEquals((radius - 2.5) * Math.cos(angle), inner.x(), 1e-5);
            assertEquals((radius - 2.5) * Math.sin(angle), inner.y(), 1e-5);
            assertEquals(0f, outer.v());
            assertEquals(1f, inner.v());
            int opacity = i == 1 ? (int) (255f * 0.5f * (1 - 0.45f * alpha[i])) : 0;
            assertEquals(0xff00 | opacity, inner.rgba());
            assertEquals(inner.rgba(), outer.rgba());
        }
    }
}
