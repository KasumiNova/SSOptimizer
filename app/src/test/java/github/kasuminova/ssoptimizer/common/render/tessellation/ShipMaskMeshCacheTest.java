package github.kasuminova.ssoptimizer.common.render.tessellation;

import com.fs.starfarer.combat.Bounds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link ShipMaskMeshCache} 的缓存逻辑测试（不触碰 GL）：
 * 命中不重复三角化（注入计数 Triangulator）、原地改坐标指纹失配重算、不同 Bounds 互不串扰。
 */
class ShipMaskMeshCacheTest {

    /** 计数 Triangulator：委托真实耳切实现，仅记录调用次数。 */
    private static final class CountingTriangulator implements Triangulator {
        private final Triangulator delegate = new EarClippingTriangulator();
        int calls;

        @Override
        public float[] triangulate(float[] xy, int vertexCount) {
            calls++;
            return delegate.triangulate(xy, vertexCount);
        }
    }

    /** 构造 50×20 矩形轮廓的 Bounds（4 段闭合）。 */
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

        float[] xy = ShipMaskMeshCache.extractVertices(bounds.origSegments);
        float[] first = cache.getOrTriangulate(bounds, bounds.origSegments, xy);
        float[] second = cache.getOrTriangulate(bounds, bounds.origSegments, xy);

        assertEquals(1, counting.calls, "缓存命中不应重复三角化");
        assertSame(first, second, "缓存命中应返回同一数组实例");
        assertNotNull(first);
        // 矩形 → 2 个三角形 × 6 个浮点数
        assertEquals(12, first.length);
    }

    @Test
    void inPlaceSegmentMutationInvalidatesCache() {
        CountingTriangulator counting = new CountingTriangulator();
        ShipMaskMeshCache cache = new ShipMaskMeshCache(counting);
        Bounds bounds = rectBounds();

        float[] first = cache.getOrTriangulate(bounds, bounds.origSegments,
                ShipMaskMeshCache.extractVertices(bounds.origSegments));
        assertEquals(1, counting.calls);

        // 原地改首段坐标（set 同步更新 p1/p2），指纹应失配并触发重算
        bounds.origSegments.get(0).set(0, 0, 60, 0);
        float[] second = cache.getOrTriangulate(bounds, bounds.origSegments,
                ShipMaskMeshCache.extractVertices(bounds.origSegments));

        assertEquals(2, counting.calls, "原地改坐标后指纹失配应重新三角化");
        assertNotNull(second);
        // 旧轮廓为 50×20 矩形（面积 1000）；改后轮廓 (0,0)→(60,0)→(50,20)→(0,20) 为梯形（面积 1100）
        assertEquals(1000.0, soupArea(first), 1e-3);
        assertEquals(1100.0, soupArea(second), 1e-3);
    }

    @Test
    void separateBoundsDoNotShareCacheEntries() {
        CountingTriangulator counting = new CountingTriangulator();
        ShipMaskMeshCache cache = new ShipMaskMeshCache(counting);
        // 几何完全相同的两个独立 Bounds 实例
        Bounds a = rectBounds();
        Bounds b = rectBounds();

        float[] meshA = cache.getOrTriangulate(a, a.origSegments, ShipMaskMeshCache.extractVertices(a.origSegments));
        float[] meshB = cache.getOrTriangulate(b, b.origSegments, ShipMaskMeshCache.extractVertices(b.origSegments));
        assertEquals(2, counting.calls, "不同 Bounds 实例必须各自三角化");

        // 修改 a 不影响 b 的缓存命中
        a.origSegments.get(0).set(0, 0, 60, 0);
        cache.getOrTriangulate(a, a.origSegments, ShipMaskMeshCache.extractVertices(a.origSegments));
        assertEquals(3, counting.calls);
        float[] meshBAgain = cache.getOrTriangulate(b, b.origSegments,
                ShipMaskMeshCache.extractVertices(b.origSegments));
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

    /** 三角形 soup 面积和。 */
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
