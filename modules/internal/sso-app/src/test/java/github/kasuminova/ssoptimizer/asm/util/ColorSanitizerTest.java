package github.kasuminova.ssoptimizer.common.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ColorSanitizer} 分量钳制与 warn-once 行为验证。
 */
class ColorSanitizerTest {

    @BeforeEach
    void resetWarnedSites() {
        ColorSanitizer.resetForTest();
    }

    @Test
    void intComponentsWithinRangePassThroughWithoutWarning() {
        Color four = ColorSanitizer.create(10, 20, 30, 40, "site-pass-4");
        assertEquals(new Color(10, 20, 30, 40), four);
        Color three = ColorSanitizer.create(0, 128, 255, "site-pass-3");
        assertEquals(new Color(0, 128, 255), three);
        assertEquals(0, ColorSanitizer.warnedSiteCount(), "合法域分量不得触发告警");
    }

    @Test
    void outOfRangeIntComponentsAreClampedAndWarnedOncePerSite() {
        Color c = ColorSanitizer.create(-5, 300, 128, 999, "site-clamp");
        assertEquals(0, c.getRed());
        assertEquals(255, c.getGreen());
        assertEquals(128, c.getBlue());
        assertEquals(255, c.getAlpha());
        assertEquals(1, ColorSanitizer.warnedSiteCount());

        // 同一调用点再次越界不重复告警
        ColorSanitizer.create(-5, 300, 128, 999, "site-clamp");
        assertEquals(1, ColorSanitizer.warnedSiteCount(), "同一 site 必须 warn-once");

        // 不同调用点独立告警
        ColorSanitizer.create(0, 0, 0, 256, "site-clamp-other");
        assertEquals(2, ColorSanitizer.warnedSiteCount());
    }

    @Test
    void threeArgIntVariantIsClamped() {
        Color c = ColorSanitizer.create(256, -1, 0, "site-clamp-3");
        assertEquals(new Color(255, 0, 0), c);
        assertEquals(1, ColorSanitizer.warnedSiteCount());
    }

    @Test
    void floatComponentsWithinRangePassThroughWithoutWarning() {
        Color c = ColorSanitizer.create(0.0f, 0.5f, 1.0f, 0.25f, "site-float-pass");
        assertEquals(new Color(0.0f, 0.5f, 1.0f, 0.25f), c);
        assertEquals(0, ColorSanitizer.warnedSiteCount());
    }

    @Test
    void outOfRangeFloatComponentsAreClamped() {
        Color c = ColorSanitizer.create(1.5f, -0.5f, 0.5f, 2.0f, "site-float-clamp");
        assertEquals(new Color(1.0f, 0.0f, 0.5f, 1.0f), c);
        assertEquals(1, ColorSanitizer.warnedSiteCount());
    }

    @Test
    void nanFloatComponentGoesToZeroInsteadOfThrowing() {
        // 原版 new Color(NaN, ...) 抛 IllegalArgumentException，净化语义为钳 0
        Color c = ColorSanitizer.create(Float.NaN, 0.5f, 0.5f, "site-float-nan");
        assertEquals(new Color(0.0f, 0.5f, 0.5f), c);
        assertEquals(1, ColorSanitizer.warnedSiteCount());
    }
}
