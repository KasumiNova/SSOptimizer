package github.kasuminova.ssoptimizer.common.render.runtime;

import org.apache.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 按模块加载原生库并缓存成功或失败结果，加载失败时返回 false 并记录日志。
 * Windows 加载前预载同目录依赖，因为 {@code System.load()} 的隐式依赖搜索不包含目标 DLL 所在目录。
 */
public final class NativeRuntime {
    private static final Logger LOGGER = Logger.getLogger(NativeRuntime.class);

    private static final String MAIN_MODULE = NativeLibraryResolver.MAIN_MODULE;
    private static final int NATIVE_ABI_VERSION = 1;

    private static final Map<String, Boolean> MODULE_LOAD_STATE = new HashMap<>();

    private static volatile boolean glReady;

    /** 渲染线程 glad 状态独立于主线程状态，0 表示未探测，1 表示就绪，-1 表示不可用。 */
    private static volatile int queueGlReadyState;

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
        return ensureLoaded();
    }

    public static boolean isGlReady() {
        return ensureLoaded() && glReady;
    }

    private static native boolean nativeInitGl();

    private static native int nativeRenderAbiVersion();

    private static native int nativeLoadingAbiVersion();

    private static native int nativeTexcompressAbiVersion();

    private static native int nativeFontAbiVersion();

    private static native int nativeImeAbiVersion();

    /** 仅限持有真实 GL context 的渲染线程调用；初始化结果在进程内缓存。 */
    public static boolean ensureQueueGlReady() {
        final int state = queueGlReadyState;
        if (state != 0) {
            return state > 0;
        }
        return initQueueGl();
    }

    /** 只读查询，不触发初始化；未就绪时录制侧使用 Java 命令路径。 */
    public static boolean isQueueGlReady() {
        return queueGlReadyState > 0;
    }

    private static synchronized boolean initQueueGl() {
        if (queueGlReadyState != 0) {
            return queueGlReadyState > 0;
        }
        final boolean ready;
        try {
            ready = ensureLoaded() && nativeInitGl();
        } catch (UnsatisfiedLinkError e) {
            queueGlReadyState = -1;
            LOGGER.error("[SSOptimizer] 渲染线程 native GL 初始化符号链接失败，回退 LWJGL/Java 录制路径", e);
            return false;
        }
        queueGlReadyState = ready ? 1 : -1;
        if (!ready) {
            LOGGER.warn("[SSOptimizer] 渲染线程 glad 初始化失败，native GL 直执通道"
                    + "（sync ops / NativeRenderCommand）不可用，回退 LWJGL/Java 录制路径");
        }
        return ready;
    }

    public static synchronized boolean ensureLoaded() {
        return loadModule(MAIN_MODULE);
    }

    public static synchronized boolean loadModule(final String moduleName) {
        final Boolean cached = MODULE_LOAD_STATE.get(moduleName);
        if (cached != null) {
            return cached;
        }

        final boolean result = attemptLoad(moduleName);
        MODULE_LOAD_STATE.put(moduleName, result);
        return result;
    }

    private static boolean attemptLoad(final String moduleName) {
        try {
            final Path nativePath = NativeLibraryResolver.resolve(moduleName);
            if (nativePath == null) {
                LOGGER.info("[SSOptimizer] Native module '" + moduleName + "' not found; using Java fallbacks.");
                return false;
            }

            preloadWindowsDependencies(nativePath.getParent());
            System.load(nativePath.toString());
            if (!hasCompatibleAbi(moduleName)) {
                return false;
            }
            LOGGER.info("[SSOptimizer] Native library loaded: " + nativePath);
            if (MAIN_MODULE.equals(moduleName)) {
                if (RenderThreadMode.isEnabled()) {
                    // 主线程没有 GL context，分离模式下仅初始化渲染线程使用的 glad 通道。
                    glReady = false;
                    LOGGER.info("[SSOptimizer] 渲染线程分离模式：跳过 glad 初始化，"
                            + "native GL 加速路径（SpriteBatch/SpriteRenderHelper/EngineBatch/"
                            + "TexturedStrip/BitmapFont 等）全部降级为 Java 录制路径");
                } else {
                    glReady = nativeInitGl();
                    if (!glReady) {
                        LOGGER.warn("[SSOptimizer] glad GL 函数指针加载失败，GL 加速路径回退 Java 实现");
                    }
                }
            }
            return true;
        } catch (UnsatisfiedLinkError e) {
            LOGGER.error("[SSOptimizer] Failed to link native module '" + moduleName + "'", e);
            return false;
        } catch (Throwable t) {
            LOGGER.warn("[SSOptimizer] Failed to load native module '" + moduleName + "': " + t.getMessage());
            return false;
        }
    }

    private static boolean hasCompatibleAbi(final String moduleName) {
        final int actualVersion = switch (moduleName) {
            case "render" -> nativeRenderAbiVersion();
            case "loading" -> nativeLoadingAbiVersion();
            case "texcompress" -> nativeTexcompressAbiVersion();
            case "font" -> nativeFontAbiVersion();
            case "ime" -> nativeImeAbiVersion();
            default -> throw new IllegalArgumentException("Unknown native module: " + moduleName);
        };
        if (actualVersion == NATIVE_ABI_VERSION) {
            return true;
        }
        LOGGER.error("[SSOptimizer] Native module '" + moduleName + "' ABI mismatch: expected="
                + NATIVE_ABI_VERSION + ", actual=" + actualVersion + "; module disabled.");
        return false;
    }

    private static void preloadWindowsDependencies(final Path nativeDir) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return;
        }
        for (final String dllName : WINDOWS_DEPENDENCY_DLLS) {
            final Path dllPath = nativeDir.resolve(dllName);
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
