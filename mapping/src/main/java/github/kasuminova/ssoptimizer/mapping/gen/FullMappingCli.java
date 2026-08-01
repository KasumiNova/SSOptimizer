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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 全量映射生成命令行入口。
 * <p>
 * 用法：{@code FullMappingCli <gameJarsRoot> <humanMappingsDir> <outputDir> <reportDir>}
 * <p>
 * 对 linux / windows 两个平台各执行一次：扫描 {@code gameJarsRoot/<platform>/} 下的
 * 混淆 jar（{@code *_obf.jar}，未混淆的 starfarer.api.jar 不生成占位映射），
 * 与人工表 {@code humanMappingsDir/ssoptimizer-<platform>.tiny} 及保持原名片段
 * {@code humanMappingsDir/ssoptimizer-identity.tiny}（可选，登记 app 编译期直接引用、
 * 必须保持原名的类）、scope 语义片段 {@code humanMappingsDir/scopes/<scope>-<platform>.tiny}
 * （可选，分层优先级：占位生成 < identity 片段 < scope 片段 < 人工运行期表；
 * scope 间混淆 key 或 named 类名冲突直接报错并指明两个 scope）
 * 合并后输出
 * {@code outputDir/<platform>/ssoptimizer-<platform>-full.tiny}，
 * 输出前由 {@link InheritedMemberPropagator} 沿继承链补齐子类侧成员别名
 * （混淆器会把继承成员引用挂在子类 owner 上），并产出漂移报告
 * {@code reportDir/mapping-drift-<platform>.txt} 与
 * 跨平台匹配报告 {@code reportDir/cross-platform-match.txt}。
 * 生成是确定性的：同一输入两次运行输出字节一致。
 */
public final class FullMappingCli {
    /** 占位成员名格式（{@code f_<指纹8>} / {@code m_<指纹8>}，冲突组追加 {@code _N}），与 {@code IntermediaryNameGenerator} 一致。 */
    private static final java.util.regex.Pattern PLACEHOLDER_MEMBER_NAME =
            java.util.regex.Pattern.compile("[fm]_[0-9a-f]{8}(_\\d+)?");

    /** 跨平台报告中未匹配类清单的最大行数。 */
    private static final int UNMATCHED_LIST_LIMIT = 200;

    private FullMappingCli() {
    }

