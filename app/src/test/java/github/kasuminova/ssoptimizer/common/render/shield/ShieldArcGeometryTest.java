package github.kasuminova.ssoptimizer.common.render.shield;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ShieldArcGeometry} 的逻辑验证：旋转递推与 raycast 对照实现分别与双精度参考式逐点对比，
 * 边缘 alpha 与原版公式逐位相等，缓存命中/失效行为，以及两实现的耗时基准对比。
 */
class ShieldArcGeometryTest {
    private static final int[]   SEGMENT_COUNTS = {2, 25, 37, 73, 100};
    private static final float[] RADII          = {32.0F, 128.0F, 260.0F};
    private static final float[] ARCS_DEG       = {45.0F, 120.0F, 360.0F, 370.0F};

    /** (arcDeg, segmentCount, radius) 笛卡尔积参数。 */
    static List<float[]> fanCases() {
        List<float[]> cases = new ArrayList<>();
        for (float arc : ARCS_DEG) {
            for (int segments : SEGMENT_COUNTS) {
                for (float radius : RADII) {
                    cases.add(new float[]{arc, segments, radius});
                }
            }
        }
        return cases;
    }

    /** (arcDeg, segmentCount, phase) 参数，相位含负值。 */
    static List<float[]> texCoordCases() {
        List<float[]> cases = new ArrayList<>();
        float[] phases = {-2.4F, -0.3F, 0.0F, 1.7F};
        for (float arc : ARCS_DEG) {
            for (int segments : SEGMENT_COUNTS) {
                for (float phase : phases) {
                    cases.add(new float[]{arc, segments, phase});
                }
            }
        }
        return cases;
    }

    @ParameterizedTest
    @MethodSource("fanCases")
    void recurrenceFanVerticesMatchCircleReference(float[] params) {
        float arcDeg = params[0];
        int segmentCount = (int) params[1];
        float radius = params[2];
        float tolerance = 1e-4F * radius;

        float[] out = new float[segmentCount * 2];
        ShieldArcGeometry.fillFanVertices(out, arcDeg, segmentCount, radius);

        // 双精度参考式：vertex_k = radius·1.07·(cos(kΔ), sin(kΔ))
        double delta = Math.toRadians(arcDeg) / (segmentCount - 1);
        double r = radius * 1.07;
        for (int k = 0; k < segmentCount; k++) {
            double refX = r * Math.cos(k * delta);
            double refY = r * Math.sin(k * delta);
            assertEquals(refX, out[k * 2], tolerance,
                    "k=" + k + " x mismatch, arc=" + arcDeg + " n=" + segmentCount + " r=" + radius);
            assertEquals(refY, out[k * 2 + 1], tolerance,
                    "k=" + k + " y mismatch, arc=" + arcDeg + " n=" + segmentCount + " r=" + radius);
        }
    }

    @ParameterizedTest
    @MethodSource("fanCases")
    void raycastFanVerticesMatchCircleReference(float[] params) {
        float arcDeg = params[0];
        int segmentCount = (int) params[1];
        float radius = params[2];
        float tolerance = 1e-4F * radius;

        float[] out = new float[segmentCount * 2];
        ShieldArcGeometry.fillFanVerticesRaycast(out, arcDeg, segmentCount, radius);

        double delta = Math.toRadians(arcDeg) / (segmentCount - 1);
        double r = radius * 1.07;
        for (int k = 0; k < segmentCount; k++) {
            double refX = r * Math.cos(k * delta);
            double refY = r * Math.sin(k * delta);
            assertEquals(refX, out[k * 2], tolerance,
                    "k=" + k + " x mismatch, arc=" + arcDeg + " n=" + segmentCount + " r=" + radius);
            assertEquals(refY, out[k * 2 + 1], tolerance,
                    "k=" + k + " y mismatch, arc=" + arcDeg + " n=" + segmentCount + " r=" + radius);
        }
    }

    @ParameterizedTest
    @MethodSource("texCoordCases")
    void fanTexCoordsMatchReference(float[] params) {
        float arcDeg = params[0];
        int segmentCount = (int) params[1];
        float phase = params[2];

        float[] out = new float[segmentCount * 2];
        ShieldArcGeometry.fillFanTexCoords(out, arcDeg, segmentCount, phase);

        // 双精度参考式：uv_k = 0.5 + (cos(kΔ + φ), sin(kΔ + φ)) / 2
        double delta = Math.toRadians(arcDeg) / (segmentCount - 1);
        for (int k = 0; k < segmentCount; k++) {
            double refU = 0.5 + Math.cos(k * delta + phase) / 2.0;
            double refV = 0.5 + Math.sin(k * delta + phase) / 2.0;
            assertEquals(refU, out[k * 2], 1e-4,
                    "k=" + k + " u mismatch, arc=" + arcDeg + " n=" + segmentCount + " phase=" + phase);
            assertEquals(refV, out[k * 2 + 1], 1e-4,
                    "k=" + k + " v mismatch, arc=" + arcDeg + " n=" + segmentCount + " phase=" + phase);
        }
    }

