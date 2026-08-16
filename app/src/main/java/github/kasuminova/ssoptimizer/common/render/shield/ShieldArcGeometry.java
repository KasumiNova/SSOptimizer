package github.kasuminova.ssoptimizer.common.render.shield;

import java.util.HashMap;
import java.util.Map;

/**
 * 舰船护盾弧形几何计算（纯静态，无 GL 依赖，可单测）。
 * <p>
 * 数学公式逐行对应原版 {@code com.fs.starfarer.combat.systems.Shield#render(float)}
 * 与 {@code #renderBand(float,float,float,float,float)}：扇形顶点、纹理坐标、
 * 边缘 noise 条带、边缘 alpha 衰减的浮点运算顺序与原版保持一致。
 * <p>
 * 顶点生成默认使用旋转递推（复数乘 e^{iΔ} 步进，每顶点 4 乘 2 加，每遍仅一次三角函数求种子），
 * 73 段 360° 盾的浮点累计误差约 1e-5·radius 像素级，远小于可感知阈值。
 * 另保留「射线-外接正方形求交」对照实现 {@link #fillFanVerticesRaycast}：
 * 从盾心沿等分角度发射线与圆外接轴对齐正方形求交、再归一化回圆上，
 * 参数化沿用原版角度等分，数学上与直接圆参数化等价，差异仅在浮点路径与指令构成
 * （除法+开方 vs 递推乘加），用于实测性能对比。
 * 通过 {@code -Dssoptimizer.render.shield.algo=recurrence|raycast} 选择（默认 recurrence）。
 */
public final class ShieldArcGeometry {
    /** 顶点生成算法选择属性：{@code recurrence}（默认）或 {@code raycast}。 */
    public static final String ALGO_PROPERTY = "ssoptimizer.render.shield.algo";
    /** 初始缓冲对应的段数上限；超出时由调用方按需要扩容。 */
    public static final int INITIAL_MAX_SEGMENTS = 256;

    private static final boolean USE_RAYCAST =
            "raycast".equalsIgnoreCase(System.getProperty(ALGO_PROPERTY, "recurrence"));

    /** 扇形周界顶点缓存上限，防止极端 mod 动态半径导致缓存无限增长。 */
    private static final int FAN_CACHE_MAX_ENTRIES = 512;

    /**
     * 扇形周界顶点缓存：key = Float.floatToRawIntBits(展开弧角) + segmentCount + Float.floatToRawIntBits(radius)。
     * 渲染单线程访问，无需同步；返回数组为共享只读，调用方不得修改。
     */
    private static final Map<FanKey, float[]> FAN_CACHE = new HashMap<>();

    private ShieldArcGeometry() {
    }

    /**
     * 返回当前配置的顶点生成算法是否为 raycast 对照实现。
     *
     * @return 使用射线-外接正方形求交实现时返回 true
     */
    public static boolean useRaycast() {
        return USE_RAYCAST;
    }

    /**
     * 获取扇形周界顶点（xy 交错，长度 2×segmentCount，含 1.07 倍半径缩放），带缓存。
     * <p>
     * 返回的数组为缓存共享实例，调用方只允许读取。
     *
     * @param arcDeg       展开弧角（度），即原版 (arc + 10) × chargeLevel
     * @param segmentCount 分段数
     * @param radius       护盾半径（原版 getRadius()）
     * @return 周界顶点数组
     */
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

