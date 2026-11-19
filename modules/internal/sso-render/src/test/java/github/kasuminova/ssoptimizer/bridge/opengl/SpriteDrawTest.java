package github.kasuminova.ssoptimizer.bridge.opengl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SpriteDrawTest {
    @Test
    void fullSpriteUsesMidpointWhenEitherPivotCoordinateIsUnset() {
        SpriteDraw sprite = new SpriteDraw(0, false, false, 0, 0, 0,
                10f, 20f, 8f, 6f, 17f, -1f, 180f,
                0.25f, 0.125f, 0.5f, 0.75f, null);

        assertArrayEquals(new float[]{18f, 26f, 18f, 20f, 10f, 20f, 10f, 26f, 0.25f, 0.125f, 0.75f, 0.875f},
                quad(sprite), 0.00001f);
    }

    @Test
    void croppedSpriteUsesItsMidpointWhenTheCroppedPivotReachesTheSentinel() {
        SpriteDraw sprite = new SpriteDraw(0, false, false, 0, 0, 0,
                3f, 5f, 8f, 6f, 1f, 2.25f, 90f, 0.25f, 0.125f, 0.5f, 0.75f,
                new SpriteDraw.Region(0.25f, 0.5f, 0.25f, 0.25f, 0.01f, 0.02f, true));

        assertArrayEquals(new float[]{7.75f, 7f, 6.25f, 7f, 6.25f, 9f, 7.75f, 9f, 0.385f, 0.52f, 0.49f, 0.6675f},
                quad(sprite), 0.00001f);
    }

    @Test
    void croppedUvReconstructsTheFarEdgeFromTheSignedTexWidth() {
        SpriteDraw sprite = new SpriteDraw(0, false, false, 0, 0, 0,
                0f, 0f, 8f, 6f, -1f, -1f, 0f, 0.1f, 0f, 1f, 1f,
                new SpriteDraw.Region(0.75f, 0f, -0.75f, 1f, 0f, 0f, true));

        float[] quad = quad(sprite);
        assertEquals(0.85f, quad[8]);
        assertEquals(0.85f - 0.75f, quad[10]);
    }

    private static float[] quad(SpriteDraw sprite) {
        float[] quad = new float[12];
        sprite.emit((x0, y0, x1, y1, x2, y2, x3, y3, u, v, texWidth, texHeight) -> {
            quad[0] = x0;
            quad[1] = y0;
            quad[2] = x1;
            quad[3] = y1;
            quad[4] = x2;
            quad[5] = y2;
            quad[6] = x3;
            quad[7] = y3;
            quad[8] = u;
            quad[9] = v;
            quad[10] = u + texWidth;
            quad[11] = v + texHeight;
        });
        return quad;
    }
}
