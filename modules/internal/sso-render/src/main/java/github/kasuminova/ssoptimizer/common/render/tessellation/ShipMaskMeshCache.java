package github.kasuminova.ssoptimizer.common.render.tessellation;

import com.fs.starfarer.combat.Bounds;
import github.kasuminova.ssoptimizer.common.render.spritebatch.SpriteBatch;
import org.apache.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.GLU;
import org.lwjgl.util.glu.GLUtessellatorCallbackAdapter;
import org.lwjgl.util.glu.tessellation.GLUtessellatorImpl;

import java.util.List;
import java.util.WeakHashMap;

public final class ShipMaskMeshCache {

    private static final ShipMaskMeshCache INSTANCE = new ShipMaskMeshCache(new EarClippingTriangulator());
    private static final Logger LOGGER = Logger.getLogger(ShipMaskMeshCache.class);
    private static final long WARN_INTERVAL_NS = 10_000_000_000L;

    private final Triangulator triangulator;
    private final WeakHashMap<Bounds, CachedMesh> cache = new WeakHashMap<>();
    private final WeakHashMap<Bounds, Long> warnTimestamps = new WeakHashMap<>();

    public ShipMaskMeshCache(Triangulator triangulator) {
        this.triangulator = triangulator;
    }

    public static void render(Bounds bounds, float r, float g, float b) {
        SpriteBatch.getInstance().flushPending();
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

    private static final class CachedMesh {
        final long fingerprint;
        final float[] triangles;

        CachedMesh(long fingerprint, float[] triangles) {
            this.fingerprint = fingerprint;
            this.triangles = triangles;
        }
    }
}
