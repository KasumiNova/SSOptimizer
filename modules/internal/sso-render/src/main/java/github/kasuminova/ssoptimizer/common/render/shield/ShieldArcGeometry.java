package github.kasuminova.ssoptimizer.common.render.shield;

import java.util.HashMap;
import java.util.Map;

public final class ShieldArcGeometry {
    public static final String ALGO_PROPERTY = "ssoptimizer.render.shield.algo";
    public static final int INITIAL_MAX_SEGMENTS = 256;

    private static final boolean USE_RAYCAST =
            "raycast".equalsIgnoreCase(System.getProperty(ALGO_PROPERTY, "recurrence"));

    private static final int FAN_CACHE_MAX_ENTRIES = 512;

    /** 返回数组由渲染线程共享读取，调用方不得修改。 */
    private static final Map<FanKey, float[]> FAN_CACHE = new HashMap<>();

    private ShieldArcGeometry() {
    }

    public static boolean useRaycast() {
        return USE_RAYCAST;
    }

    public static float[] fanVertices(final float arcDeg, final int segmentCount, final float radius) {
        FanKey key = new FanKey(Float.floatToRawIntBits(arcDeg), segmentCount, Float.floatToRawIntBits(radius));
        float[] cached = FAN_CACHE.get(key);
        if (cached != null) {
            return cached;
        }

        float[] vertices = new float[segmentCount * 2];
        if (USE_RAYCAST) {
            fillFanVerticesRaycast(vertices, arcDeg, segmentCount, radius);
        } else {
            fillFanVertices(vertices, arcDeg, segmentCount, radius);
        }

        if (FAN_CACHE.size() >= FAN_CACHE_MAX_ENTRIES) {
            FAN_CACHE.clear();
        }
        FAN_CACHE.put(key, vertices);
        return vertices;
    }

    public static void fillFanVertices(final float[] out, final float arcDeg, final int segmentCount, final float radius) {
        float delta = (float) Math.toRadians(arcDeg) / (segmentCount - 1);
        float r = radius * 1.07F;

        float cosDelta = (float) Math.cos(delta);
        float sinDelta = (float) Math.sin(delta);

        float x = r;
        float y = 0.0F;
        for (int k = 0; k < segmentCount; k++) {
            out[k * 2] = x;
            out[k * 2 + 1] = y;
            float nx = x * cosDelta - y * sinDelta;
            y = x * sinDelta + y * cosDelta;
            x = nx;
        }
    }

    public static void fillFanVerticesRaycast(final float[] out, final float arcDeg, final int segmentCount, final float radius) {
        float delta = (float) Math.toRadians(arcDeg) / (segmentCount - 1);
        float r = radius * 1.07F;

        for (int k = 0; k < segmentCount; k++) {
            float theta = k * delta;
            float c = (float) Math.cos(theta);
            float s = (float) Math.sin(theta);
            float t = r / Math.max(Math.abs(c), Math.abs(s));
            float px = c * t;
            float py = s * t;
            float invLen = r / (float) Math.sqrt(px * px + py * py);
            out[k * 2] = px * invLen;
            out[k * 2 + 1] = py * invLen;
        }
    }

    public static void fillFanTexCoords(final float[] out, final float arcDeg, final int segmentCount, final float phase) {
        float delta = (float) Math.toRadians(arcDeg) / (segmentCount - 1);

        float cosDelta = (float) Math.cos(delta);
        float sinDelta = (float) Math.sin(delta);

        float c = (float) Math.cos(phase);
        float s = (float) Math.sin(phase);
        for (int k = 0; k < segmentCount; k++) {
            out[k * 2] = 0.5F + c / 2.0F;
            out[k * 2 + 1] = 0.5F + s / 2.0F;
            float nc = c * cosDelta - s * sinDelta;
            s = c * sinDelta + s * cosDelta;
            c = nc;
        }
    }