    @ParameterizedTest
    @MethodSource("fanCases")
    void bandStripMatchesNoiseReference(float[] params) {
        float arcDeg = params[0];
        int segmentCount = (int) params[1];
        float radius = params[2];
        float startDeg = 137.5F;
        float bandWidth = 4.5F;
        float scaleEff = 0.8F;
        float ringAngle = 2.1F;
        float tolerance = 1e-4F * (radius + scaleEff);

        float bandArcRad = ShieldArcGeometry.bandArcRadians(startDeg, arcDeg);
        // 原版语义：(float)toRadians(h+a) − (float)toRadians(h) 后再经 normalizeAngle 的 float 取模链
        // （(f % 360 + 360) % 360 在 360 量级有 ulp≈3e-5 的精度损失，属于原版固有行为，须逐位复刻）
        float radDiff = (float) Math.toRadians(startDeg + arcDeg) - (float) Math.toRadians(startDeg);
        float expectedArc = (radDiff % 360.0F + 360.0F) % 360.0F;
        assertEquals(expectedArc, bandArcRad, 0.0F, "bandArcRad 应与原版弧度序列逐位一致");

        float[] out = new float[segmentCount * 4];
        ShieldArcGeometry.fillBandStrip(out, segmentCount, bandArcRad, radius, bandWidth, scaleEff, ringAngle);

        // 双精度参考式：rOut = radius + scaleEff·sin(ringAngle·10 + k·Δ·10)
        double delta = bandArcRad / (double) (segmentCount - 1);
        for (int k = 0; k < segmentCount; k++) {
            double refROut = radius + scaleEff * (float) Math.sin(ringAngle * 10.0F + k * (float) delta * 10.0F);
            int base = k * 4;
            double outerR = Math.hypot(out[base], out[base + 1]);
            double innerR = Math.hypot(out[base + 2], out[base + 3]);
            assertEquals(refROut, outerR, tolerance,
                    "k=" + k + " outer radius mismatch, arc=" + arcDeg + " n=" + segmentCount);
            assertEquals(refROut - bandWidth, innerR, tolerance,
                    "k=" + k + " inner radius mismatch, arc=" + arcDeg + " n=" + segmentCount);
            // 顶点方向应与角度等分参考一致
            double refX = refROut * Math.cos(k * delta);
            double refY = refROut * Math.sin(k * delta);
            assertEquals(refX, out[base], tolerance,
                    "k=" + k + " outer x mismatch, arc=" + arcDeg + " n=" + segmentCount);
            assertEquals(refY, out[base + 1], tolerance,
                    "k=" + k + " outer y mismatch, arc=" + arcDeg + " n=" + segmentCount);
        }
    }

    @Test
    void fanVertexAlphaMatchesOriginalFormulaExactly() {
        float[] damageMults = {0.0F, 0.37F, 1.0F};
        float segmentAlphaMax = 100.0F;
        float[] segmentAlphas = {0.0F, 55.0F, segmentAlphaMax};
        int colorAlpha = 200;

        for (float arcDeg : ARCS_DEG) {
            for (int segmentCount : SEGMENT_COUNTS) {
                for (float damageMult : damageMults) {
                    for (float segmentAlpha : segmentAlphas) {
                        for (int k = 0; k < segmentCount; k++) {
                            int expected = originalFanAlpha(k, segmentCount, arcDeg,
                                    damageMult, segmentAlpha, segmentAlphaMax, colorAlpha);
                            int actual = ShieldArcGeometry.fanVertexAlpha(k, segmentCount, arcDeg,
                                    damageMult, segmentAlpha, segmentAlphaMax, colorAlpha);
                            assertEquals(expected, actual,
                                    "k=" + k + " arc=" + arcDeg + " n=" + segmentCount
                                            + " dmg=" + damageMult + " segAlpha=" + segmentAlpha);
                        }
                    }
                }
            }
        }
    }

    @Test
    void bandAlphaMatchesOriginalFormulaExactly() {
        float[] damageMults = {0.0F, 0.37F, 1.0F};
        float segmentAlphaMax = 100.0F;
        float[] segmentAlphas = {0.0F, 55.0F, segmentAlphaMax};

        for (float arcDeg : ARCS_DEG) {
            for (int segmentCount : SEGMENT_COUNTS) {
                float bandArcRad = ShieldArcGeometry.bandArcRadians(137.5F, arcDeg);
                for (float damageMult : damageMults) {
                    for (float segmentAlpha : segmentAlphas) {
                        for (int k = 0; k < segmentCount; k++) {
                            int expected = originalBandAlpha(k, segmentCount, bandArcRad,
                                    damageMult, segmentAlpha, segmentAlphaMax);
                            int actual = ShieldArcGeometry.bandAlpha(k, segmentCount, bandArcRad,
                                    damageMult, segmentAlpha, segmentAlphaMax);
                            assertEquals(expected, actual,
                                    "k=" + k + " arc=" + arcDeg + " n=" + segmentCount
                                            + " dmg=" + damageMult + " segAlpha=" + segmentAlpha);
                        }
                    }
                }
            }
        }
    }

