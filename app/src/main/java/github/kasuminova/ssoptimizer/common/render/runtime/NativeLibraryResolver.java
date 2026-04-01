package github.kasuminova.ssoptimizer.common.render.runtime;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 原生库路径解析器。
 * <p>
 * 按优先级查找原生库：系统属性指定路径 → mod 目录下的平台相关子目录。
 */
public final class NativeLibraryResolver {
    private NativeLibraryResolver() {
    }

    public static Path resolve() {
        String override = System.getProperty("ssoptimizer.native.path");
        if (override != null && !override.isBlank()) {
            Path overridePath = Path.of(override).toAbsolutePath();
            return Files.isRegularFile(overridePath) ? overridePath : null;
        }

        Path modsDir = Path.of(System.getProperty("com.fs.starfarer.settings.paths.mods", "./mods"));
        Path candidate = modsDir.resolve("ssoptimizer")
                                .resolve("native")
                                .resolve(platformFolder())
                                .resolve(System.mapLibraryName("ssoptimizer"))
                                .toAbsolutePath();

        return Files.isRegularFile(candidate) ? candidate : null;
    }

    private static String platformFolder() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac")) {
            return "mac";
        }
        return "linux";
    }
}