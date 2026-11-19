package github.kasuminova.ssoptimizer.common.render.engine;

import github.kasuminova.ssoptimizer.common.render.engine.ParticleParameters.Kind;
import github.kasuminova.ssoptimizer.common.render.engine.ParticleParameters.Vertices;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ParticleBatchHelperTest {
    @Test
    void brightnessRoundsAndClampsAllChannels() {
        Color color = new Color(3, 126, 200, 255);
        assertEquals(new Color(2, 63, 100, 128), ParticleBatchHelper.adjustBrightness(color, .5f));
        assertEquals(new Color(6, 252, 255, 255), ParticleBatchHelper.adjustBrightness(color, 2f));
        assertEquals(0, ParticleBatchHelper.brightnessRgba(null, 1f));
        assertEquals(0, ParticleBatchHelper.brightnessRgba(color, Float.NaN));
        assertEquals(0, ParticleBatchHelper.brightnessRgba(color, Float.POSITIVE_INFINITY));
        assertEquals(0, ParticleBatchHelper.brightnessRgba(color, -1f));
    }

    @Test
    void axisAlignedExpansionPreservesSignedSizeColorAndInsertionOrderAfterGrowth() {
        for (Kind kind : new Kind[]{Kind.SMOOTH, Kind.NEGATIVE}) {
            ParticleParameters params = kind.acquire();
            try {
                for (int i = 0; i < 43; i++) {
                    params.axis(ParticleParameters.rgba(i, 256 + i, -1, 127 - i), i, -i, -2.5f);
                }
                Vertices out = params.expand();
                assertEquals(43 * 8, out.positions().remaining());
                for (int i = 0; i < 43; i++) {
                    assertArrayEquals(new float[]{i, -i, i, -i - 2.5f, i - 2.5f, -i - 2.5f, i - 2.5f, -i},
                            quad(out.positions(), i));
                    assertArrayEquals(new float[]{0, 0, 0, 1, 1, 1, 1, 0}, quad(out.texCoords(), i));
                    for (int vertex = 0; vertex < 4; vertex++) {
                        int start = i * 16 + vertex * 4;
                        assertEquals(i, out.colors().get(start) & 255);
                        assertEquals(i, out.colors().get(start + 1) & 255);
                        assertEquals(255, out.colors().get(start + 2) & 255);
                        assertEquals(127 - i, out.colors().get(start + 3) & 255);
                    }
                }
            } finally {
                params.release();
            }
        }
    }

    @Test
    void smokeRotatesTheOffsetAndTheWholeSquareAboutTheParticleOrigin() {
        ParticleParameters params = Kind.SMOKE.acquire();
        try {
            params.smoke(0, 10, -5, 90, 2, 4, 3);
            Vertices out = params.expand();
            assertArrayEquals(new float[]{6, -3, 3, -3, 3, 0, 6, 0}, quad(out.positions(), 0), 1e-5f);
            assertArrayEquals(new float[]{0, 0, 0, 1, 1, 1, 1, 0}, quad(out.texCoords(), 0));
        } finally {
            params.release();
        }
    }

    @Test
    void genericRepeatUsesTheOriginalLocalOffsetAcrossSplitBatches() {
        ParticleParameters params = Kind.GENERIC.acquire();
        try {
            int first = ParticleBatchHelper.MAX_PARTICLES - 1;
            params.generic(0, 4, 5, 90, -first + .25f, -2, 3, 6, .8f, .6f, first, 3);
            Vertices out = params.expand();
            for (int i = 0; i < 3; i++) {
                float y = 5.25f + i;
                assertArrayEquals(new float[]{6, y, 0, y, 0, y + 3, 6, y + 3}, quad(out.positions(), i), 1e-5f);
                assertArrayEquals(new float[]{0, 0, 0, .6f, .8f, .6f, .8f, 0}, quad(out.texCoords(), i));
            }
        } finally {
            params.release();
        }
    }

    @Test
    void nebulaPreservesAtlasCornersAndRotatesNonSquareGeometry() {
        ParticleParameters params = Kind.NEBULA.acquire();
        try {
            params.nebula(0, 2, -1, -90, 1, 2, 3, 4, .25f, .5f, .75f, 1);
            Vertices out = params.expand();
            assertArrayEquals(new float[]{4, -2, 8, -2, 8, -5, 4, -5}, quad(out.positions(), 0), 1e-5f);
            assertArrayEquals(new float[]{.25f, .5f, .25f, 1, .75f, 1, .75f, .5f}, quad(out.texCoords(), 0));
        } finally {
            params.release();
        }
    }

    private static float[] quad(FloatBuffer buffer, int index) {
        float[] values = new float[8];
        buffer.get(index * 8, values);
        return values;
    }
}
