package github.kasuminova.ssoptimizer.common.render.tessellation;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EarClippingTriangulator} 的纯几何逻辑测试：
 * 凸/凹多边形面积一致性、顺时针输入、预处理（重复点/共线点）、自交返回 null，
 * 以及 vanilla .ship 真实船体轮廓语料的批量面积校验。
 */
class EarClippingTriangulatorTest {

    /** 面积校验相对容差。 */
    private static final double AREA_TOLERANCE = 1e-4;

    private final Triangulator triangulator = new EarClippingTriangulator();

    // ---------- 凸多边形 ----------

    @Test
    void triangleYieldsSingleTriangleWithSameArea() {
        float[] xy = {0, 0, 40, 0, 0, 30};
        float[] soup = triangulator.triangulate(xy, 3);
        assertNotNull(soup);
        assertEquals(1, soup.length / 6);
        assertAreaConsistent(xy, 3, soup);
    }

    @Test
    void rectangleYieldsTwoTrianglesWithSameArea() {
        float[] xy = {0, 0, 50, 0, 50, 20, 0, 20};
        float[] soup = triangulator.triangulate(xy, 4);
        assertNotNull(soup);
        assertEquals(2, soup.length / 6);
        assertAreaConsistent(xy, 4, soup);
    }

    @Test
    void regularConvexPolygonYieldsFanWithSameArea() {
        int n = 12;
        float[] xy = regularPolygon(n, 100f);
        float[] soup = triangulator.triangulate(xy, n);
        assertNotNull(soup);
        assertEquals(n - 2, soup.length / 6);
        assertAreaConsistent(xy, n, soup);
    }

    // ---------- 凹多边形 ----------

    @Test
    void lShapeYieldsConsistentArea() {
        // L 形（6 顶点，含一个凹角）
        float[] xy = {0, 0, 40, 0, 40, 20, 20, 20, 20, 40, 0, 40};
        float[] soup = triangulator.triangulate(xy, 6);
        assertNotNull(soup);
        assertEquals(4, soup.length / 6);
        assertAreaConsistent(xy, 6, soup);
        assertSamplingOwnership(xy, 6, soup);
    }

    @Test
    void uShapeYieldsConsistentArea() {
        // U 形（8 顶点，含两个凹角）
        float[] xy = {0, 0, 40, 0, 40, 30, 30, 30, 30, 10, 10, 10, 10, 30, 0, 30};
        float[] soup = triangulator.triangulate(xy, 8);
        assertNotNull(soup);
        assertEquals(6, soup.length / 6);
        assertAreaConsistent(xy, 8, soup);
        assertSamplingOwnership(xy, 8, soup);
    }

    @Test
    void starShapeYieldsConsistentArea() {
        // 五角星轮廓（10 顶点交替内外半径，简单多边形但含 5 个凹角）
        int n = 10;
        float[] xy = new float[n * 2];
        for (int i = 0; i < n; i++) {
            double angle = Math.PI / 2 + i * Math.PI / 5;
            double radius = (i % 2 == 0) ? 50 : 20;
            xy[i * 2] = (float) (Math.cos(angle) * radius);
            xy[i * 2 + 1] = (float) (Math.sin(angle) * radius);
        }
        float[] soup = triangulator.triangulate(xy, n);
        assertNotNull(soup);
        assertEquals(n - 2, soup.length / 6);
        assertAreaConsistent(xy, n, soup);
        assertSamplingOwnership(xy, n, soup);
    }

    // ---------- 方向与预处理 ----------

    @Test
    void clockwiseInputYieldsEquivalentResult() {
        float[] ccw = {0, 0, 40, 0, 40, 20, 20, 20, 20, 40, 0, 40};
        float[] cw = new float[ccw.length];
        for (int i = 0; i < 6; i++) {
            int j = (6 - i) % 6;
            cw[i * 2] = ccw[j * 2];
            cw[i * 2 + 1] = ccw[j * 2 + 1];
        }
        float[] soup = triangulator.triangulate(cw, 6);
        assertNotNull(soup);
        assertEquals(4, soup.length / 6);
        assertAreaConsistent(cw, 6, soup);
    }

    @Test
    void preprocessingRemovesClosingDuplicateConsecutiveDuplicatesAndCollinearPoints() {
        // 矩形 + 闭合重复末点 + 连续重复点 + 底边共线中点
        float[] xy = {
                0, 0,
                0.05f, 0.05f,   // 与首点 epsilon 重复
                25, 0,          // 底边共线中点
                50, 0,
                50, 0,          // 连续完全重复
                50, 20,
                0, 20,
                0, 0            // 闭合重复末点
        };
        float[] soup = triangulator.triangulate(xy, 8);
        assertNotNull(soup);
        // 清理后回到 4 顶点矩形：2 个三角形
        assertEquals(2, soup.length / 6);
        assertEquals(50.0 * 20.0, soupArea(soup), 50.0 * 20.0 * AREA_TOLERANCE);
    }