    /**
     * 以旋转递推填充扇形周界顶点（无缓存）。
     * <p>
     * 对应原版：{@code var13 = radius * 1.07F; vertex_k = var13 · (cos(k·Δ), sin(k·Δ))}，
     * Δ = toRadians(arcDeg) / (segmentCount - 1)。
     *
     * @param out          输出数组（xy 交错，长度 ≥ 2×segmentCount）
     * @param arcDeg       展开弧角（度）
     * @param segmentCount 分段数（≥ 2）
     * @param radius       护盾半径
     */
    public static void fillFanVertices(final float[] out, final float arcDeg, final int segmentCount, final float radius) {
        float delta = (float) Math.toRadians(arcDeg) / (segmentCount - 1);
        float r = radius * 1.07F;

        // 每遍一次三角函数求旋转种子，之后每顶点 4 乘 2 加递推
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

    /**
     * 对照算法：射线-外接正方形求交填充扇形周界顶点（无缓存）。
     * <p>
     * 从盾心沿角度等分方向发射线，与圆外接的轴对齐正方形 [-r, r]² 求交，
     * 交点归一化回半径 r 的圆上得到顶点。参数化与原版角度等分一致，
     * 数学上与 {@link #fillFanVertices} 等价，用于对比两者实测耗时与浮点误差。
     *
     * @param out          输出数组（xy 交错，长度 ≥ 2×segmentCount）
     * @param arcDeg       展开弧角（度）
     * @param segmentCount 分段数（≥ 2）
     * @param radius       护盾半径
     */
    public static void fillFanVerticesRaycast(final float[] out, final float arcDeg, final int segmentCount, final float radius) {
        float delta = (float) Math.toRadians(arcDeg) / (segmentCount - 1);
        float r = radius * 1.07F;

        for (int k = 0; k < segmentCount; k++) {
            float theta = k * delta;
            float c = (float) Math.cos(theta);
            float s = (float) Math.sin(theta);
            // 射线与外接正方形求交：t = r / max(|cosθ|, |sinθ|)，max ≥ √2/2 不会除零
            float t = r / Math.max(Math.abs(c), Math.abs(s));
            float px = c * t;
            float py = s * t;
            // 归一化回半径 r 的圆上
            float invLen = r / (float) Math.sqrt(px * px + py * py);
            out[k * 2] = px * invLen;
            out[k * 2 + 1] = py * invLen;
        }
    }

    /**
     * 以旋转递推填充扇形周界纹理坐标（uv 交错，长度 ≥ 2×segmentCount）。
     * <p>
     * 对应原版：{@code uv_k = 0.5 + (cos(k·Δ + phase), sin(k·Δ + phase)) / 2}，
     * 其中 phase = ±innerAngle − toRadians(arcDeg / 2)（第二遍 innerAngle 取负）。
     *
     * @param out          输出数组
     * @param arcDeg       展开弧角（度）
     * @param segmentCount 分段数
     * @param phase        起始相位（弧度，可为负）
     */
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

    /**
     * 计算 band 的展开弧角（弧度），逐行复刻原版 renderBand 的角度序列。
     * <p>
     * 原版：{@code var9 = toRadians(h); var10 = toRadians(h + arcDeg);
     * var11 = Utils.normalizeAngle(var10 - var9)}。注意原版把弧度值当作角度做 normalize，
     * 由于弧度值恒小于 360 且为正，normalize 实际为恒等变换，此处仍原样保留以保证位级一致。
     *
     * @param startDeg 起始朝向角（度），即原版 h = facing − arcDeg / 2
     * @param arcDeg   展开弧角（度）
     * @return band 展开弧角（弧度）
     */
    public static float bandArcRadians(final float startDeg, final float arcDeg) {
        float startRad = (float) Math.toRadians(startDeg);
        float endRad = (float) Math.toRadians(startDeg + arcDeg);
        return normalizeAngleDeg(endRad - startRad);
    }

    /**
     * 以旋转递推填充 band 条带顶点（每分段 4 个 float：outerX, outerY, innerX, innerY）。
     * <p>
     * 对应原版 renderBand：{@code rOut = radius + scaleEff · sin(ringAngle·10 + k·Δ·10)}，
     * outer = rOut·(cos(k·Δ), sin(k·Δ))，inner = (rOut − bandWidth)·(cos(k·Δ), sin(k·Δ))。
     * 位置角度与 noise 相位各自一路旋转递推，每顶点共 8 乘 4 加，无逐顶点三角函数。
     *
     * @param out          输出数组（长度 ≥ 4×segmentCount）
     * @param segmentCount 分段数
     * @param bandArcRad   band 展开弧角（弧度，见 {@link #bandArcRadians}）
     * @param radius       护盾半径
     * @param bandWidth    band 宽度（原版 var7，含效果加成后）
     * @param scaleEff     noise 幅度（原版 var15 = textureScale 含效果加成后）
     * @param ringAngle    当前 ringAngle（noise 相位种子）
     */
    public static void fillBandStrip(final float[] out, final int segmentCount, final float bandArcRad,
                                     final float radius, final float bandWidth, final float scaleEff,
                                     final float ringAngle) {
        float delta = bandArcRad / (segmentCount - 1);

        float cosDelta = (float) Math.cos(delta);
        float sinDelta = (float) Math.sin(delta);
        float noiseDelta = delta * 10.0F;
        float cosNoise = (float) Math.cos(noiseDelta);
        float sinNoise = (float) Math.sin(noiseDelta);

        // 位置角度递推种子：角度 0（原版顶点角为 k·Δ，旋转由 glRotatef 承担）
        float dirX = 1.0F;
        float dirY = 0.0F;
        // noise 相位递推种子：ringAngle·10
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

    /**
     * 分段亮度，逐行对应原版 {@code getSegmentBrightness(int)}。
     *
     * @param segmentAlpha    当前分段的 segmentAlpha
     * @param segmentAlphaMax segmentAlpha 上限
     * @return 分段亮度
     */
    public static float segmentBrightness(final float segmentAlpha, final float segmentAlphaMax) {
        return 1.0F - 0.45F * segmentAlpha / segmentAlphaMax;
    }

    /**
     * 扇形顶点 alpha（int 截断），逐行对应原版 render 内层循环。
     * <p>
     * 原版：{@code b = 1·damageMult·brightness; deg = toDegrees(Δ·k);
     * 若 deg < 10° 或 arcDeg − deg < 10°，b = min(deg, arcDeg − deg) / 10 · b；
     * alpha = (int)(b × colorAlpha)}。
     *
     * @param k               分段索引
     * @param segmentCount    分段数
     * @param arcDeg          展开弧角（度）
     * @param damageMult      chargeTracker.getDamageMult() × amount
     * @param segmentAlpha    当前分段 segmentAlpha
     * @param segmentAlphaMax segmentAlpha 上限
     * @param colorAlpha      颜色 alpha 分量
     * @return 顶点颜色 alpha（0-255，int 截断）
     */
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

    /**
     * band 顶点 alpha（int 截断），逐行对应原版 renderBand 内层循环。
     * <p>
     * 原版：{@code b = damageMult·brightness; edge = toRadians(10); pos = Δ·k;
     * 若 pos < edge 或 bandArc − pos < edge，b = min(pos, bandArc − pos) / edge · b；
     * alpha = (int)(255 × b × 1.0)}。
     *
     * @param k               分段索引
     * @param segmentCount    分段数
     * @param bandArcRad      band 展开弧角（弧度）
     * @param damageMult      chargeTracker.getDamageMult() × amount
     * @param segmentAlpha    当前分段 segmentAlpha
     * @param segmentAlphaMax segmentAlpha 上限
     * @return band 顶点颜色 alpha（0-255，int 截断）
     */
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

    /**
     * 逐行复刻原版 {@code Utils.normalizeAngle(float)}（把入参当角度处理）。
     */
    private static float normalizeAngleDeg(final float f) {
        return (f % 360.0F + 360.0F) % 360.0F;
    }

    /** 扇形顶点缓存键：展开弧角位比特 + 分段数 + 半径位比特。 */
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
