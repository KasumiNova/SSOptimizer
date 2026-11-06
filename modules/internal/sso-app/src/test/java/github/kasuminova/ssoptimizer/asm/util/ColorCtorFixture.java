package github.kasuminova.ssoptimizer.asm.util;

import java.awt.Color;

public final class ColorCtorFixture {

    private ColorCtorFixture() {
    }

    public static Color nested() {
        return new Color(new Color(1, 2, 3).getRGB(), 4, 5, 6);
    }

    public static Color floatTernary(final boolean cond) {
        return new Color(cond ? 1.5f : 0.5f, -0.1f, 2.0f, 0.5f);
    }

    public static final class Chained extends Color {
        public Chained() {
            super(1, 2, 3, 4);
        }
    }
}
