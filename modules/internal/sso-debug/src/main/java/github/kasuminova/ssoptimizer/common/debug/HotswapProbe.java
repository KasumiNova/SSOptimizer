package github.kasuminova.ssoptimizer.common.debug;

/**
 * hotswap 实机验证探针。
 *
 * <p>动机：验证 L2 hotswap 链路（自 attach → redefineClasses）在游戏运行时
 * 真实生效，需要一个可随时安全重定义的目标类。本类无副作用、无业务引用，
 * 仅承载 {@link #marker()} 供调试脚本调用验证。</p>
 */
public final class HotswapProbe {
    private HotswapProbe() {
    }

    /**
     * 标记方法：hotswap 验证将其方法体重定义为返回其他值。
     *
     * @return 标记字符串
     */
    public static String marker() {
        return "original";
    }
}
