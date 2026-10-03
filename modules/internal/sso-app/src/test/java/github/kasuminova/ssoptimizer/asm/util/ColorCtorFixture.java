package github.kasuminova.ssoptimizer.asm.util;

import java.awt.Color;

/**
 * {@link ColorCtorSanitizeProcessorTest} 的字节码夹具：覆盖真实游戏类样本中
 * 不出现的构造形态（嵌套构造参数、三元表达式参数、子类 super 链式调用）。
 * 本类仅供测试读取字节码，不被直接调用。
 */
public final class ColorCtorFixture {

    private ColorCtorFixture() {
    }

    /** 嵌套构造参数：内层 (III) 与外层 (IIII) 都须改写且互不串配。 */
    public static Color nested() {
        return new Color(new Color(1, 2, 3).getRGB(), 4, 5, 6);
    }

    /** 三元表达式参数：参数求值含跳转/标签，(FFFF) 变体。 */
    public static Color floatTernary(final boolean cond) {
        return new Color(cond ? 1.5f : 0.5f, -0.1f, 2.0f, 0.5f);
    }

    /** 子类 super 链式调用：无 NEW/DUP 配对，不得改写。 */
    public static final class Chained extends Color {
        public Chained() {
            super(1, 2, 3, 4);
        }
    }
}
