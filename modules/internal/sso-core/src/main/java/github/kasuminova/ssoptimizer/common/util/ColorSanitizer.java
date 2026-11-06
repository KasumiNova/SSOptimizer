package github.kasuminova.ssoptimizer.common.util;

import org.apache.log4j.Logger;

import java.awt.Color;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AWT {@link Color} 构造参数净化器，越界分量按类型范围钳制，NaN 归为零。
 * ASM 调用点标识用于按位置去重告警，合法分量不记录日志。
 */
public final class ColorSanitizer {

    private static final Logger LOGGER = Logger.getLogger(ColorSanitizer.class);

    private static final Set<String> WARNED_SITES = ConcurrentHashMap.newKeySet();

    private ColorSanitizer() {
    }

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

    private static boolean inByteRange(final int v) {
        return (v & ~0xFF) == 0;
    }

    private static boolean inUnitRange(final float v) {
        return v >= 0.0f && v <= 1.0f;
    }

    public static int clamp255(final int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    public static float clampUnit(final float v) {
        if (Float.isNaN(v)) {
            return 0.0f;
        }
        return v < 0.0f ? 0.0f : Math.min(v, 1.0f);
    }

    private static void warnOnce(final String site, final String original, final String clamped) {
        if (!WARNED_SITES.add(site)) {
            return;
        }
        LOGGER.warn("[SSOptimizer][ColorSanitize] AWT Color 构造参数越界已规范化：site="
                + site + " 原值=(" + original + ") 规范化=(" + clamped + ")",
                new Throwable("ColorSanitize 调用栈溯源"));
    }

    public static int warnedSiteCount() {
        return WARNED_SITES.size();
    }

    public static void resetForTest() {
        WARNED_SITES.clear();
    }
}
