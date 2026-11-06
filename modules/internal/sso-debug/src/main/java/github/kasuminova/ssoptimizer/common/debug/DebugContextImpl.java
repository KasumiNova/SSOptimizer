package github.kasuminova.ssoptimizer.common.debug;

import github.kasuminova.ssoptimizer.api.debug.DebugContext;
import github.kasuminova.ssoptimizer.bootstrap.ServiceRegistry;
import org.apache.log4j.Logger;

public final class DebugContextImpl implements DebugContext {
    private static final Logger SCRIPT_LOGGER = Logger.getLogger("SSOptimizer.DebugScript");

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
