package github.kasuminova.ssoptimizer.common.debug;

import org.apache.log4j.Logger;

public final class DebugServerBootstrap {
    private static final Logger LOGGER = Logger.getLogger(DebugServerBootstrap.class);

    private static volatile DebugServer server;

    private DebugServerBootstrap() {
    }

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
