package github.kasuminova.ssoptimizer.mapping.gen;

import github.kasuminova.ssoptimizer.mapping.MappingEntry;
import github.kasuminova.ssoptimizer.mapping.MappingLookupException;
import github.kasuminova.ssoptimizer.mapping.MappingPlatform;
import github.kasuminova.ssoptimizer.mapping.TinyV2MappingRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * scope 语义片段校验命令行入口（Gradle {@code mergeScopeFragments} 任务）。
 * <p>
 * 用法：{@code ScopeFragmentCli <gameJarsRoot> <mappingsDir> <reportFile>}
 * <p>
 * 对 linux / windows 两个平台各执行一次：加载 {@code mappingsDir/scopes/} 下全部
 * scope 片段，校验：
 * <ul>
 *     <li>可解析（Tiny v2 格式，解析失败直接报错）；</li>
 *     <li>跨 scope 混淆 key / named 类名唯一性（冲突指明两个 scope）；</li>
 *     <li>每条目的 jar 一致性（以入库游戏 jar 为唯一事实源，成员按 name+desc 精确匹配，
 *     描述符 named→obf 换算上下文取合并后全量表的类条目，覆盖引用其他 scope /
 *     人工表 named 类的情形）；</li>
 * </ul>
 * 并输出汇总报告 {@code reportFile}：每 scope 条目数、覆盖率（片段覆盖类数 /
 * 片段涉及的包在混淆 jar 中的总类数）与全部冲突/不一致项。
 * 该任务只校验并报告，不改动任何映射表；全量表由 {@code generateFullMappings} 自动纳入片段。
 * 存在冲突或不一致项时以非零退出码失败。
 */
public final class ScopeFragmentCli {
    private ScopeFragmentCli() {
    }

