package github.kasuminova.ssoptimizer.bootstrap;

import org.apache.log4j.Logger;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨模块服务注册表：api 接口与实现之间的显式接线点。
 * <p>
 * 动机：模块化拆离后功能模块之间禁止直接依赖，跨域行为调用经 api 接口；
 * 实现类由 coremod 装配期（{@code SSOptimizerCorePlugin.onLoad}）统一注册，
 * 消费方经本注册表获取。刻意不用 SPI/反射——注册关系在装配代码中显式可见，
 * 缺失实现在首次使用时即以带日志的异常暴露，不做静默降级。
 * <p>
 * 线程安全：注册发生在启动期单线程，消费期并发只读；内部以 CHM 保证发布安全。
 */
public final class ServiceRegistry {

    private static final Logger LOGGER = Logger.getLogger(ServiceRegistry.class);

    private static final ConcurrentHashMap<Class<?>, Object> SERVICES = new ConcurrentHashMap<>();

    private ServiceRegistry() {
    }

    /**
     * 注册服务实现。重复注册视为装配错误，直接抛异常。
     *
     * @param type api 接口类型
     * @param impl 实现实例
     * @param <T>  服务类型
     */
    public static <T> void register(final Class<T> type, final T impl) {
        final Object prev = SERVICES.putIfAbsent(type, impl);
        if (prev != null) {
            throw new IllegalStateException(
                    "[SSOptimizer] 服务重复注册：" + type.getName() + "（已有 " + prev.getClass().getName()
                            + "，新注册 " + impl.getClass().getName() + "）");
        }
        LOGGER.info("[SSOptimizer] 服务已注册：" + type.getSimpleName() + " -> " + impl.getClass().getName());
    }

    /**
     * 获取已注册的服务实现；未注册视为装配错误，抛带日志的异常。
     *
     * @param type api 接口类型
     * @param <T>  服务类型
     * @return 已注册的实现
     */
    public static <T> T require(final Class<T> type) {
        final Object impl = SERVICES.get(type);
        if (impl == null) {
            final IllegalStateException failure = new IllegalStateException(
                    "[SSOptimizer] 服务未注册：" + type.getName() + "（coremod 装配期应完成注册）");
            LOGGER.error(failure.getMessage());
            throw failure;
        }
        return type.cast(impl);
    }

    /**
     * 查询可选服务（未注册返回 null）。仅用于语义上允许缺省的钩子
     * （如帧捕获调试钩子），调用点必须显式判空。
     *
     * @param type api 接口类型
     * @param <T>  服务类型
     * @return 已注册的实现，未注册为 null
     */
    public static <T> T getOrNull(final Class<T> type) {
        return type.cast(SERVICES.get(type));
    }
}