    public static float bandArcRadians(final float startDeg, final float arcDeg) {
        float startRad = (float) Math.toRadians(startDeg);
        float endRad = (float) Math.toRadians(startDeg + arcDeg);
        return normalizeAngleDeg(endRad - startRad);
    }

    public static void fillBandStrip(final float[] out, final int segmentCount, final float bandArcRad,
                                     final float radius, final float bandWidth, final float scaleEff,
                                     final float ringAngle) {
        float delta = bandArcRad / (segmentCount - 1);

        float cosDelta = (float) Math.cos(delta);
        float sinDelta = (float) Math.sin(delta);
        float noiseDelta = delta * 10.0F;
        float cosNoise = (float) Math.cos(noiseDelta);
        float sinNoise = (float) Math.sin(noiseDelta);

        float dirX = 1.0F;
        float dirY = 0.0F;
        float noiseC = (float) Math.cos(ringAngle * 10.0F);
        float noiseS = (float) Math.sin(ringAngle * 10.0F);

        for (int k = 0; k < segmentCount; k++) {
            float rOut = radius + scaleEff * noiseS;
            float rIn = rOut - bandWidth;
            int base = k * 4;
            out[base] = dirX * rOut;
            out[base + 1] = dirY * rOut;
            out[base + 2] = dirX * rIn;
            out[base + 3] = dirY * rIn;

            float nx = dirX * cosDelta - dirY * sinDelta;
            dirY = dirX * sinDelta + dirY * cosDelta;
            dirX = nx;

            float nnc = noiseC * cosNoise - noiseS * sinNoise;
            noiseS = noiseC * sinNoise + noiseS * cosNoise;
            noiseC = nnc;
        }
    }

    public static float segmentBrightness(final float segmentAlpha, final float segmentAlphaMax) {
        return 1.0F - 0.45F * segmentAlpha / segmentAlphaMax;
    }

    public static int fanVertexAlpha(final int k, final int segmentCount, final float arcDeg,
                                     final float damageMult, final float segmentAlpha, final float segmentAlphaMax,
                                     final int colorAlpha) {
        float brightness = 1.0F;
        brightness *= damageMult;
        brightness *= segmentBrightness(segmentAlpha, segmentAlphaMax);
        float delta = (float) Math.toRadians(arcDeg) / (segmentCount - 1);
        float deg = (float) Math.toDegrees(delta * k);
        float edge = 10.0F;
        if (deg < edge || arcDeg - deg < edge) {
            brightness = Math.min(deg, arcDeg - deg) / edge * brightness;
        }
        return (int) (brightness * colorAlpha);
    }

    public static int bandAlpha(final int k, final int segmentCount, final float bandArcRad,
                                final float damageMult, final float segmentAlpha, final float segmentAlphaMax) {
        float delta = bandArcRad / (segmentCount - 1);
        float brightness = damageMult;
        brightness *= segmentBrightness(segmentAlpha, segmentAlphaMax);
        float edge = (float) Math.toRadians(10.0);
        float pos = delta * k;
        if (pos < edge || bandArcRad - pos < edge) {
            brightness = Math.min(pos, bandArcRad - pos) / edge * brightness;
        }
        return (int) (255.0F * brightness * 1.0F);
    }

    private static float normalizeAngleDeg(final float f) {
        return (f % 360.0F + 360.0F) % 360.0F;
    }

    private static final class FanKey {
        private final int arcBits;
        private final int segmentCount;
        private final int radiusBits;

        private FanKey(final int arcBits, final int segmentCount, final int radiusBits) {
            this.arcBits = arcBits;
            this.segmentCount = segmentCount;
            this.radiusBits = radiusBits;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof FanKey other)) {
                return false;
            }
            return arcBits == other.arcBits && segmentCount == other.segmentCount && radiusBits == other.radiusBits;
        }

        @Override
        public int hashCode() {
            int result = arcBits;
            result = 31 * result + segmentCount;
            result = 31 * result + radiusBits;
            return result;
        }
    }
}