    @Test
    void fanVertexCacheHitReturnsSameContentAndArcChangeRecomputes() {
        float[] first = ShieldArcGeometry.fanVertices(360.0F, 73, 150.0F);
        float[] second = ShieldArcGeometry.fanVertices(360.0F, 73, 150.0F);
        assertSame(first, second, "缓存命中应返回同一共享数组");

        float[] changedArc = ShieldArcGeometry.fanVertices(361.0F, 73, 150.0F);
        assertNotSame(first, changedArc, "展开弧角变化应重算");
        assertFalse(Arrays.equals(first, changedArc), "展开弧角变化后内容应不同");

        float[] changedRadius = ShieldArcGeometry.fanVertices(360.0F, 73, 160.0F);
        assertNotSame(first, changedRadius, "半径变化应重算");

        float[] changedSegments = ShieldArcGeometry.fanVertices(360.0F, 74, 150.0F);
        assertNotSame(first, changedSegments, "分段数变化应重算");
        assertEquals(74 * 2, changedSegments.length);
    }

    @Test
    void benchmarkRecurrenceVsRaycast() {
        int segmentCount = 73;
        float arcDeg = 370.0F;
        float radius = 150.0F;
        int iterations = 100_000;
        float[] outA = new float[segmentCount * 2];
        float[] outB = new float[segmentCount * 2];

        // 预热 JIT
        for (int i = 0; i < 10_000; i++) {
            ShieldArcGeometry.fillFanVertices(outA, arcDeg, segmentCount, radius);
            ShieldArcGeometry.fillFanVerticesRaycast(outB, arcDeg, segmentCount, radius);
        }

        long startRec = System.nanoTime();
        float sinkRec = 0.0F;
        for (int i = 0; i < iterations; i++) {
            ShieldArcGeometry.fillFanVertices(outA, arcDeg, segmentCount, radius);
            sinkRec += outA[i % outA.length];
        }
        long recNanos = System.nanoTime() - startRec;

        long startRay = System.nanoTime();
        float sinkRay = 0.0F;
        for (int i = 0; i < iterations; i++) {
            ShieldArcGeometry.fillFanVerticesRaycast(outB, arcDeg, segmentCount, radius);
            sinkRay += outB[i % outB.length];
        }
        long rayNanos = System.nanoTime() - startRay;

        // 两实现输出逐点误差统计（仅输出，不做阈值断言）
        float maxDiff = 0.0F;
        for (int i = 0; i < outA.length; i++) {
            maxDiff = Math.max(maxDiff, Math.abs(outA[i] - outB[i]));
        }

        System.out.printf(Locale.ROOT,
                "[ShieldArcGeometry] recurrence: %.1f ns/shield, raycast: %.1f ns/shield, max vertex diff: %.8f (n=%d, r=%s, arc=%s)%n",
                recNanos / (double) iterations, rayNanos / (double) iterations, maxDiff,
                segmentCount, radius, arcDeg);

        // 防止 JIT 将循环整体消除
        assertTrue(Float.isFinite(sinkRec) && Float.isFinite(sinkRay));
    }

    /**
     * 原版 render 扇形顶点 alpha 公式的独立转写（作为对照参考，非调用被测代码）。
     */
    private static int originalFanAlpha(int k, int segmentCount, float arcDeg,
                                        float damageMult, float segmentAlpha, float segmentAlphaMax,
                                        int colorAlpha) {
        // 原版 getSegmentBrightness(int)
        float segBrightness = 1.0F - 0.45F * segmentAlpha / segmentAlphaMax;

        float var15 = 1.0F;
        var15 *= damageMult;
        var15 *= segBrightness;
        float var16 = var15;
        float var12 = (float) Math.toRadians(arcDeg) / (segmentCount - 1);
        float var17 = (float) Math.toDegrees(var12 * k);
        float var18 = 10.0F;
        if (var17 < var18 || arcDeg - var17 < var18) {
            var16 = Math.min(var17, arcDeg - var17) / var18 * var15;
        }
        return (int) (var16 * colorAlpha);
    }

    /**
     * 原版 renderBand 顶点 alpha 公式的独立转写（作为对照参考，非调用被测代码）。
     */
    private static int originalBandAlpha(int k, int segmentCount, float bandArcRad,
                                         float damageMult, float segmentAlpha, float segmentAlphaMax) {
        float segBrightness = 1.0F - 0.45F * segmentAlpha / segmentAlphaMax;

        float var12 = segmentCount - 1;
        float var13 = bandArcRad / var12;
        float var18 = damageMult;
        var18 *= segBrightness;
        float var19 = (float) Math.toRadians(10.0);
        float var20 = var13 * k;
        if (var20 < var19 || bandArcRad - var20 < var19) {
            var18 = Math.min(var20, bandArcRad - var20) / var19 * var18;
        }
        return (int) (255.0F * var18 * 1.0F);
    }
}
