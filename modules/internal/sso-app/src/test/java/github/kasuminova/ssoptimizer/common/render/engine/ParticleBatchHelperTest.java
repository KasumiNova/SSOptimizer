package github.kasuminova.ssoptimizer.common.render.engine;

import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParticleBatchHelperTest {

    @Test
    void adjustBrightnessScalesAllColorComponents() {
        Color adjusted = ParticleBatchHelper.adjustBrightness(new Color(100, 150, 200, 250), 0.5f);
        assertEquals(50, adjusted.getRed());
        assertEquals(75, adjusted.getGreen());
        assertEquals(100, adjusted.getBlue());
        assertEquals(125, adjusted.getAlpha());
    }

    @Test
    void adjustBrightnessNullColorFallsBackToTransparentBlack() {
        Color adjusted = ParticleBatchHelper.adjustBrightness(null, 1.0f);
        assertEquals(0, adjusted.getRed());
        assertEquals(0, adjusted.getGreen());
        assertEquals(0, adjusted.getBlue());
        assertEquals(0, adjusted.getAlpha());
    }

    @Test
    void adjustBrightnessClampsNonFiniteAndNegativeBrightnessToZero() {
        Color nanAdjusted = ParticleBatchHelper.adjustBrightness(new Color(10, 20, 30, 40), Float.NaN);
        assertEquals(0, nanAdjusted.getRed());
        assertEquals(0, nanAdjusted.getAlpha());

        Color negativeAdjusted = ParticleBatchHelper.adjustBrightness(new Color(10, 20, 30, 40), -2.0f);
        assertEquals(0, negativeAdjusted.getRed());
        assertEquals(0, negativeAdjusted.getBlue());
    }

    @Test
    void smoothBatchAccumulatesFourVerticesPerParticle() {
        ParticleBatchHelper.beginSmoothBatch();
        int baseline = ParticleBatchHelper.getNumVertices();
        ParticleBatchHelper.addSmoothParticle(255, 255, 255, 255, 10f, 10f, 0f, 0f, 8f);
        ParticleBatchHelper.addSmoothParticle(255, 255, 255, 255, 20f, 20f, 0f, 0f, 8f);
        assertEquals(baseline + 8, ParticleBatchHelper.getNumVertices());
    }

    @Test
    void smokeBatchAccumulatesFourVerticesPerParticle() {
        ParticleBatchHelper.beginSmokeBatch();
        int baseline = ParticleBatchHelper.getNumVertices();
        ParticleBatchHelper.addSmokeParticle(255, 255, 255, 255, 10f, 10f, 90f, 0f, 0f, 8f);
        ParticleBatchHelper.addSmokeParticle(255, 255, 255, 255, 10f, 10f, 90f, 1f, 1f, 8f);
        assertEquals(baseline + 8, ParticleBatchHelper.getNumVertices());
    }

    @Test
    void beginBatchResetsPendingVertices() {
        ParticleBatchHelper.beginSmokeBatch();
        int baseline = ParticleBatchHelper.getNumVertices();
        ParticleBatchHelper.addSmokeParticle(255, 255, 255, 255, 10f, 10f, 0f, 0f, 0f, 8f);
        assertEquals(baseline + 4, ParticleBatchHelper.getNumVertices());

        ParticleBatchHelper.beginSmokeBatch();
        assertEquals(baseline, ParticleBatchHelper.getNumVertices());
    }

    @Test
    void flushWithNoPendingVerticesIsNoOp() {
        ParticleBatchHelper.beginGenericTextureBatch();
        int genericBaseline = ParticleBatchHelper.getNumVertices();
        ParticleBatchHelper.flushGenericTextureBatch();
        assertEquals(genericBaseline, ParticleBatchHelper.getNumVertices());

        ParticleBatchHelper.beginSmoothBatch();
        int smoothBaseline = ParticleBatchHelper.getNumVertices();
        ParticleBatchHelper.flushSmoothBatch();
        assertEquals(smoothBaseline, ParticleBatchHelper.getNumVertices());
    }

    @Test
    void negativeBatchAccumulatesFourVerticesPerParticle() {
        ParticleBatchHelper.beginNegativeBatch();
        int baseline = ParticleBatchHelper.getNumVertices();
        ParticleBatchHelper.addNegativeParticle(255, 255, 255, 255, 10f, 10f, -4f, -4f, 8f);
        ParticleBatchHelper.addNegativeParticle(255, 255, 255, 255, 20f, 20f, -4f, -4f, 8f);
        assertEquals(baseline + 8, ParticleBatchHelper.getNumVertices());
    }

    @Test
    void beginNegativeBatchResetsPendingVertices() {
        ParticleBatchHelper.beginNegativeBatch();
        int baseline = ParticleBatchHelper.getNumVertices();
        ParticleBatchHelper.addNegativeParticle(255, 255, 255, 255, 10f, 10f, -4f, -4f, 8f);
        assertEquals(baseline + 4, ParticleBatchHelper.getNumVertices());

        ParticleBatchHelper.beginNegativeBatch();
        assertEquals(baseline, ParticleBatchHelper.getNumVertices());
    }
}