    /**
     * 命令行入口。
     *
     * @param args 命令行参数
     * @throws Exception 若校验失败
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("用法: ScopeFragmentCli <gameJarsRoot> <mappingsDir> <reportFile>");
        }
        Path gameJarsRoot = Path.of(args[0]);
        Path mappingsDir = Path.of(args[1]);
        Path reportFile = Path.of(args[2]);
        Path scopesDir = mappingsDir.resolve("scopes");

        List<String> report = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        report.add("# scope 片段校验汇总报告");
        report.add("# 由 mergeScopeFragments 生成；只校验并报告，不改动任何映射表。");

        TinyV2MappingRepository identityRepository = loadIdentityRepository(mappingsDir);
        List<MappingEntry> identityEntries = identityRepository == null ? List.of() : identityRepository.entries();
        Set<String> identityClasses = new LinkedHashSet<>();
        for (MappingEntry entry : identityEntries) {
            if (entry.isClass()) {
                identityClasses.add(entry.obfuscatedName());
            }
        }

        int totalFragments = 0;
        for (MappingPlatform platform : MappingPlatform.values()) {
            List<ScopeFragments.ScopeFragment> fragments = ScopeFragments.load(scopesDir, platform);
            totalFragments += fragments.size();
            report.add("");
            report.add("## 平台 " + platform.id() + "（scope 片段 " + fragments.size() + " 个）");

            List<String> conflicts = ScopeFragments.crossScopeConflictLines(fragments);
            for (String conflict : conflicts) {
                report.add("冲突: " + conflict);
                problems.add("[" + platform.id() + "] " + conflict);
            }
            if (fragments.isEmpty()) {
                continue;
            }

            List<ClassStructure> obfuscatedClasses = ClassStructure.scan(obfuscatedJars(gameJarsRoot.resolve(platform.id())));
            List<ClassStructure> allClasses = ClassStructure.scan(allJars(gameJarsRoot.resolve(platform.id())));

            // 合并出全量表类视图，作为片段 jar 校验的 named→obf 描述符换算上下文。
            TinyV2MappingRepository humanRepository = TinyV2MappingRepository.loadFromFile(
                    mappingsDir.resolve("ssoptimizer-" + platform.id() + ".tiny"));
            List<MappingEntry> generated = new IntermediaryNameGenerator()
                    .generate(obfuscatedClasses, humanRepository, identityClasses);
            List<MappingEntry> priorityEntries = new ArrayList<>(humanRepository.entries().size() + identityEntries.size());
            priorityEntries.addAll(humanRepository.entries());
            priorityEntries.addAll(identityEntries);
            List<MappingEntry> merged = new FullMappingMerger()
                    .merge(priorityEntries, ScopeFragments.mergedEntries(fragments), generated);
            Map<String, String> namedToObfuscatedContext = new HashMap<>();
            for (MappingEntry entry : merged) {
                if (entry.isClass()) {
                    namedToObfuscatedContext.put(entry.namedName(), entry.obfuscatedName());
                }
            }

            for (ScopeFragments.ScopeFragment fragment : fragments) {
                int entryCount = fragment.entries().size();
                int mappedClasses = 0;
                Set<String> scopePackages = new LinkedHashSet<>();
                for (MappingEntry entry : fragment.entries()) {
                    if (entry.isClass()) {
                        mappedClasses++;
                        scopePackages.add(packageOf(entry.obfuscatedName()));
                    }
                }
                int scopeTotalClasses = 0;
                for (ClassStructure classStructure : obfuscatedClasses) {
                    if (scopePackages.contains(packageOf(classStructure.name()))) {
                        scopeTotalClasses++;
                    }
                }

                List<String> drift = FullMappingMerger.driftLines(fragment.entries(), allClasses, namedToObfuscatedContext);

                double coverage = scopeTotalClasses == 0 ? 0.0 : mappedClasses * 100.0 / scopeTotalClasses;
                report.add("- scope '" + fragment.scope() + "': 条目 " + entryCount
                        + ", 覆盖类 " + mappedClasses + " / " + scopeTotalClasses
                        + String.format(java.util.Locale.ROOT, "（覆盖率 %.1f%%）", coverage)
                        + ", jar 不一致 " + drift.size() + " 项");
                for (String driftLine : drift) {
                    report.add("    不一致: " + driftLine);
                    problems.add("[" + platform.id() + "][scope '" + fragment.scope() + "'] " + driftLine);
                }
            }
        }

        report.add("");
        report.add("校验结论: " + (problems.isEmpty() ? "通过" : "失败，共 " + problems.size() + " 项问题"));
        Files.createDirectories(reportFile.getParent());
        Files.write(reportFile, report, StandardCharsets.UTF_8);

        System.out.println("[ScopeFragmentCli] 校验 scope 片段 " + totalFragments + " 个，问题 "
                + problems.size() + " 项，报告: " + reportFile);
        if (!problems.isEmpty()) {
            throw new MappingLookupException("scope 片段校验失败（" + problems.size() + " 项），详见报告: " + reportFile);
        }
    }

    private static TinyV2MappingRepository loadIdentityRepository(Path mappingsDir) {
        Path identityFile = mappingsDir.resolve("ssoptimizer-identity.tiny");
        if (!Files.isRegularFile(identityFile)) {
            return null;
        }
        return TinyV2MappingRepository.loadFromFile(identityFile);
    }

    private static List<Path> obfuscatedJars(Path platformJarDir) throws IOException {
        try (Stream<Path> files = Files.list(platformJarDir)) {
            return files.filter(path -> path.getFileName().toString().endsWith("_obf.jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    /**
     * 列出平台目录下全部 jar（含未混淆的 starfarer.api.jar），与漂移报告同一视野。
     */
    private static List<Path> allJars(Path platformJarDir) throws IOException {
        try (Stream<Path> files = Files.list(platformJarDir)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    private static String packageOf(String internalName) {
        int lastSlash = internalName.lastIndexOf('/');
        return lastSlash < 0 ? "" : internalName.substring(0, lastSlash);
    }
}
