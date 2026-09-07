package github.kasuminova.ssoptimizer.common.debug;

import github.kasuminova.ssoptimizer.api.debug.DebugContext;
import github.kasuminova.ssoptimizer.bootstrap.ServiceRegistry;
import org.apache.log4j.Logger;

/**
 * {@link DebugContext} 的生产实现。
 *
 * <p>游戏类加载器取 sso-debug 模块自身的加载器（生产环境全部 coremod 类
 * 由 LaunchClassLoader 加载，与游戏/模组类同域可见）；日志走 log4j；
 * 跨域服务经 core 的 ServiceRegistry 解析。</p>
 */
public final class DebugContextImpl implements DebugContext {
    private static final Logger SCRIPT_LOGGER = Logger.getLogger("SSOptimizer.DebugScript");

    /** 全局单例：上下文无会话状态，全部脚本共享一个实例。 */
    public static final DebugContextImpl INSTANCE = new DebugContextImpl();

    private DebugContextImpl() {
    }

    @Override
    public ClassLoader gameClassLoader() {
        return DebugContextImpl.class.getClassLoader();
    }

    @Override
    public void log(final String message) {
        SCRIPT_LOGGER.info(message);
    }

    @Override
    public <T> T service(final Class<T> api) {
        return ServiceRegistry.getOrNull(api);
    }
}
