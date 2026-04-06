package github.kasuminova.ssoptimizer.mapping;

import java.util.Locale;

/**
 * 映射资源所属的平台枚举。
 * <p>
 * 负责把运行时 / 构建期的“当前平台”解析为稳定的 mapping 资源路径，
 * 让 {@code mapping} 模块承担 Linux / Windows 的混淆差异，
 * 而上层 {@code app} 模块始终只面向统一的 named 命名空间编程。
 */
public enum MappingPlatform {
    /** Linux 平台映射。 */
    LINUX("linux"),
    /** Windows 平台映射。 */
    WINDOWS("windows");

    /**
     * 选择默认 mapping 平台的系统属性。
     * <p>
     * 支持值：{@code linux}、{@code windows}、{@code auto}。
     */
    public static final String PROPERTY = "ssoptimizer.mapping.platform";

    private final String id;

    MappingPlatform(final String id) {
        this.id = id;
    }

    /**
     * 返回平台标识字符串。
     *
     * @return 平台标识
     */
    public String id() {
        return id;
    }

    /**
     * 返回该平台对应的 Tiny v2 资源路径。
     *
     * @return classpath 资源路径
     */
    public String resourcePath() {
        return "/mappings/ssoptimizer-" + id + ".tiny";
    }

    /**
     * 解析当前应使用的 mapping 平台。
     * <p>
     * 优先级：显式系统属性 → Starsector 平台标记属性 → 宿主 OS 名称。
     *
     * @return 当前平台
     */
    public static MappingPlatform current() {
        final String configured = System.getProperty(PROPERTY);
        if (configured != null && !configured.isBlank() && !"auto".equalsIgnoreCase(configured.trim())) {
            return parse(configured);
        }
        if (Boolean.getBoolean("com.fs.starfarer.settings.windows")) {
            return WINDOWS;
        }
        if (Boolean.getBoolean("com.fs.starfarer.settings.linux")) {
            return LINUX;
        }
        return detectFromOsName(System.getProperty("os.name", ""));
    }

    /**
     * 解析外部传入的平台字符串。
     *
     * @param raw 原始平台字符串
     * @return 对应平台枚举
     */
    public static MappingPlatform parse(final String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("mapping 平台不能为空");
        }

        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "linux" -> LINUX;
            case "windows", "win" -> WINDOWS;
            case "auto" -> current();
            default -> throw new IllegalArgumentException("不支持的 mapping 平台: " + raw);
        };
    }

    private static MappingPlatform detectFromOsName(final String osName) {
        final String normalized = osName != null ? osName.toLowerCase(Locale.ROOT) : "";
        return normalized.contains("win") ? WINDOWS : LINUX;
    }
}
