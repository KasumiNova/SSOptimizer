package github.kasuminova.ssoptimizer.common.render.tessellation;

public final class EarClippingTriangulator implements Triangulator {

    private static final float[] EMPTY = new float[0];

    private static final float EPSILON = 0.1F;
    private static final double COLLINEAR_SIN = 1e-3;
    private static final double INSIDE_RATIO = 1e-9;
    private static final double DEGENERATE_AREA_RATIO = 1e-12;

    @Override
    public float[] triangulate(float[] xy, int vertexCount) {
        float[] pts = preprocess(xy, vertexCount);
        int n = pts.length >>> 1;
        if (n < 3) {
            return EMPTY;
        }

        // 自交轮廓可能产生错误覆盖，必须交由 GLU 降级处理。
        if (selfIntersects(pts)) {
            return null;
        }

        double areaX2 = signedAreaX2(pts);
        if (Math.abs(areaX2) <= bboxExtentX2(pts) * DEGENERATE_AREA_RATIO) {
            return null;
        }
        if (areaX2 < 0) {
            reverse(pts);
        }

        if (isConvex(pts)) {
            return triangleFan(pts);
        }
        return earClip(pts);
    }

    private static float[] preprocess(float[] xy, int vertexCount) {
        int n = Math.min(vertexCount, xy.length >>> 1);
        float[] pts = new float[n * 2];

        int m = 0;
        for (int i = 0; i < n; i++) {
            float x = xy[i * 2];
            float y = xy[i * 2 + 1];
            if (m > 0 && near(pts[(m - 1) * 2], pts[(m - 1) * 2 + 1], x, y)) {
                continue;
            }
            pts[m * 2] = x;
            pts[m * 2 + 1] = y;
            m++;
        }
        while (m > 1 && near(pts[0], pts[1], pts[(m - 1) * 2], pts[(m - 1) * 2 + 1])) {
            m--;
        }

        boolean changed = true;
        int rounds = 0;
        while (changed && m >= 3 && rounds++ <= m) {
            changed = false;
            for (int i = 0; i < m && m >= 3; i++) {
                int prev = (i - 1 + m) % m;
                int next = (i + 1) % m;
                if (collinear(pts, prev, i, next)) {
                    System.arraycopy(pts, (i + 1) * 2, pts, i * 2, (m - i - 1) * 2);
                    m--;
                    changed = true;
                    i--;
                }
            }
        }

        if (m == n && rounds == 1 && !changed) {
            return pts;
        }
        float[] trimmed = new float[m * 2];
        System.arraycopy(pts, 0, trimmed, 0, trimmed.length);
        return trimmed;
    }

    private static boolean near(float ax, float ay, float bx, float by) {
        return Math.abs(ax - bx) < EPSILON && Math.abs(ay - by) < EPSILON;
    }

    private static boolean collinear(float[] pts, int prev, int cur, int next) {
        double ux = pts[cur * 2] - pts[prev * 2];
        double uy = pts[cur * 2 + 1] - pts[prev * 2 + 1];
        double vx = pts[next * 2] - pts[cur * 2];
        double vy = pts[next * 2 + 1] - pts[cur * 2 + 1];
        double lenProduct = Math.hypot(ux, uy) * Math.hypot(vx, vy);
        if (lenProduct == 0) {
            return true;
        }
        double cross = ux * vy - uy * vx;
        double dot = ux * vx + uy * vy;
        return dot > 0 && Math.abs(cross) <= COLLINEAR_SIN * lenProduct;
    }

