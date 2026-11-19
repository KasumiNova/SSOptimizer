package github.kasuminova.ssoptimizer.common.render.tessellation;

import com.fs.starfarer.combat.Bounds;
import github.kasuminova.ssoptimizer.bridge.opengl.GlDispatch;
import github.kasuminova.ssoptimizer.bridge.opengl.ShipMaskDraw;
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
    private static final GluMaskCallback GLU_CALLBACK = new GluMaskCallback();

    private final Triangulator triangulator;
    private final WeakHashMap<Bounds, CachedMesh> cache = new WeakHashMap<>();

    public ShipMaskMeshCache(Triangulator triangulator) {
        this.triangulator = triangulator;
    }

    public static void render(Bounds bounds, float r, float g, float b) {
        SpriteBatch.getInstance().flushPending();
        CachedMesh mesh = INSTANCE.capture(bounds);
        if (!GlDispatch.canSubmitOwnedDraw()) {
            draw(mesh, r, g, b, null);
            return;
        }
        float[] triangles = mesh.triangles;
        if (triangles == null) {
            ShipMaskDraw.submit(target -> draw(mesh, r, g, b, target));
        } else if (triangles.length != 0) {
            ShipMaskDraw.submitTriangles(triangles, r, g, b);
        }
    }

    CachedMesh capture(Bounds bounds) {
        List<Bounds.BoundsSegment> segments = bounds.origSegments;
        long fingerprint = computeFingerprint(segments);
        synchronized (cache) {
            CachedMesh mesh = cache.get(bounds);
            if (mesh == null || mesh.fingerprint != fingerprint) {
                WarningWindow warnings = mesh == null ? new WarningWindow() : mesh.warnings;
                mesh = new CachedMesh(fingerprint, extractVertices(segments), warnings, triangulator);
                cache.put(bounds, mesh);
            }
            return mesh;
        }
    }

    private static void draw(CachedMesh mesh, float r, float g, float b, ShipMaskDraw target) {
        float[] triangles = mesh.resolve();
        if (triangles == null) {
            mesh.warnings.warn((mesh.contour.length >>> 1) - 1);
            renderWithGlu(mesh.contour, r, g, b, target == null ? GLU_CALLBACK : target);
        } else if (triangles.length != 0) {
            if (target != null) {
                target.triangles(triangles, r, g, b);
            } else {
                GL11.glColor3f(r, g, b);
                GL11.glBegin(GL11.GL_TRIANGLES);
                try {
                    for (int i = 0; i < triangles.length; i += 2) {
                        GL11.glVertex2f(triangles[i], triangles[i + 1]);
                    }
                } finally {
                    GL11.glEnd();
                }
            }
        }
    }

    private static float[] extractVertices(List<Bounds.BoundsSegment> segments) {
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

    public static void renderWithGlu(Bounds bounds, float r, float g, float b) {
        float[] xy = extractVertices(bounds.origSegments);
        if (xy.length == 0) {
            return;
        }
        if (GlDispatch.canSubmitOwnedDraw()) {
            SpriteBatch.getInstance().flushPending();
            ShipMaskDraw.submit(target -> renderWithGlu(xy, r, g, b, target));
        } else {
            renderWithGlu(xy, r, g, b, GLU_CALLBACK);
        }
    }

    private static void renderWithGlu(float[] xy, float r, float g, float b, GLUtessellatorCallbackAdapter callback) {
        GLUtessellatorImpl tess = (GLUtessellatorImpl) GLU.gluNewTess();
        try {
            tess.gluTessCallback(GLU.GLU_TESS_VERTEX, callback);
            tess.gluTessCallback(GLU.GLU_TESS_BEGIN, callback);
            tess.gluTessCallback(GLU.GLU_TESS_END, callback);
            tess.gluTessCallback(GLU.GLU_TESS_COMBINE, GLU_CALLBACK);
            tess.gluTessProperty(GLU.GLU_TESS_WINDING_RULE, GLU.GLU_TESS_WINDING_ODD);
            tess.gluTessBeginPolygon(null);
            tess.gluTessBeginContour();
            for (int i = 0; i < xy.length; i += 2) {
                double[] data = {xy[i], xy[i + 1], 0.0, r, g, b};
                tess.gluTessVertex(data, 0, data);
            }
            tess.gluTessEndContour();
            tess.gluTessEndPolygon();
        } finally {
            tess.gluDeleteTess();
        }
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

    static final class CachedMesh {
        private final long fingerprint;
        private final float[] contour;
        private final WarningWindow warnings;
        private final Triangulator triangulator;
        private volatile float[] triangles;
        private boolean resolved;

        private CachedMesh(long fingerprint, float[] contour, WarningWindow warnings, Triangulator triangulator) {
            this.fingerprint = fingerprint;
            this.contour = contour;
            this.warnings = warnings;
            this.triangulator = triangulator;
        }

        synchronized float[] resolve() {
            if (!resolved) {
                triangles = triangulator.triangulate(contour, contour.length >>> 1);
                resolved = true;
            }
            return triangles;
        }
    }

    private static final class WarningWindow {
        private long lastWarn = Long.MIN_VALUE;

        synchronized void warn(int segmentCount) {
            long now = System.nanoTime();
            if (lastWarn == Long.MIN_VALUE || now - lastWarn >= WARN_INTERVAL_NS) {
                lastWarn = now;
                LOGGER.warn("SSOptimizer: 船体蒙版耳切三角化失败，使用 GLU 剖分；轮廓段数 " + segmentCount);
            }
        }
    }
}
