package github.kasuminova.ssoptimizer.common.render.tessellation;

/**
 * 耳切法（Ear Clipping）简单多边形三角化器，{@link Triangulator} 的默认实现。
 * <p>
 * 面向船体蒙版轮廓（顶点数十级、无孔、内容静态）：
 * <ol>
 *   <li>预处理：剔除闭合重复末点、连续 epsilon 重复点（口径与游戏 {@code Tesselator.epsilon}
 *       一致，0.1F）、同向共线点；清理后不足 3 个顶点返回空数组。</li>
 *   <li>鞋带公式计算有向面积并统一为逆时针；预处理后面积仍退化为零判定为数值退化，返回 null。</li>
 *   <li>非相邻边逐对相交检查：自交/弱简单轮廓（交叉、顶点搭桥）耳切可能产生错误覆盖，返回 null。</li>
 *   <li>全凸多边形快路径：直接输出三角扇。</li>
 *   <li>耳切主循环 O(n²)：扫描凸顶点并验证无其他顶点落在耳内，逐耳切除；
 *       整轮找不到耳（自交/数值退化）返回 null，另设迭代上限保险，保证不抛异常、不死循环。</li>
 * </ol>
 * 全部几何判定使用 double 计算（输入为 float 顶点），相对容差均按几何量纲归一化。
 */
public final class EarClippingTriangulator implements Triangulator {

    private static final float[] EMPTY = new float[0];

    /** 顶点重合判定阈值，与游戏 {@code Tesselator.epsilon}（0.1F）口径一致（逐分量 abs 比较）。 */
    private static final float EPSILON = 0.1F;
    /** 共线剔除阈值：相邻边夹角正弦绝对值低于该值且两边同向（点积为正）时剔除中间点。 */
    private static final double COLLINEAR_SIN = 1e-3;
    /** 耳包含判定的数值容差（相对耳三角形面积），用于抵消浮点噪声。 */
    private static final double INSIDE_RATIO = 1e-9;
    /** 退化面积阈值（相对包围盒对角线平方）：预处理后 |有向面积| 低于该值视为自交/退化。 */
    private static final double DEGENERATE_AREA_RATIO = 1e-12;

