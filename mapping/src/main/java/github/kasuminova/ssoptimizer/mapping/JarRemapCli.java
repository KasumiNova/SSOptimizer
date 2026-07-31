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
     *     <li>{@code --mapping=<path>}（可选，从指定文件加载映射；未传则走 classpath 资源，运行期行为不变）</li>
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
        Path mappingFile = null;
        while (args.length > offset && args[offset].startsWith("--")) {
            if (args[offset].startsWith("--platform=")) {
                platform = MappingPlatform.parse(args[offset].substring("--platform=".length()));
            } else if (args[offset].startsWith("--mapping=")) {
                mappingFile = Path.of(args[offset].substring("--mapping=".length()));
            } else {
                throw new IllegalArgumentException("不支持的参数: " + args[offset]);
            }
            offset++;
        }

        if (args.length - offset < 4) {
            throw new IllegalArgumentException("用法: batch <direction> <outputDir> <inputJar...> | single <direction> <inputJar> <outputJar>");
        }

        String mode = args[offset];
        MappingDirection direction = parseDirection(args[offset + 1]);
        TinyV2MappingRepository repository = mappingFile == null
                ? TinyV2MappingRepository.loadForPlatform(platform)
                : TinyV2MappingRepository.loadFromFile(mappingFile);
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
