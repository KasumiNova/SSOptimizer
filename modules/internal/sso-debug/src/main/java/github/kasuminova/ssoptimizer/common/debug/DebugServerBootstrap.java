package github.kasuminova.ssoptimizer.common.debug;

import org.apache.log4j.Logger;

/**
 * 调试服务装配入口。
 *
 * <p>由 coremod 插件 {@code SSOptimizerCorePlugin.onLoad} 调用：读取系统属性，
 * 启用时创建并启动 {@link DebugServer}。onLoad 早于一切游戏类加载，调试服务
 * 由此可覆盖游戏加载期的调试场景；脚本内对游戏实例的访问一律在执行时惰性解析。</p>
 */
public final class DebugServerBootstrap {
    private static final Logger LOGGER = Logger.getLogger(DebugServerBootstrap.class);

    private static volatile DebugServer server;

    private DebugServerBootstrap() {
    }

    /**
     * 按系统属性条件启动调试服务（幂等：重复调用返回既有实例）。
     *
     * @return 运行中的调试服务；未启用时返回 null
     */
    public static synchronized DebugServer startIfEnabled() {
        if (server != null) {
            return server;
        }
        final DebugConfig config = DebugConfig.fromSystemProperties();
        if (!config.enabled()) {
            return null;
        }
        final DebugRpcRouter router = DebugRpcRouter.createDefault();
        final ScriptRegistry scripts = new ScriptRegistry(config.outputDir());
        scripts.registerRpc(router);
        final DebugServerImpl impl = new DebugServerImpl(config, router);
        impl.start();
        server = impl;
        LOGGER.info("[SSOptimizer] Debug mode enabled");
        return impl;
    }
}
