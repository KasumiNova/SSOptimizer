package github.kasuminova.ssoptimizer.mapping.gen;

import github.kasuminova.ssoptimizer.mapping.MappingEntry;
import github.kasuminova.ssoptimizer.mapping.MappingPlatform;
import github.kasuminova.ssoptimizer.mapping.TinyV2MappingRepository;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 映射使用度扫描命令行入口。
 * <p>
 * 用法：{@code UsageScanCli <humanMappingsDir> <generatedMappingsDir> <reportDir> <input...>}
 * <p>
 * 对 linux / windows 两个平台各执行一次：加载构建期全量表
 * {@code generatedMappingsDir/<platform>/ssoptimizer-<platform>-full.tiny}，
 * 扫描 {@code input}（消费侧 jar 或 class 目录，可多个）中对游戏类/成员的静态引用，
 * 按 语义命名 / 保持原名 / 提升名 / 占位名 分类，输出
 * {@code reportDir/mapping-usage-<platform>.txt}。
 * 分类语义与盲区见 {@link MappingUsageScanner} 类文档。
 */
public final class UsageScanCli {
    private UsageScanCli() {
    }

    /**
     * 命令行入口。
     *
     * @param args 命令行参数
     * @throws Exception 若扫描失败
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            throw new IllegalArgumentException(
                    "用法: UsageScanCli <humanMappingsDir> <generatedMappingsDir> <reportDir> <input...>");
        }
        Path humanMappingsDir = Path.of(args[0]);
        Path generatedMappingsDir = Path.of(args[1]);
        Path reportDir = Path.of(args[2]);
        List<Path> inputs = new ArrayList<>();
        for (int i = 3; i < args.length; i++) {
            inputs.add(Path.of(args[i]));
        }

        TinyV2MappingRepository identityRepository = loadIdentityRepository(humanMappingsDir);
        List<MappingEntry> identityEntries = identityRepository == null ? List.of() : identityRepository.entries();
        Set<String> identityClasses = new HashSet<>();
        for (MappingEntry entry : identityEntries) {
            if (entry.isClass()) {
                identityClasses.add(entry.namedName());
            }
        }

        for (MappingPlatform platform : MappingPlatform.values()) {
            Path fullMappingFile = generatedMappingsDir
                    .resolve(platform.id())
                    .resolve("ssoptimizer-" + platform.id() + "-full.tiny");
            TinyV2MappingRepository fullRepository = TinyV2MappingRepository.loadFromFile(fullMappingFile);

            List<MappingEntry> semanticEntries = new ArrayList<>();
            semanticEntries.addAll(TinyV2MappingRepository.loadFromFile(
                    humanMappingsDir.resolve("ssoptimizer-" + platform.id() + ".tiny")).entries());
            semanticEntries.addAll(ScopeFragments.mergedEntries(
                    ScopeFragments.load(humanMappingsDir.resolve("scopes"), platform)));

            MappingUsageScanner.UsageScanResult result = new MappingUsageScanner()
                    .scan(inputs, fullRepository, semanticEntries, identityClasses);

            Files.createDirectories(reportDir);
            Path reportFile = reportDir.resolve("mapping-usage-" + platform.id() + ".txt");
            Files.write(reportFile, result.reportLines(), StandardCharsets.UTF_8);
            System.out.println("[UsageScanCli] " + platform.id()
                    + ": 被引用类 " + result.referencedClasses().size()
                    + ", 占位名引用 " + result.violations().size()
                    + ", 提升名引用 " + result.promotedReferences().size()
                    + " -> " + reportFile);
        }
    }

    private static TinyV2MappingRepository loadIdentityRepository(Path humanMappingsDir) {
        Path identityFile = humanMappingsDir.resolve("ssoptimizer-identity.tiny");
        if (!Files.isRegularFile(identityFile)) {
            return null;
        }
        return TinyV2MappingRepository.loadFromFile(identityFile);
    }
}
