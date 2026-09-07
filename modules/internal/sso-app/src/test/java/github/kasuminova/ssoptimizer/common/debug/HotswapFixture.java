package github.kasuminova.ssoptimizer.common.debug;

/**
 * hotswap 测试夹具：被重定义的目标类。
 *
 * <p>只被 {@code HotswapSupportTest} 引用——重定义对测试 JVM 永久生效，
 * 独立夹具避免污染其他测试。</p>
 */
public class HotswapFixture {
    /**
     * 标记方法：hotswap 测试将其方法体重定义为返回 "hotswapped"。
     *
     * @return 标记字符串
     */
    public String marker() {
        return "original";
    }
}
