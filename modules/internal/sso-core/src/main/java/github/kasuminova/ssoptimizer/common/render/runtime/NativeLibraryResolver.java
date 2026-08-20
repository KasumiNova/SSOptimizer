package github.kasuminova.ssoptimizer.common.render.runtime;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 原生库路径解析器。
 * <p>
 * 自阶段 4 起 native 按功能域拆分为多个共享库（libssoptimizer_render.so / libssoptimizer_loading.so /
 * libssoptimizer_font.so / libssoptimizer_ime.so），本类按模块名解析各自路径。
 * <p>
 * 查找优先级：
 * <ol>
 *   <li>系统属性 {@code ssoptimizer.native.path.<moduleName>} 指定路径（按模块覆盖）；</li>
 *   <li>系统属性 {@code ssoptimizer.native.path} 指定路径（仅对主模块 render 生效，历史语义）；</li>
 *   <li>mod 目录下平台相关子目录的约定文件名。</li>
 * </ol>
 */
public final class NativeLibraryResolver {
    /** 主模块名：包含 glad 初始化与全部 GL 渲染器。 */
    public static final String MAIN_MODULE = "render";

    private NativeLibraryResolver() {
    }

    /** 主模块（render）路径解析，兼容历史调用方。 */
    public static Path resolve() {
        return resolve(MAIN_MODULE);
    }

    public static Path resolve(final String moduleName) {
        final String moduleOverride = System.getProperty("ssoptimizer.native.path." + moduleName);
        if (moduleOverride != null && !moduleOverride.isBlank()) {
            final Path overridePath = Path.of(moduleOverride).toAbsolutePath();
            return Files.isRegularFile(overridePath) ? overridePath : null;
        }
        if (MAIN_MODULE.equals(moduleName)) {
            final String override = System.getProperty("ssoptimizer.native.path");
            if (override != null && !override.isBlank()) {
                final Path overridePath = Path.of(override).toAbsolutePath();
                return Files.isRegularFile(overridePath) ? overridePath : null;
            }
        }

        final Path modsDir = Path.of(System.getProperty("com.fs.starfarer.settings.paths.mods", "./mods"));
        final Path candidate = modsDir.resolve("ssoptimizer")
                                      .resolve("native")
                                      .resolve(platformFolder())
                                      .resolve(System.mapLibraryName("ssoptimizer_" + moduleName))
                                      .toAbsolutePath();

        return Files.isRegularFile(candidate) ? candidate : null;
    }

    private static String platformFolder() {
        final String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac")) {
            return "mac";
        }
        return "linux";
    }
}
