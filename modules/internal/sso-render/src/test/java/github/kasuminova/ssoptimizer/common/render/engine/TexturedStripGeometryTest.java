package github.kasuminova.ssoptimizer.common.render.engine;

import github.kasuminova.ssoptimizer.common.render.EffectMeshSamples;
import github.kasuminova.ssoptimizer.common.render.EffectMeshSamples.Vertex;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TexturedStripGeometryTest {
    @Test
    void batchPreservesPerimeterOrderAndClosesEachFan() {
        var out = new EffectMeshSamples();
        TexturedStripRenderHelper.emitBatch(out, new float[]{
                1, 2, 1, 8, 2, 4, -0.5f, 0.5f, 2,
                4, 5, 4, 5, 8, 16, 1, 0, 1}, new int[]{0x123456c8, 0xaabbccff}, 2);
        assertEquals(2, out.primitives.size());
        var first = out.primitives.getFirst();
        assertEquals(GL11.GL_TRIANGLE_FAN, first.mode());
        assertEquals(List.of(new Vertex(1, 5, 0.5f, 0.5f, 0x12345664),
                new Vertex(0, 2, 0, 0, 0x12345600), new Vertex(2, 2, 0, 1, 0x12345600),
                new Vertex(3, 8, 1, 1, 0x123456ff), new Vertex(-1, 8, 1, 0, 0x123456ff),
                new Vertex(0, 2, 0, 0, 0x12345600)), first.vertices());
        var second = out.primitives.get(1).vertices();
        assertEquals(6, second.size());
        for (int i = 0; i < second.size(); i++) {
            assertEquals(4f, second.get(i).x());
            assertEquals(5f, second.get(i).y());
            assertEquals(i == 0 ? 0xaabbcc00 : 0xaabbccff, second.get(i).rgba());
        }
    }
}
