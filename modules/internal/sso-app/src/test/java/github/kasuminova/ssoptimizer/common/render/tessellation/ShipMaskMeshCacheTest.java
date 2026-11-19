package github.kasuminova.ssoptimizer.common.render.tessellation;

import com.fs.starfarer.combat.Bounds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ShipMaskMeshCacheTest {

    private static final class CountingTriangulator implements Triangulator {
        private final Triangulator delegate = new EarClippingTriangulator();
        int calls;

        @Override
        public float[] triangulate(float[] xy, int vertexCount) {
            calls++;
            return delegate.triangulate(xy, vertexCount);
        }
    }

    private static Bounds rectBounds() {
        Bounds bounds = new Bounds();
        bounds.addSegment(0, 0, 50, 0);
        bounds.addSegment(50, 0, 50, 20);
        bounds.addSegment(50, 20, 0, 20);
        bounds.addSegment(0, 20, 0, 0);
        return bounds;
    }

    @Test
    void cacheHitSkipsRetriangulation() {
        CountingTriangulator counting = new CountingTriangulator();
        ShipMaskMeshCache cache = new ShipMaskMeshCache(counting);
        Bounds bounds = rectBounds();

        ShipMaskMeshCache.CachedMesh mesh = cache.capture(bounds);
        float[] first = mesh.resolve();
        assertSame(mesh, cache.capture(bounds));
        float[] second = cache.capture(bounds).resolve();

        assertEquals(1, counting.calls, "缓存命中不应重复三角化");
        assertSame(first, second, "缓存命中应返回同一数组实例");
        assertNotNull(first);
        assertEquals(12, first.length);
    }

    @Test
    void inPlaceSegmentMutationInvalidatesCache() {
        CountingTriangulator counting = new CountingTriangulator();
        ShipMaskMeshCache cache = new ShipMaskMeshCache(counting);
        Bounds bounds = rectBounds();

        ShipMaskMeshCache.CachedMesh firstPending = cache.capture(bounds);
        bounds.origSegments.get(0).set(0, 0, 60, 0);
        ShipMaskMeshCache.CachedMesh secondPending = cache.capture(bounds);
        float[] second = secondPending.resolve();
        float[] first = firstPending.resolve();
        assertSame(secondPending, cache.capture(bounds));

        assertEquals(2, counting.calls, "原地改坐标后指纹失配应重新三角化");
        assertNotNull(second);
        assertEquals(1000.0, soupArea(first), 1e-3);
        assertEquals(1100.0, soupArea(second), 1e-3);
    }

    @Test
    void separateBoundsDoNotShareCacheEntries() {
        CountingTriangulator counting = new CountingTriangulator();
        ShipMaskMeshCache cache = new ShipMaskMeshCache(counting);
        Bounds a = rectBounds();
        Bounds b = rectBounds();

        cache.capture(a).resolve();
        float[] meshB = cache.capture(b).resolve();
        assertEquals(2, counting.calls, "不同 Bounds 实例必须各自三角化");

        a.origSegments.get(0).set(0, 0, 60, 0);
        cache.capture(a).resolve();
        assertEquals(3, counting.calls);
        float[] meshBAgain = cache.capture(b).resolve();
        assertEquals(3, counting.calls, "b 的缓存不应受 a 的原地修改影响");
        assertSame(meshB, meshBAgain);
    }

    @Test
    void fingerprintReflectsSegmentCountAndCoordinates() {
        Bounds a = rectBounds();
        Bounds b = rectBounds();
        assertEquals(ShipMaskMeshCache.computeFingerprint(a.origSegments),
                ShipMaskMeshCache.computeFingerprint(b.origSegments),
                "几何相同的轮廓指纹应一致");

        b.origSegments.get(0).set(0, 0, 60, 0);
        org.junit.jupiter.api.Assertions.assertNotEquals(
                ShipMaskMeshCache.computeFingerprint(a.origSegments),
                ShipMaskMeshCache.computeFingerprint(b.origSegments),
                "坐标变化后指纹应不同");

        b.addSegment(60, 0, 60, 20);
        org.junit.jupiter.api.Assertions.assertNotEquals(
                ShipMaskMeshCache.computeFingerprint(a.origSegments),
                ShipMaskMeshCache.computeFingerprint(b.origSegments),
                "段数变化后指纹应不同");
    }

    private static double soupArea(float[] soup) {
        double sum = 0;
        for (int i = 0; i + 5 < soup.length; i += 6) {
            double ux = soup[i + 2] - soup[i];
            double uy = soup[i + 3] - soup[i + 1];
            double vx = soup[i + 4] - soup[i];
            double vy = soup[i + 5] - soup[i + 1];
            sum += Math.abs(ux * vy - uy * vx) / 2.0;
        }
        return sum;
    }
}
