package github.kasuminova.ssoptimizer.common.util;

import org.apache.log4j.Logger;

import java.awt.Color;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AWT {@link Color} 构造参数净化器。
 * <p>
 * 动机：{@code new Color(r, g, b, a)} 对越界分量直接抛
 * {@link IllegalArgumentException}（实机：amazigh 锻船厂 hullmod
 * {@code ASF_ArtyWepRem.advanceInCombat} 用越界 alpha 构造 Color，战斗内
 * 逐帧崩溃）。模组/引擎内所有 Color 构造调用点由
 * {@code ColorCtorSanitizeProcessor}（ASM）改写为本类的静态工厂：
 * 分量在合法域内走零开销快速路径；越界时按 0..255（int）/ 0.0..1.0（float）
 * 钳制（NaN 归 0），并按调用点 warn-once 打印规范化日志。
 * <p>
 * site 参数由 ASM 改写器在织入期烧录（{@code 类名#方法名@序号}），
 * 稳态下 warn-once 判定是一次 map 查询，不需要运行时栈回溯。
 */
public final class ColorSanitizer {

    private static final Logger LOGGER = Logger.getLogger(ColorSanitizer.class);

    /** 已告警过的调用点（warn-once）。 */
    private static final Set<String> WARNED_SITES = ConcurrentHashMap.newKeySet();

    private ColorSanitizer() {
    }

    /**
     * {@code new Color(int, int, int)} 的净化等价。
     *
     * @param site 调用点标识（ASM 织入期烧录）
     */
    public static Color create(final int r, final int g, final int b, final String site) {
        if (inByteRange(r) && inByteRange(g) && inByteRange(b)) {
            return new Color(r, g, b);
        }
        final int cr = clamp255(r);
        final int cg = clamp255(g);
        final int cb = clamp255(b);
        warnOnce(site, r + "," + g + "," + b, cr + "," + cg + "," + cb);
        return new Color(cr, cg, cb);
    }

    /**
     * {@code new Color(int, int, int, int)} 的净化等价。
     *
     * @param site 调用点标识（ASM 织入期烧录）
     */
    public static Color create(final int r, final int g, final int b, final int a, final String site) {
        if (inByteRange(r) && inByteRange(g) && inByteRange(b) && inByteRange(a)) {
            return new Color(r, g, b, a);
        }
        final int cr = clamp255(r);
        final int cg = clamp255(g);
        final int cb = clamp255(b);
        final int ca = clamp255(a);
        warnOnce(site, r + "," + g + "," + b + "," + a, cr + "," + cg + "," + cb + "," + ca);
        return new Color(cr, cg, cb, ca);
    }

    /**
     * {@code new Color(float, float, float)} 的净化等价。
     *
     * @param site 调用点标识（ASM 织入期烧录）
     */
    public static Color create(final float r, final float g, final float b, final String site) {
        if (inUnitRange(r) && inUnitRange(g) && inUnitRange(b)) {
            return new Color(r, g, b);
        }
        final float cr = clampUnit(r);
        final float cg = clampUnit(g);
        final float cb = clampUnit(b);
        warnOnce(site, r + "," + g + "," + b, cr + "," + cg + "," + cb);
        return new Color(cr, cg, cb);
    }

    /**
     * {@code new Color(float, float, float, float)} 的净化等价。
     *
     * @param site 调用点标识（ASM 织入期烧录）
     */
    public static Color create(final float r, final float g, final float b, final float a, final String site) {
        if (inUnitRange(r) && inUnitRange(g) && inUnitRange(b) && inUnitRange(a)) {
            return new Color(r, g, b, a);
        }
        final float cr = clampUnit(r);
        final float cg = clampUnit(g);
        final float cb = clampUnit(b);
        final float ca = clampUnit(a);
        warnOnce(site, r + "," + g + "," + b + "," + a, cr + "," + cg + "," + cb + "," + ca);
        return new Color(cr, cg, cb, ca);
    }

    /** int 分量是否在 0..255 合法域（快速路径判定，负数高位非零必然失败）。 */
    private static boolean inByteRange(final int v) {
        return (v & ~0xFF) == 0;
    }

    /** float 分量是否在 0.0..1.0 合法域（NaN 比较恒假，落入慢速路径）。 */
    private static boolean inUnitRange(final float v) {
        return v >= 0.0f && v <= 1.0f;
    }

    /** int 分量钳制到 0..255。 */
    public static int clamp255(final int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    /** float 分量钳制到 0.0..1.0（NaN 归 0——Color 构造对 NaN 同样抛越界）。 */
    public static float clampUnit(final float v) {
        if (Float.isNaN(v)) {
            return 0.0f;
        }
        return v < 0.0f ? 0.0f : Math.min(v, 1.0f);
    }

    /** 按调用点 warn-once 输出规范化日志（附原值与钳制结果）。 */
    private static void warnOnce(final String site, final String original, final String clamped) {
        if (!WARNED_SITES.add(site)) {
            return;
        }
        LOGGER.warn("[SSOptimizer][ColorSanitize] AWT Color 构造参数越界已规范化：site="
                + site + " 原值=(" + original + ") 规范化=(" + clamped + ")",
                new Throwable("ColorSanitize 调用栈溯源"));
    }

    /** 已告警调用点数（测试用）。 */
    public static int warnedSiteCount() {
        return WARNED_SITES.size();
    }

    /** 清空 warn-once 记录（测试用）。 */
    public static void resetForTest() {
        WARNED_SITES.clear();
    }
}
