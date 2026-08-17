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
    /** glad GL 函数指针是否就绪（仅 GL 加速路径关心；非 GL 功能如 PNG/字体不看此标志）。 */
    private static volatile boolean glReady;

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

    /**
     * GL 加速路径是否可用（native 库已加载且 glad 函数指针就绪）。
     * GL 相关的 helper 应以此为准；PNG 解码 / 字体栅格化等非 GL 功能只看 {@link #isLoaded()}。
     */
    public static boolean isGlReady() {
        return ensureLoaded() && glReady;
    }

    /** glad 一次性加载 GL 函数指针（不要求当前线程持有 GL context）。 */
    private static native boolean nativeInitGl();

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
            if (RenderThreadMode.isEnabled()) {
                // 分离模式：主线程自始至终没有 GL context，native（glad 直调）在
                // 主线程执行会崩。跳过 glad 函数指针加载，isGlReady() 恒 false，
                // 强制 SpriteRenderHelper/EngineBatch/字体等 native 加速路径全部落
                // Java 回退——回退路径里的 org.lwjgl 调用会被 ASM 重定向录制入队，
                // 语义正确（性能回退可接受，native 迁移留待后续轮次）。
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