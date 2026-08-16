package github.kasuminova.ssoptimizer.common.render.tessellation;

import com.fs.starfarer.combat.Bounds;
import org.apache.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.GLU;
import org.lwjgl.util.glu.GLUtessellatorCallbackAdapter;
import org.lwjgl.util.glu.tessellation.GLUtessellatorImpl;

import java.util.List;
import java.util.WeakHashMap;

/**
 * 舰船形状蒙版三角网格缓存与 GL 提交。
 * <p>
 * 动机：{@code Tesselator.renderTriangles}（唯一调用方 {@code Ship.clipToBounds}，stencil 蒙版）
 * 每帧每艘船执行一次 GLU 剖分；船体 {@code Bounds.origSegments} 内容静态
 * （{@code splitShip} 会整体替换 Bounds 对象），三角化结果可按 Bounds 身份缓存。
 * <p>
 * 缓存策略：{@link WeakHashMap} 以 Bounds 为键（游戏不覆盖 equals/hashCode，即身份语义，
 * 且船体销毁后条目随 GC 自动回收）；值内附带内容指纹双保险——每帧对
 * {@code origSegments} 做纳秒级位哈希校验，原地改坐标导致指纹失配时重算。
 * <p>
 * 耳切失败（自交/数值退化）时按 Bounds 限频输出 WARN 日志并降级到原版 GLU 剖分路径
 * （{@link #renderWithGlu}，逐行复刻原版 {@code renderTriangles} 行为，
 * 顶点数据用 {@code double[]} 替代包私有的 {@code TessellationVertex}）。
 * <p>
 * 颜色语义：原版回调首顶点用环境色、其余用参数色；所有调用方均传 (1,1,1) 且环境色为白，
 * 故快路径统一一次 {@code glColor3f(r,g,b)} 后批量 {@code glVertex2f}。
 */
public final class ShipMaskMeshCache {

    /** 单例：Mixin 入口使用的全局缓存。 */
    private static final ShipMaskMeshCache INSTANCE = new ShipMaskMeshCache(new EarClippingTriangulator());
    private static final Logger LOGGER = Logger.getLogger(ShipMaskMeshCache.class);
    /** 同一 Bounds 耳切失败 WARN 的最小间隔（纳秒），避免逐帧刷屏。 */
    private static final long WARN_INTERVAL_NS = 10_000_000_000L;

    private final Triangulator triangulator;
    /** 身份键缓存；值携带内容指纹与三角化结果（triangles 为 null 表示该指纹下耳切失败）。 */
    private final WeakHashMap<Bounds, CachedMesh> cache = new WeakHashMap<>();
    /** 耳切失败 WARN 的按 Bounds 限频时间戳。 */
    private final WeakHashMap<Bounds, Long> warnTimestamps = new WeakHashMap<>();

    public ShipMaskMeshCache(Triangulator triangulator) {
        this.triangulator = triangulator;
    }

    /**
     * 渲染入口：取（或算）三角形 soup 后以单次 glBegin(GL_TRIANGLES) 批量提交；
     * 耳切失败时限频 WARN 并降级 GLU 路径。
     */
    public static void render(Bounds bounds, float r, float g, float b) {
        INSTANCE.renderInternal(bounds, r, g, b);
    }

    private void renderInternal(Bounds bounds, float r, float g, float b) {
        List<Bounds.BoundsSegment> segments = bounds.origSegments;
        float[] xy = extractVertices(segments);
        float[] triangles = getOrTriangulate(bounds, segments, xy);
        if (triangles == null) {
            warnThrottled(bounds, segments.size());
            renderWithGlu(xy, r, g, b);
            return;
        }
        if (triangles.length == 0) {
            return;
        }
        GL11.glColor3f(r, g, b);
        GL11.glBegin(GL11.GL_TRIANGLES);
        for (int i = 0; i < triangles.length; i += 2) {
            GL11.glVertex2f(triangles[i], triangles[i + 1]);
        }
        GL11.glEnd();
    }

    /**
     * 取缓存的三角形 soup；指纹失配或首次见到时重新三角化并更新缓存。
     *
     * @return 三角形 soup；耳切失败返回 null（结果同样入缓存，同指纹下不重复尝试）
     */
    float[] getOrTriangulate(Bounds bounds, List<Bounds.BoundsSegment> segments, float[] xy) {
        long fingerprint = computeFingerprint(segments);
        synchronized (cache) {
            CachedMesh cached = cache.get(bounds);
            if (cached != null && cached.fingerprint == fingerprint) {
                return cached.triangles;
            }
        }

        float[] triangles = triangulator.triangulate(xy, xy.length >>> 1);
        synchronized (cache) {
            cache.put(bounds, new CachedMesh(fingerprint, triangles));
        }
        return triangles;
    }

