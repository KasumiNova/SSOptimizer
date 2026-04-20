package github.kasuminova.ssoptimizer.common.render.runtime;

import org.apache.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 原生运行时加载器。
 * <p>
 * 负责在 JVM 进程中加载 SSOptimizer 的 C++ 原生库（{@code libssoptimizer.so / ssoptimizer.dll}），
 * 保证全局只加载一次，并提供加载状态查询。
 * <p>
 * Windows 下在加载主 DLL 前，会预加载同目录下的依赖 DLL（libpng16、freetype 及其传递依赖），
 * 因为 {@code System.load()} 内部使用 LoadLibrary，其隐式依赖搜索路径不包含被加载 DLL 的所在目录。
 * 预加载使依赖 DLL 进入进程地址空间，后续主 DLL 的隐式引用会命中已加载的模块。
 */
public final class NativeRuntime {
    private static final Logger LOGGER = Logger.getLogger(NativeRuntime.class);

    private static volatile boolean loadAttempted;
    private static volatile boolean loaded;

    /**
     * Windows 下需要预加载的依赖 DLL 列表（按依赖顺序排列：被依赖者在前）。
     * 缺少某个 DLL 不影响加载流程，只会跳过。
     */
    private static final String[] WINDOWS_DEPENDENCY_DLLS = {
        "zlib1.dll",
        "brotlicommon.dll",
        "brotlidec.dll",
        "bz2.dll",
        "libpng16.dll",
        "freetype.dll"
    };

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

            preloadWindowsDependencies(nativePath.getParent());
            System.load(nativePath.toString());
            loaded = true;
            LOGGER.info("[SSOptimizer] Native library loaded: " + nativePath);
            return true;
        } catch (Throwable t) {
            LOGGER.warn("[SSOptimizer] Failed to load native library: " + t.getMessage());
            return false;
        }
    }

    /**
     * Windows 平台下，预加载主 DLL 同目录的依赖库到进程地址空间。
     * 这样当 OS 加载 ssoptimizer.dll 时，其 import table 中引用的 DLL 已经在内存中，无需再搜索文件系统。
     *
     * @param nativeDir 主原生库所在目录
     */
    private static void preloadWindowsDependencies(Path nativeDir) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return;
        }
        for (String dllName : WINDOWS_DEPENDENCY_DLLS) {
            Path dllPath = nativeDir.resolve(dllName);
            if (Files.isRegularFile(dllPath)) {
                try {
                    System.load(dllPath.toAbsolutePath().toString());
                    LOGGER.debug("[SSOptimizer] Pre-loaded dependency: " + dllName);
                } catch (Throwable t) {
                    LOGGER.debug("[SSOptimizer] Skipped dependency " + dllName + ": " + t.getMessage());
                }
            }
        }
    }
}