package github.kasuminova.ssoptimizer.common.render.runtime;

import org.apache.log4j.Logger;

import java.nio.file.Path;

/**
 * 原生运行时加载器。
 * <p>
 * 负责在 JVM 进程中加载 SSOptimizer 的 C++ 原生库（{@code libssoptimizer.so / ssoptimizer.dll}），
 * 保证全局只加载一次，并提供加载状态查询。
 */
public final class NativeRuntime {
    private static final Logger LOGGER = Logger.getLogger(NativeRuntime.class);

    private static volatile boolean loadAttempted;
    private static volatile boolean loaded;

    private NativeRuntime() {
    }

    public static boolean isLoaded() {
        ensureLoaded();
        return loaded;
    }

    public static synchronized boolean ensureLoaded() {
        if (loadAttempted) {
            return loaded;
        }

        loadAttempted = true;
        try {
            Path nativePath = NativeLibraryResolver.resolve();
            if (nativePath == null) {
                LOGGER.info("[SSOptimizer] Native library not found; using Java fallbacks.");
                return false;
            }

            System.load(nativePath.toString());
            loaded = true;
            LOGGER.info("[SSOptimizer] Native library loaded: " + nativePath);
            return true;
        } catch (Throwable t) {
            LOGGER.warn("[SSOptimizer] Failed to load native library: " + t.getMessage());
            return false;
        }
    }
}