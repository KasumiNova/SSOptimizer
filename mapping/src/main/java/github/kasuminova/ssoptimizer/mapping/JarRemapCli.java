package github.kasuminova.ssoptimizer.mapping;

import java.nio.file.Path;

/**
 * JAR 重映射命令行入口。
 * <p>
 * 该入口主要供 Gradle 的 {@code JavaExec} 任务调用，用于生成开发期 named 依赖和
 * 发布期 reobf 产物。
 */
public final class JarRemapCli {
    private JarRemapCli() {
    }

    /**
     * 命令行入口。
     * <p>
     * 支持两种模式：
     * <ul>
        *     <li>{@code --platform=<linux|windows|auto>}（可选，未传则读取系统属性 / 自动探测）</li>
     *     <li>{@code batch <obf-to-named|named-to-obf> <outputDir> <inputJar...>}</li>
     *     <li>{@code single <obf-to-named|named-to-obf> <inputJar> <outputJar>}</li>
     * </ul>
     *
     * @param args 命令行参数
     * @throws Exception 若重映射失败
     */
    public static void main(String[] args) throws Exception {
        int offset = 0;
        MappingPlatform platform = MappingPlatform.current();
        if (args.length > 0 && args[0].startsWith("--platform=")) {
            platform = MappingPlatform.parse(args[0].substring("--platform=".length()));
            offset = 1;
        }

        if (args.length - offset < 4) {
            throw new IllegalArgumentException("用法: batch <direction> <outputDir> <inputJar...> | single <direction> <inputJar> <outputJar>");
        }

        String mode = args[offset];
        MappingDirection direction = parseDirection(args[offset + 1]);
        TinyV2MappingRepository repository = TinyV2MappingRepository.loadForPlatform(platform);
        JarRemapper remapper = new JarRemapper(repository, direction);

        if ("batch".equals(mode)) {
            Path outputDir = Path.of(args[offset + 2]);
            for (int i = offset + 3; i < args.length; i++) {
                Path inputJar = Path.of(args[i]);
                Path outputJar = outputDir.resolve(inputJar.getFileName().toString());
                remapper.remapJar(inputJar, outputJar);
                System.out.println("[JarRemapCli] Remapped " + inputJar + " -> " + outputJar);
            }
            return;
        }

        if ("single".equals(mode)) {
            Path inputJar = Path.of(args[offset + 2]);
            Path outputJar = Path.of(args[offset + 3]);
            remapper.remapJar(inputJar, outputJar);
            System.out.println("[JarRemapCli] Remapped " + inputJar + " -> " + outputJar);
            return;
        }

        throw new IllegalArgumentException("不支持的模式: " + mode);
    }

    private static MappingDirection parseDirection(String rawDirection) {
        return switch (rawDirection) {
            case "obf-to-named" -> MappingDirection.OBFUSCATED_TO_NAMED;
            case "named-to-obf" -> MappingDirection.NAMED_TO_OBFUSCATED;
            default -> throw new IllegalArgumentException("不支持的 remap 方向: " + rawDirection);
        };
    }
}
