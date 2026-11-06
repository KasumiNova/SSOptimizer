package github.kasuminova.ssoptimizer.common.render.runtime;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 按模块解析原生库路径。
 * 优先使用模块专属系统属性，其次使用 render 模块的通用路径属性，最后查找 mod 目录的平台子目录。
 */
public final class NativeLibraryResolver {
    public static final String MAIN_MODULE = "render";

    private NativeLibraryResolver() {
    }

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