    @Test
    void degeneratePointInputYieldsEmpty() {
        float[] xy = {5, 5, 5.05f, 5.05f};
        float[] soup = triangulator.triangulate(xy, 2);
        assertNotNull(soup);
        assertEquals(0, soup.length);
    }

    // ---------- 自交 ----------

    @Test
    void bowtieSelfIntersectionReturnsNull() {
        float[] xy = {0, 0, 20, 20, 0, 20, 20, 0};
        assertNull(triangulator.triangulate(xy, 4));
    }

    // ---------- 真实船体语料 ----------

    @Test
    void vanillaShipHullBoundsTriangulateWithConsistentArea() throws IOException {
        Path hullsDir = Paths.get("/mnt/store/Games/Starsector098-linux/data/data/hulls");
        Assumptions.assumeTrue(Files.isDirectory(hullsDir), "vanilla 游戏目录不存在，跳过真实船体语料测试");

        List<Path> hullFiles = new ArrayList<>();
        try (Stream<Path> stream = Files.list(hullsDir)) {
            stream.filter(p -> p.toString().endsWith(".ship")).sorted().forEach(hullFiles::add);
        }
        Assumptions.assumeTrue(!hullFiles.isEmpty(), "未找到 .ship 文件");

        Pattern boundsPattern = Pattern.compile("\"bounds\"\\s*:\\s*\\[([^]]*)]");
        int checked = 0;
        int nullCount = 0;
        StringBuilder failures = new StringBuilder();
        for (Path file : hullFiles) {
            Matcher matcher = boundsPattern.matcher(Files.readString(file));
            if (!matcher.find()) {
                continue;
            }
            String[] parts = matcher.group(1).split(",");
            float[] xy = new float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                xy[i] = Float.parseFloat(parts[i].trim());
            }
            int vertexCount = xy.length / 2;
            float[] soup = triangulator.triangulate(xy, vertexCount);
            String name = file.getFileName().toString();
            if (soup == null) {
                // 仅允许确实自交/弱简单的轮廓返回 null（走 GLU 降级）；
                // 用测试侧独立的逐边相交校验复核，防止误拒简单多边形
                if (!selfIntersects(xy, vertexCount)) {
                    failures.append(name).append("(简单多边形意外耳切失败) ");
                } else {
                    nullCount++;
                }
                continue;
            }
            double expected = Math.abs(shoelace(xy, vertexCount));
            double actual = soupArea(soup);
            if (Math.abs(actual - expected) > expected * 1e-3) {
                failures.append(name).append("(面积偏差 expected=").append(expected)
                        .append(" actual=").append(actual).append(") ");
                continue;
            }
            assertTrue(soup.length / 6 <= vertexCount - 2, name + " 三角形数量超过 n-2");
            checked++;
        }
        assertTrue(checked > 0, "未校验任何船体");
        assertEquals("", failures.toString(), "部分船体轮廓三角化校验失败");
        // 哨兵：vanilla 语料中已知 wasp/paragon 为非简单轮廓，若未来 null 数异常膨胀需关注
        assertTrue(nullCount <= 4, "返回 null 的船体数量异常: " + nullCount);
    }

    /**
     * 测试侧独立的自交/弱简单校验：任意一对非相邻边相交（含端点触碰）即非简单多边形。
     * 与实现侧算法相互独立，用于复核语料中返回 null 的合法性。
     */
    private static boolean selfIntersects(float[] xy, int vertexCount) {
        for (int i = 0; i < vertexCount; i++) {
            int i2 = (i + 1) % vertexCount;
            for (int j = i + 1; j < vertexCount; j++) {
                int j2 = (j + 1) % vertexCount;
                if (j == i2 || j2 == i) {
                    continue;
                }
                double o1 = orient(xy, i, i2, j);
                double o2 = orient(xy, i, i2, j2);
                double o3 = orient(xy, j, j2, i);
                double o4 = orient(xy, j, j2, i2);
                if (o1 * o2 < 0 && o3 * o4 < 0) {
                    return true;
                }
                if ((o1 == 0 && onSegment(xy, i, i2, j)) || (o2 == 0 && onSegment(xy, i, i2, j2))
                        || (o3 == 0 && onSegment(xy, j, j2, i)) || (o4 == 0 && onSegment(xy, j, j2, i2))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static double orient(float[] xy, int a, int b, int c) {
        return (xy[b * 2] - xy[a * 2]) * (double) (xy[c * 2 + 1] - xy[a * 2 + 1])
                - (xy[b * 2 + 1] - xy[a * 2 + 1]) * (double) (xy[c * 2] - xy[a * 2]);
    }

    private static boolean onSegment(float[] xy, int a, int b, int p) {
        float px = xy[p * 2], py = xy[p * 2 + 1];
        return px >= Math.min(xy[a * 2], xy[b * 2]) && px <= Math.max(xy[a * 2], xy[b * 2])
                && py >= Math.min(xy[a * 2 + 1], xy[b * 2 + 1]) && py <= Math.max(xy[a * 2 + 1], xy[b * 2 + 1]);
    }

    // ---------- 工具 ----------

    /** 生成以原点为中心的正 n 边形顶点（逆时针）。 */
    private static float[] regularPolygon(int n, float radius) {
        float[] xy = new float[n * 2];
        for (int i = 0; i < n; i++) {
            double angle = i * 2.0 * Math.PI / n;
            xy[i * 2] = (float) (Math.cos(angle) * radius);
            xy[i * 2 + 1] = (float) (Math.sin(angle) * radius);
        }
        return xy;
    }

    /** 鞋带公式面积（取绝对值）。 */
    private static double shoelace(float[] xy, int vertexCount) {
        double sum = 0;
        for (int i = 0; i < vertexCount; i++) {
            int j = (i + 1) % vertexCount;
            sum += (double) xy[i * 2] * xy[j * 2 + 1] - (double) xy[j * 2] * xy[i * 2 + 1];
        }
        return sum / 2.0;
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

    /** 断言三角形 soup 面积和与多边形鞋带面积一致（相对容差 {@link #AREA_TOLERANCE}）。 */
    private static void assertAreaConsistent(float[] xy, int vertexCount, float[] soup) {
        double expected = Math.abs(shoelace(xy, vertexCount));
        double actual = soupArea(soup);
        assertEquals(expected, actual, expected * AREA_TOLERANCE,
                "三角形 soup 面积和与多边形面积不一致");
    }

    /**
     * 抽样点唯一归属校验：包围盒内随机抽样，偶奇规则判定在多边形内的点
     * 必须恰好落在 1 个三角形内，外部点必须落在 0 个三角形内。
     */
    private static void assertSamplingOwnership(float[] xy, int vertexCount, float[] soup) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < vertexCount; i++) {
            minX = Math.min(minX, xy[i * 2]);
            minY = Math.min(minY, xy[i * 2 + 1]);
            maxX = Math.max(maxX, xy[i * 2]);
            maxY = Math.max(maxY, xy[i * 2 + 1]);
        }
        Random random = new Random(42);
        for (int s = 0; s < 2000; s++) {
            double px = minX + (maxX - minX) * random.nextDouble();
            double py = minY + (maxY - minY) * random.nextDouble();
            boolean insidePolygon = evenOddContains(xy, vertexCount, px, py);
            int containing = 0;
            for (int i = 0; i + 5 < soup.length; i += 6) {
                if (triangleContains(soup, i, px, py)) {
                    containing++;
                }
            }
            assertEquals(insidePolygon ? 1 : 0, containing,
                    "抽样点 (" + px + "," + py + ") 归属三角形数量异常");
        }
    }

    /** 偶奇规则点在多边形内判定。 */
    private static boolean evenOddContains(float[] xy, int vertexCount, double px, double py) {
        boolean inside = false;
        for (int i = 0, j = vertexCount - 1; i < vertexCount; j = i++) {
            double xi = xy[i * 2], yi = xy[i * 2 + 1];
            double xj = xy[j * 2], yj = xy[j * 2 + 1];
            if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** 点在三角形内判定（叉积同号，容差贴近边界时按内部计）。 */
    private static boolean triangleContains(float[] soup, int offset, double px, double py) {
        double ax = soup[offset], ay = soup[offset + 1];
        double bx = soup[offset + 2], by = soup[offset + 3];
        double cx = soup[offset + 4], cy = soup[offset + 5];
        double d0 = (bx - ax) * (py - ay) - (by - ay) * (px - ax);
        double d1 = (cx - bx) * (py - by) - (cy - by) * (px - bx);
        double d2 = (ax - cx) * (py - cy) - (ay - cy) * (px - cx);
        boolean hasNeg = d0 < -1e-6 || d1 < -1e-6 || d2 < -1e-6;
        boolean hasPos = d0 > 1e-6 || d1 > 1e-6 || d2 > 1e-6;
        return !(hasNeg && hasPos);
    }
}