    /**
     * 顶点抽取：与游戏 {@code Tesselator.getVertices} 等价——
     * 首段 p1 + 逐段 p2，闭合轮廓输出首点=末点。
     *
     * @return x,y 交错顶点数组；空轮廓返回长度 0 数组
     */
    static float[] extractVertices(List<Bounds.BoundsSegment> segments) {
        if (segments.isEmpty()) {
            return new float[0];
        }
        float[] xy = new float[(segments.size() + 1) * 2];
        Bounds.BoundsSegment first = segments.get(0);
        xy[0] = first.p1.x;
        xy[1] = first.p1.y;
        for (int i = 0; i < segments.size(); i++) {
            Bounds.BoundsSegment seg = segments.get(i);
            xy[(i + 1) * 2] = seg.p2.x;
            xy[(i + 1) * 2 + 1] = seg.p2.y;
        }
        return xy;
    }

    /**
     * 内容指纹：段数 + 逐段 p1.x/p1.y/p2.x/p2.y 的位哈希。
     * 每帧调用，成本为段数级数次位运算（纳秒级）。
     */
    static long computeFingerprint(List<Bounds.BoundsSegment> segments) {
        long hash = segments.size();
        for (Bounds.BoundsSegment seg : segments) {
            hash = hash * 31 + Float.floatToRawIntBits(seg.p1.x);
            hash = hash * 31 + Float.floatToRawIntBits(seg.p1.y);
            hash = hash * 31 + Float.floatToRawIntBits(seg.p2.x);
            hash = hash * 31 + Float.floatToRawIntBits(seg.p2.y);
        }
        return hash;
    }

    /** 按 Bounds 限频输出耳切失败 WARN。 */
    private void warnThrottled(Bounds bounds, int segmentCount) {
        long now = System.nanoTime();
        synchronized (warnTimestamps) {
            Long last = warnTimestamps.get(bounds);
            if (last != null && now - last < WARN_INTERVAL_NS) {
                return;
            }
            warnTimestamps.put(bounds, now);
        }
        LOGGER.warn(String.format(
                "SSOptimizer: 船体蒙版耳切三角化失败（段数 %d），降级 GLU 剖分路径；同一船体 %d 秒内不重复告警",
                segmentCount, WARN_INTERVAL_NS / 1_000_000_000L));
    }

    /**
     * GLU 降级路径：复刻原版 {@code Tesselator.renderTriangles} 的完整调用序列
     * （gluNewTess + 4 回调 + ODD 环绕规则 + 逐顶点提交 + 删除）。
     * 供 Mixin 在优化开关关闭时直接调用，以及耳切失败时降级使用。
     */
    public static void renderWithGlu(Bounds bounds, float r, float g, float b) {
        renderWithGlu(extractVertices(bounds.origSegments), r, g, b);
    }

    private static void renderWithGlu(float[] xy, float r, float g, float b) {
        if (xy.length == 0) {
            return;
        }
        GLUtessellatorImpl tess = (GLUtessellatorImpl) GLU.gluNewTess();
        GluMaskCallback callback = new GluMaskCallback();
        tess.gluTessCallback(GLU.GLU_TESS_VERTEX, callback);
        tess.gluTessCallback(GLU.GLU_TESS_BEGIN, callback);
        tess.gluTessCallback(GLU.GLU_TESS_END, callback);
        tess.gluTessCallback(GLU.GLU_TESS_COMBINE, callback);
        tess.gluTessProperty(GLU.GLU_TESS_WINDING_RULE, GLU.GLU_TESS_WINDING_ODD);
        tess.gluTessBeginPolygon(null);
        tess.gluTessBeginContour();

        int vertexCount = xy.length >>> 1;
        double[][] data = new double[vertexCount][6];
        for (int i = 0; i < vertexCount; i++) {
            data[i][0] = xy[i * 2];
            data[i][1] = xy[i * 2 + 1];
            data[i][2] = 0.0;
            data[i][3] = r;
            data[i][4] = g;
            data[i][5] = b;
        }
        for (int i = 0; i < vertexCount; i++) {
            tess.gluTessVertex(data[i], 0, data[i]);
        }

        tess.gluTessEndContour();
        tess.gluTessEndPolygon();
        tess.gluDeleteTess();
    }

    /**
     * GLU 剖分回调：行为与游戏 {@code TessellationCallback} 逐行一致
     * （顶点数据以 {@code double[]} 承载，替代游戏中包私有的 {@code TessellationVertex}）。
     */
    private static final class GluMaskCallback extends GLUtessellatorCallbackAdapter {
        @Override
        public void begin(int type) {
            GL11.glBegin(type);
        }

        @Override
        public void end() {
            GL11.glEnd();
        }

        @Override
        public void vertex(Object vertexData) {
            double[] coords = (double[]) vertexData;
            GL11.glVertex3d(coords[0], coords[1], coords[2]);
            GL11.glColor3d(coords[3], coords[4], coords[5]);
        }

        @Override
        public void combine(double[] coords, Object[] data, float[] weight, Object[] outData) {
            for (int i = 0; i < outData.length; i++) {
                outData[i] = new double[]{coords[0], coords[1], coords[2], 1.0, 1.0, 1.0};
            }
        }
    }

    /** 缓存条目：内容指纹 + 三角化结果（null 表示该指纹下耳切失败，直接走降级）。 */
    private static final class CachedMesh {
        final long fingerprint;
        final float[] triangles;

        CachedMesh(long fingerprint, float[] triangles) {
            this.fingerprint = fingerprint;
            this.triangles = triangles;
        }
    }
}