    private static double signedAreaX2(float[] pts) {
        int n = pts.length >>> 1;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            sum += (double) pts[i * 2] * pts[j * 2 + 1] - (double) pts[j * 2] * pts[i * 2 + 1];
        }
        return sum;
    }

    private static double bboxExtentX2(float[] pts) {
        int n = pts.length >>> 1;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            float x = pts[i * 2];
            float y = pts[i * 2 + 1];
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
        double w = maxX - minX;
        double h = maxY - minY;
        return w * w + h * h;
    }

    private static void reverse(float[] pts) {
        int n = pts.length >>> 1;
        for (int i = 0, j = n - 1; i < j; i++, j--) {
            float tx = pts[i * 2];
            float ty = pts[i * 2 + 1];
            pts[i * 2] = pts[j * 2];
            pts[i * 2 + 1] = pts[j * 2 + 1];
            pts[j * 2] = tx;
            pts[j * 2 + 1] = ty;
        }
    }

    private static boolean isConvex(float[] pts) {
        int n = pts.length >>> 1;
        for (int i = 0; i < n; i++) {
            int prev = (i - 1 + n) % n;
            int next = (i + 1) % n;
            if (cross(pts, prev, i, next) <= 0) {
                return false;
            }
        }
        return true;
    }

    private static float[] triangleFan(float[] pts) {
        int n = pts.length >>> 1;
        float[] out = new float[(n - 2) * 6];
        int pos = 0;
        for (int i = 1; i < n - 1; i++) {
            out[pos++] = pts[0];
            out[pos++] = pts[1];
            out[pos++] = pts[i * 2];
            out[pos++] = pts[i * 2 + 1];
            out[pos++] = pts[(i + 1) * 2];
            out[pos++] = pts[(i + 1) * 2 + 1];
        }
        return out;
    }

    private static float[] earClip(float[] pts) {
        int n = pts.length >>> 1;
        int[] prev = new int[n];
        int[] next = new int[n];
        for (int i = 0; i < n; i++) {
            prev[i] = (i - 1 + n) % n;
            next[i] = (i + 1) % n;
        }

        float[] out = new float[(n - 2) * 6];
        int outPos = 0;
        int remaining = n;
        int maxIterations = n * n + 16;
        int iterations = 0;
        int cursor = 0;

        while (remaining > 3) {
            boolean clipped = false;
            int v = cursor;
            for (int k = 0; k < remaining; k++) {
                int a = prev[v];
                int b = next[v];
                if (isEar(pts, a, v, b, prev, next)) {
                    emit(out, outPos, pts, a, v, b);
                    outPos += 6;
                    next[a] = b;
                    prev[b] = a;
                    remaining--;
                    cursor = a;
                    clipped = true;
                    break;
                }
                v = next[v];
            }
            if (!clipped) {
                return null;
            }
            if (++iterations > maxIterations) {
                return null;
            }
        }

        emit(out, outPos, pts, prev[cursor], cursor, next[cursor]);
        return out;
    }

    private static boolean isEar(float[] pts, int a, int v, int b, int[] prev, int[] next) {
        double earArea = cross(pts, a, v, b);
        if (earArea <= 0) {
            return false;
        }
        for (int p = next[b]; p != a; p = next[p]) {
            if (insideOrOnEdge(pts, p, a, v, b, earArea)) {
                return false;
            }
        }
        return true;
    }

    // 边界点也必须排除，否则耳三角形可能跨越凹口。
    private static boolean insideOrOnEdge(float[] pts, int p, int a, int b, int c, double earArea) {
        double threshold = earArea * INSIDE_RATIO;
        return cross(pts, a, b, p) > -threshold
                && cross(pts, b, c, p) > -threshold
                && cross(pts, c, a, p) > -threshold;
    }

    private static double cross(float[] pts, int a, int b, int c) {
        double abx = pts[b * 2] - pts[a * 2];
        double aby = pts[b * 2 + 1] - pts[a * 2 + 1];
        double acx = pts[c * 2] - pts[a * 2];
        double acy = pts[c * 2 + 1] - pts[a * 2 + 1];
        return abx * acy - aby * acx;
    }

    private static boolean selfIntersects(float[] pts) {
        int n = pts.length >>> 1;
        for (int i = 0; i < n; i++) {
            int i2 = (i + 1) % n;
            for (int j = i + 1; j < n; j++) {
                int j2 = (j + 1) % n;
                if (j == i2 || j2 == i) {
                    continue;
                }
                if (segmentsIntersect(pts, i, i2, j, j2)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean segmentsIntersect(float[] pts, int a, int b, int c, int d) {
        double o1 = cross(pts, a, b, c);
        double o2 = cross(pts, a, b, d);
        double o3 = cross(pts, c, d, a);
        double o4 = cross(pts, c, d, b);
        if (o1 * o2 < 0 && o3 * o4 < 0) {
            return true;
        }
        return (o1 == 0 && onSegment(pts, a, b, c))
                || (o2 == 0 && onSegment(pts, a, b, d))
                || (o3 == 0 && onSegment(pts, c, d, a))
                || (o4 == 0 && onSegment(pts, c, d, b));
    }

    private static boolean onSegment(float[] pts, int a, int b, int p) {
        double px = pts[p * 2];
        double py = pts[p * 2 + 1];
        return px >= Math.min(pts[a * 2], pts[b * 2]) && px <= Math.max(pts[a * 2], pts[b * 2])
                && py >= Math.min(pts[a * 2 + 1], pts[b * 2 + 1]) && py <= Math.max(pts[a * 2 + 1], pts[b * 2 + 1]);
    }

    private static void emit(float[] out, int pos, float[] pts, int a, int b, int c) {
        out[pos] = pts[a * 2];
        out[pos + 1] = pts[a * 2 + 1];
        out[pos + 2] = pts[b * 2];
        out[pos + 3] = pts[b * 2 + 1];
        out[pos + 4] = pts[c * 2];
        out[pos + 5] = pts[c * 2 + 1];
    }
}