    /**
     * 命令行入口。
     *
     * @param args 命令行参数
     * @throws Exception 若生成失败
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("用法: FullMappingCli <gameJarsRoot> <humanMappingsDir> <outputDir> <reportDir>");
        }
        Path gameJarsRoot = Path.of(args[0]);
        Path humanMappingsDir = Path.of(args[1]);
        Path outputDir = Path.of(args[2]);
        Path reportDir = Path.of(args[3]);
        Path scopesDir = humanMappingsDir.resolve("scopes");

        Map<MappingPlatform, List<ClassStructure>> classesByPlatform = new LinkedHashMap<>();
        TinyV2MappingRepository identityRepository = loadIdentityRepository(humanMappingsDir);
        List<MappingEntry> identityEntries = identityRepository == null ? List.of() : identityRepository.entries();
        java.util.Set<String> identityClasses = new java.util.HashSet<>();
        for (MappingEntry entry : identityEntries) {
            if (entry.isClass()) {
                identityClasses.add(entry.obfuscatedName());
            }
        }

        for (MappingPlatform platform : MappingPlatform.values()) {
            List<Path> jars = obfuscatedJars(gameJarsRoot.resolve(platform.id()));
            List<ClassStructure> classes = ClassStructure.scan(jars);
            classesByPlatform.put(platform, classes);

            List<ScopeFragments.ScopeFragment> scopeFragments = ScopeFragments.load(scopesDir, platform);
            List<String> scopeConflicts = ScopeFragments.crossScopeConflictLines(scopeFragments);
            if (!scopeConflicts.isEmpty()) {
                throw new MappingLookupException("scope 片段冲突 (" + platform.id() + "):\n - "
                        + String.join("\n - ", scopeConflicts));
            }
            List<MappingEntry> scopeEntries = ScopeFragments.mergedEntries(scopeFragments);

            TinyV2MappingRepository humanRepository = TinyV2MappingRepository.loadFromFile(
                    humanMappingsDir.resolve("ssoptimizer-" + platform.id() + ".tiny"));
            List<MappingEntry> generated = new IntermediaryNameGenerator().generate(classes, humanRepository, identityClasses);
            FullMappingMerger merger = new FullMappingMerger();
            List<MappingEntry> priorityEntries = new ArrayList<>(humanRepository.entries().size() + identityEntries.size());
            priorityEntries.addAll(humanRepository.entries());
            priorityEntries.addAll(identityEntries);
            List<MappingEntry> merged = merger.merge(priorityEntries, scopeEntries, generated);
            merged = InheritedMemberPropagator.propagate(merged, classes);

            Path outputFile = outputDir.resolve(platform.id()).resolve("ssoptimizer-" + platform.id() + "-full.tiny");
            Files.createDirectories(outputFile.getParent());
            Files.writeString(outputFile, merger.exportTiny(merged), StandardCharsets.UTF_8);

            List<String> drift = FullMappingMerger.driftLines(priorityEntries,
                    ClassStructure.scan(allJars(gameJarsRoot.resolve(platform.id()))));
            writeDriftReport(reportDir.resolve("mapping-drift-" + platform.id() + ".txt"), platform, drift);

            long totalMembers = merged.stream().filter(entry -> !entry.isClass()).count();
            long placeholderMembers = merged.stream()
                    .filter(entry -> !entry.isClass() && PLACEHOLDER_MEMBER_NAME.matcher(entry.namedName()).matches())
                    .count();
            double semanticCoverage = totalMembers == 0 ? 100.0
                    : (totalMembers - placeholderMembers) * 100.0 / totalMembers;

            System.out.println("[FullMappingCli] " + platform.id() + ": 扫描类 " + classes.size()
                    + ", 人工条目 " + humanRepository.entries().size()
                    + ", scope 片段 " + scopeFragments.size() + " 个 / " + scopeEntries.size() + " 条目"
                    + ", 占位条目 " + generated.size()
                    + ", 全量条目 " + merged.size()
                    + ", 漂移条目 " + drift.size()
                    + String.format(java.util.Locale.ROOT, ", 语义覆盖率 %.1f%%（成员 %d / 占位 %d）",
                            semanticCoverage, totalMembers, placeholderMembers));
        }

        writeCrossPlatformReport(reportDir.resolve("cross-platform-match.txt"), classesByPlatform);
    }

    private static TinyV2MappingRepository loadIdentityRepository(Path humanMappingsDir) {
        Path identityFile = humanMappingsDir.resolve("ssoptimizer-identity.tiny");
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
     * 列出平台目录下全部 jar（含未混淆的 starfarer.api.jar）。
     * <p>
     * 漂移报告需要与一致性测试相同的全量视野：人工表可能映射 api jar 中的未混淆类，
     * 只扫描混淆 jar 会把这些合法条目误报为漂移。
     */
    private static List<Path> allJars(Path platformJarDir) throws IOException {
        try (Stream<Path> files = Files.list(platformJarDir)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    private static void writeDriftReport(Path reportFile, MappingPlatform platform, List<String> drift) throws IOException {
        Files.createDirectories(reportFile.getParent());
        List<String> lines = new ArrayList<>();
        lines.add("# 人工映射漂移报告 - " + platform.id());
        lines.add("# 人工条目在 jar 当前结构中找不到对应类/成员（name+desc 精确匹配）时列出。");
        lines.add("漂移条目数: " + drift.size());
        lines.addAll(drift);
        Files.write(reportFile, lines, StandardCharsets.UTF_8);
    }

    private static void writeCrossPlatformReport(Path reportFile,
                                                 Map<MappingPlatform, List<ClassStructure>> classesByPlatform) throws IOException {
        Files.createDirectories(reportFile.getParent());

        Map<String, List<String>> linuxByHash = fingerprintIndex(classesByPlatform.get(MappingPlatform.LINUX));
        Map<String, List<String>> windowsByHash = fingerprintIndex(classesByPlatform.get(MappingPlatform.WINDOWS));

        int matched = 0;
        List<String> unmatchedLinux = new ArrayList<>();
        List<String> unmatchedWindows = new ArrayList<>();
        for (Map.Entry<String, List<String>> linuxGroup : linuxByHash.entrySet()) {
            List<String> windowsGroup = windowsByHash.get(linuxGroup.getKey());
            int pairCount = windowsGroup == null ? 0 : Math.min(linuxGroup.getValue().size(), windowsGroup.size());
            matched += pairCount;
            for (int i = pairCount; i < linuxGroup.getValue().size(); i++) {
                unmatchedLinux.add(linuxGroup.getValue().get(i));
            }
        }
        for (Map.Entry<String, List<String>> windowsGroup : windowsByHash.entrySet()) {
            List<String> linuxGroup = linuxByHash.get(windowsGroup.getKey());
            int pairCount = linuxGroup == null ? 0 : Math.min(windowsGroup.getValue().size(), linuxGroup.size());
            for (int i = pairCount; i < windowsGroup.getValue().size(); i++) {
                unmatchedWindows.add(windowsGroup.getValue().get(i));
            }
        }
        unmatchedLinux.sort(String::compareTo);
        unmatchedWindows.sort(String::compareTo);

        int linuxTotal = classesByPlatform.get(MappingPlatform.LINUX).size();
        int windowsTotal = classesByPlatform.get(MappingPlatform.WINDOWS).size();

        List<String> lines = new ArrayList<>();
        lines.add("# 跨平台类结构指纹匹配报告");
        lines.add("# 指纹精确匹配的类在双平台间自动对齐（占位名一致）；不匹配项（平台分支/条件编译）只报告。");
        lines.add("linux 类数: " + linuxTotal);
        lines.add("windows 类数: " + windowsTotal);
        lines.add("指纹匹配类数: " + matched);
        lines.add(String.format(java.util.Locale.ROOT, "linux 匹配率: %.2f%%", matched * 100.0 / linuxTotal));
        lines.add(String.format(java.util.Locale.ROOT, "windows 匹配率: %.2f%%", matched * 100.0 / windowsTotal));
        lines.add("未匹配 linux 类数: " + unmatchedLinux.size());
        appendCapped(lines, unmatchedLinux);
        lines.add("未匹配 windows 类数: " + unmatchedWindows.size());
        appendCapped(lines, unmatchedWindows);
        Files.write(reportFile, lines, StandardCharsets.UTF_8);

        System.out.println("[FullMappingCli] 跨平台指纹匹配: " + matched + " / linux " + linuxTotal
                + " / windows " + windowsTotal);
    }

    private static Map<String, List<String>> fingerprintIndex(List<ClassStructure> classes) {
        Map<String, List<String>> byHash = new LinkedHashMap<>();
        for (ClassStructure classStructure : classes) {
            byHash.computeIfAbsent(StructuralFingerprint.ofClass(classStructure), key -> new ArrayList<>())
                    .add(classStructure.name());
        }
        return byHash;
    }

    private static void appendCapped(List<String> lines, List<String> unmatched) {
        int limit = Math.min(unmatched.size(), UNMATCHED_LIST_LIMIT);
        for (int i = 0; i < limit; i++) {
            lines.add("  " + unmatched.get(i));
        }
        if (unmatched.size() > UNMATCHED_LIST_LIMIT) {
            lines.add("  ... 其余 " + (unmatched.size() - UNMATCHED_LIST_LIMIT) + " 条省略");
        }
    }
}