    @Override
    public float[] triangulate(float[] xy, int vertexCount) {
        float[] pts = preprocess(xy, vertexCount);
        int n = pts.length >>> 1;
        if (n < 3) {
            return EMPTY;
        }

        // 自交/弱简单轮廓（如 vanilla wasp 的交叉轮廓、paragon 的重复顶点搭桥轮廓）：
        // 耳切可能「成功」但覆盖区域错误，必须交回 GLU 降级路径
        if (selfIntersects(pts)) {
            return null;
        }

        double areaX2 = signedAreaX2(pts);
        if (Math.abs(areaX2) <= bboxExtentX2(pts) * DEGENERATE_AREA_RATIO) {
            // 预处理后（共线点已剔除）有向面积仍为零：数值退化轮廓
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

    /**
     * 预处理：拷贝有效顶点 → 去连续 epsilon 重复点（含首尾闭合重复）→ 迭代去同向共线点。
     *
     * @return 清理后的顶点数组（x,y 交错），长度 {@code 2 * 清理后顶点数}
     */
    private static float[] preprocess(float[] xy, int vertexCount) {
        int n = Math.min(vertexCount, xy.length >>> 1);
        float[] pts = new float[n * 2];

        // 去连续重复点（保留首个点，后续点与上一个保留点比较）
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
        // 首尾闭合重复（getVertices 输出首点=末点）
        while (m > 1 && near(pts[0], pts[1], pts[(m - 1) * 2], pts[(m - 1) * 2 + 1])) {
            m--;
        }

        // 去同向共线点：剔除中间点可能制造新的共线，迭代至稳定，轮数上限 m 防意外死循环
        boolean changed = true;
        int rounds = 0;
        while (changed && m >= 3 && rounds++ <= m) {
            changed = false;
            for (int i = 0; i < m && m >= 3; i++) {
                int prev = (i - 1 + m) % m;
                int next = (i + 1) % m;
                if (collinear(pts, prev, i, next)) {
                    // 前移覆盖被剔除点
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

    /** 逐分量 abs 比较的近似重合判定，口径与游戏 {@code Tesselator.epsilon} 一致。 */
    private static boolean near(float ax, float ay, float bx, float by) {
        return Math.abs(ax - bx) < EPSILON && Math.abs(ay - by) < EPSILON;
    }

    /** 同向共线判定：相邻边夹角正弦低于阈值且点积为正（排除 180° 回折尖刺）。 */
    private static boolean collinear(float[] pts, int prev, int cur, int next) {
        double ux = pts[cur * 2] - pts[prev * 2];
        double uy = pts[cur * 2 + 1] - pts[prev * 2 + 1];
        double vx = pts[next * 2] - pts[cur * 2];
        double vy = pts[next * 2 + 1] - pts[cur * 2 + 1];
        double lenProduct = Math.hypot(ux, uy) * Math.hypot(vx, vy);
        if (lenProduct == 0) {
            // 零长度边已由重复点剔除处理，这里视为共线便于收敛
            return true;
        }
        double cross = ux * vy - uy * vx;
        double dot = ux * vx + uy * vy;
        return dot > 0 && Math.abs(cross) <= COLLINEAR_SIN * lenProduct;
    }

    /** 鞋带公式有向面积 ×2（逆时针为正）。 */
    private static double signedAreaX2(float[] pts) {
        int n = pts.length >>> 1;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            sum += (double) pts[i * 2] * pts[j * 2 + 1] - (double) pts[j * 2] * pts[i * 2 + 1];
        }
        return sum;
    }

    /** 包围盒对角线平方，用作面积阈值的量纲归一化基准。 */
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

    /** 反转顶点顺序（有向面积为负时将轮廓统一为逆时针）。 */
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

    /** 全凸判定：逆时针轮廓下所有顶点叉积严格为正（共线点已在预处理剔除）。 */
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

    /** 全凸快路径：以顶点 0 为扇心输出三角扇。 */
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

    /**
     * 耳切主循环。用 prev/next 双链表维护剩余顶点，每轮从游标起扫描一个耳并切除；
     * 整轮无耳返回 null。外循环次数恒等于切除数（n-3），迭代上限仅作数值异常保险。
     */
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
                // 自交或数值退化：整轮找不到耳
                return null;
            }
            if (++iterations > maxIterations) {
                return null;
            }
        }

        // 剩余 3 个顶点构成最后一个三角形
        emit(out, outPos, pts, prev[cursor], cursor, next[cursor]);
        return out;
    }

    /** 耳判定：v 为凸顶点且其余剩余顶点均不严格落在三角形 (a, v, b) 内。 */
    private static boolean isEar(float[] pts, int a, int v, int b, int[] prev, int[] next) {
        double earArea = cross(pts, a, v, b);
        if (earArea <= 0) {
            return false;
        }
        // 遍历除 a/v/b 外的所有剩余顶点（环形链表：从 b 的下一个走到 a 之前）
        for (int p = next[b]; p != a; p = next[p]) {
            if (insideOrOnEdge(pts, p, a, v, b, earArea)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 点包含判定（含边界）：顶点落在耳三角形内部<strong>或边上</strong>都使该耳失效。
     * 边界点必须算入：凹点恰好贴在耳对角线上时，若放行，耳三角形会跨过凹口切到多边形外
     * （L 形轮廓 (0,40)-(40,0) 对角线穿过凹点 (20,20) 的用例覆盖此场景）。
     */
    private static boolean insideOrOnEdge(float[] pts, int p, int a, int b, int c, double earArea) {
        double threshold = earArea * INSIDE_RATIO;
        return cross(pts, a, b, p) > -threshold
                && cross(pts, b, c, p) > -threshold
                && cross(pts, c, a, p) > -threshold;
    }

    /** 三点叉积 (b-a)×(c-a)，逆时针为正。 */
    private static double cross(float[] pts, int a, int b, int c) {
        double abx = pts[b * 2] - pts[a * 2];
        double aby = pts[b * 2 + 1] - pts[a * 2 + 1];
        double acx = pts[c * 2] - pts[a * 2];
        double acy = pts[c * 2 + 1] - pts[a * 2 + 1];
        return abx * acy - aby * acx;
    }

    /**
     * 自交/弱简单判定：任意一对非相邻边相交（含端点触碰、顶点重合搭桥）即视为非简单多边形。
     * 顶点数十级，O(n²) 逐对检查仅发生在首次三角化（结果按 Bounds 缓存），开销可忽略。
     */
    private static boolean selfIntersects(float[] pts) {
        int n = pts.length >>> 1;
        for (int i = 0; i < n; i++) {
            int i2 = (i + 1) % n;
            for (int j = i + 1; j < n; j++) {
                int j2 = (j + 1) % n;
                // 跳过共享端点的相邻边（含首尾环绕相邻）
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

    /** 线段相交判定（含端点触碰）：跨立实验 + 共线时在段判定。 */
    private static boolean segmentsIntersect(float[] pts, int a, int b, int c, int d) {
        double o1 = cross(pts, a, b, c);
        double o2 = cross(pts, a, b, d);
        double o3 = cross(pts, c, d, a);
        double o4 = cross(pts, c, d, b);
        if (o1 * o2 < 0 && o3 * o4 < 0) {
            return true;
        }
        // 端点落在另一线段上（含顶点重合搭桥）
        return (o1 == 0 && onSegment(pts, a, b, c))
                || (o2 == 0 && onSegment(pts, a, b, d))
                || (o3 == 0 && onSegment(pts, c, d, a))
                || (o4 == 0 && onSegment(pts, c, d, b));
    }

    /** 共线点 p 是否位于线段 (a, b) 的包围盒内（调用前已确认叉积为零）。 */
    private static boolean onSegment(float[] pts, int a, int b, int p) {
        double px = pts[p * 2];
        double py = pts[p * 2 + 1];
        return px >= Math.min(pts[a * 2], pts[b * 2]) && px <= Math.max(pts[a * 2], pts[b * 2])
                && py >= Math.min(pts[a * 2 + 1], pts[b * 2 + 1]) && py <= Math.max(pts[a * 2 + 1], pts[b * 2 + 1]);
    }

    /** 向输出数组写入一个三角形（a, b, c）。 */
    private static void emit(float[] out, int pos, float[] pts, int a, int b, int c) {
        out[pos] = pts[a * 2];
        out[pos + 1] = pts[a * 2 + 1];
        out[pos + 2] = pts[b * 2];
        out[pos + 3] = pts[b * 2 + 1];
        out[pos + 4] = pts[c * 2];
        out[pos + 5] = pts[c * 2 + 1];
    }
}
