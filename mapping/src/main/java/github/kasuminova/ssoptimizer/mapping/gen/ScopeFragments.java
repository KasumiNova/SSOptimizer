package github.kasuminova.ssoptimizer.mapping.gen;

import github.kasuminova.ssoptimizer.mapping.MappingEntry;
import github.kasuminova.ssoptimizer.mapping.MappingLookupException;
import github.kasuminova.ssoptimizer.mapping.MappingPlatform;
import github.kasuminova.ssoptimizer.mapping.TinyV2MappingRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * scope 语义映射片段的加载与跨片段冲突检测。
 * <p>
 * 目录约定：{@code mappings/scopes/{scope}-{platform}.tiny}，每个 scope 对 linux / windows
 * 各一个文件，格式与现有 tiny 表一致（{@code tiny 2 0 obf named}，obf 列保持游戏真实混淆名，
 * named 列为语义名，允许注释行）。片段是全量表语义层的数据源，分层优先级为
 * 占位生成 &lt; identity 片段 &lt; scope 片段 &lt; 人工运行期表（同混淆 key 高层胜出），
 * 由 {@link FullMappingMerger} 合并进全量表。
 * <p>
 * scope 之间必须互不相交：同一混淆类/成员被两个 scope 映射、或同一 named 类名被两个 scope
 * 用于不同混淆类，都属于冲突，{@link #crossScopeConflictLines(List)} 会逐条报告并指明两个 scope。
 */
public final class ScopeFragments {
    private ScopeFragments() {
    }

    /**
     * 单个 scope 的映射片段。
     *
     * @param scope   scope 名（取自文件名 {@code {scope}-{platform}.tiny} 的前缀）
     * @param entries 片段内的映射条目（按文件顺序）
     */
    public record ScopeFragment(String scope, List<MappingEntry> entries) {
        public ScopeFragment {
            Objects.requireNonNull(scope, "scope");
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        }
    }

    /**
     * 加载指定平台的全部 scope 片段。
     *
     * @param scopesDir scope 片段目录（{@code mappings/scopes}）；不存在时返回空列表
     * @param platform  目标平台（只读取 {@code *-{platform}.tiny} 文件）
     * @return 按 scope 名排序的片段列表
     */
    public static List<ScopeFragment> load(Path scopesDir, MappingPlatform platform) {
        Objects.requireNonNull(scopesDir, "scopesDir");
        Objects.requireNonNull(platform, "platform");
        if (!Files.isDirectory(scopesDir)) {
            return List.of();
        }

        String suffix = "-" + platform.id() + ".tiny";
        List<Path> fragmentFiles;
        try (Stream<Path> files = Files.list(scopesDir)) {
            fragmentFiles = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            throw new MappingLookupException("读取 scope 片段目录失败: " + scopesDir, exception);
        }

        List<ScopeFragment> fragments = new ArrayList<>();
        for (Path fragmentFile : fragmentFiles) {
            String fileName = fragmentFile.getFileName().toString();
            String scope = fileName.substring(0, fileName.length() - suffix.length());
            fragments.add(new ScopeFragment(scope, TinyV2MappingRepository.loadFromFile(fragmentFile).entries()));
        }
        return List.copyOf(fragments);
    }

    /**
     * 把全部片段的条目展平为单一列表（按 scope 名序、片段内保持文件顺序）。
     *
     * @param fragments scope 片段列表
     * @return 展平后的条目列表
     */
    public static List<MappingEntry> mergedEntries(List<ScopeFragment> fragments) {
        Objects.requireNonNull(fragments, "fragments");
        List<MappingEntry> entries = new ArrayList<>();
        for (ScopeFragment fragment : fragments) {
            entries.addAll(fragment.entries());
        }
        return entries;
    }

    /**
     * 检测跨 scope 冲突。
     * <p>
     * 冲突判据：
     * <ul>
     *     <li>同一混淆类被两个 scope 映射（即使 named 名一致也不允许，scope 间必须互斥）；</li>
     *     <li>同一混淆成员（owner + 种类 + 混淆名 + 换算为混淆形式的描述符）被两个 scope 映射；</li>
     *     <li>同一 named 类名被两个 scope 用于不同混淆类。</li>
     * </ul>
     * 成员描述符在比较前按全部片段的类条目做 named→obf 换算，避免同一成员因
     * 描述符写法不同而漏判；表外类引用保持原样（最佳努力比较）。
     *
     * @param fragments scope 片段列表
     * @return 冲突描述行（每条都指明涉及的两个 scope）；无冲突返回空列表
     */
    public static List<String> crossScopeConflictLines(List<ScopeFragment> fragments) {
        Objects.requireNonNull(fragments, "fragments");

        Map<String, String> namedToObfuscated = new HashMap<>();
        for (ScopeFragment fragment : fragments) {
            for (MappingEntry entry : fragment.entries()) {
                if (entry.isClass()) {
                    namedToObfuscated.put(entry.namedName(), entry.obfuscatedName());
                }
            }
        }

        Map<String, String> classScopeByObfuscated = new LinkedHashMap<>();
        Map<String, String> classScopeByNamed = new LinkedHashMap<>();
        Map<String, String> memberScopeByObfuscatedKey = new LinkedHashMap<>();
        List<String> conflicts = new ArrayList<>();
        for (ScopeFragment fragment : fragments) {
            for (MappingEntry entry : fragment.entries()) {
                if (entry.isClass()) {
                    String previousScope = classScopeByObfuscated.putIfAbsent(entry.obfuscatedName(), fragment.scope());
                    if (previousScope != null) {
                        conflicts.add("混淆类 " + entry.obfuscatedName() + " 同时被 scope '"
                                + previousScope + "' 与 '" + fragment.scope() + "' 映射");
                    }
                    String namedOwnerScope = classScopeByNamed.putIfAbsent(entry.namedName(), fragment.scope());
                    if (namedOwnerScope != null && !namedOwnerScope.equals(fragment.scope())) {
                        conflicts.add("named 类名 " + entry.namedName() + " 同时被 scope '"
                                + namedOwnerScope + "' 与 '" + fragment.scope() + "' 使用");
                    }
                    continue;
                }

                String memberKey = entry.ownerObfuscatedName() + '#' + entry.kind() + '#'
                        + entry.obfuscatedName() + '#' + toObfuscatedDescriptor(entry.descriptor(), namedToObfuscated);
                String previousScope = memberScopeByObfuscatedKey.putIfAbsent(memberKey, fragment.scope());
                if (previousScope != null && !previousScope.equals(fragment.scope())) {
                    conflicts.add("混淆成员 " + entry.ownerObfuscatedName() + '#' + entry.obfuscatedName()
                            + " 同时被 scope '" + previousScope + "' 与 '" + fragment.scope() + "' 映射");
                }
            }
        }
        return conflicts;
    }

    /**
     * 检测单个候选片段与既有片段集合之间的冲突（{@code validateScopeFragment} 的数据逻辑）。
     * <p>
     * 判据与 {@link #crossScopeConflictLines(List)} 一致，但只报告涉及候选片段的冲突：
     * 既有片段集合内部假定已互斥（入库前由 mergeScopeFragments 保证）。候选片段内部的
     * 重复类声明也会被报出。
     *
     * @param existing  既有 scope 片段列表（不含候选片段）
     * @param candidate 待校验的候选片段
     * @return 冲突描述行（每条都指明涉及的两个 scope）；无冲突返回空列表
     */
    public static List<String> conflictLinesAgainst(List<ScopeFragment> existing, ScopeFragment candidate) {
        Objects.requireNonNull(existing, "existing");
        Objects.requireNonNull(candidate, "candidate");

        Map<String, String> namedToObfuscated = new HashMap<>();
        for (ScopeFragment fragment : existing) {
            for (MappingEntry entry : fragment.entries()) {
                if (entry.isClass()) {
                    namedToObfuscated.put(entry.namedName(), entry.obfuscatedName());
                }
            }
        }
        Map<String, String> classScopeByObfuscated = new LinkedHashMap<>();
        Map<String, String> classScopeByNamed = new LinkedHashMap<>();
        Map<String, String> memberScopeByObfuscatedKey = new LinkedHashMap<>();
        for (ScopeFragment fragment : existing) {
            for (MappingEntry entry : fragment.entries()) {
                if (entry.isClass()) {
                    classScopeByObfuscated.putIfAbsent(entry.obfuscatedName(), fragment.scope());
                    classScopeByNamed.putIfAbsent(entry.namedName(), fragment.scope());
                    continue;
                }
                memberScopeByObfuscatedKey.putIfAbsent(memberKey(entry, namedToObfuscated), fragment.scope());
            }
        }

        List<String> conflicts = new ArrayList<>();
        for (MappingEntry entry : candidate.entries()) {
            if (entry.isClass()) {
                namedToObfuscated.putIfAbsent(entry.namedName(), entry.obfuscatedName());
                String previousScope = classScopeByObfuscated.putIfAbsent(entry.obfuscatedName(), candidate.scope());
                if (previousScope != null) {
                    conflicts.add("混淆类 " + entry.obfuscatedName() + " 同时被 scope '"
                            + previousScope + "' 与 '" + candidate.scope() + "' 映射");
                }
                String namedOwnerScope = classScopeByNamed.putIfAbsent(entry.namedName(), candidate.scope());
                if (namedOwnerScope != null && !namedOwnerScope.equals(candidate.scope())) {
                    conflicts.add("named 类名 " + entry.namedName() + " 同时被 scope '"
                            + namedOwnerScope + "' 与 '" + candidate.scope() + "' 使用");
                }
                continue;
            }
            String previousScope = memberScopeByObfuscatedKey.putIfAbsent(memberKey(entry, namedToObfuscated), candidate.scope());
            if (previousScope != null && !previousScope.equals(candidate.scope())) {
                conflicts.add("混淆成员 " + entry.ownerObfuscatedName() + '#' + entry.obfuscatedName()
                        + " 同时被 scope '" + previousScope + "' 与 '" + candidate.scope() + "' 映射");
            }
        }
        return conflicts;
    }

    /**
     * 成员扩展感知的候选片段冲突检测（{@code validateScopeFragment} 批量命名工作流用）。
     * <p>
     * 批量成员命名时，代理产出的片段会为既有 scope 已声明的类补充成员条目（成员扩展片段，
     * 合入时由编排方把成员行迁入归属 scope）。候选片段中 obf + named 与既有声明完全一致
     * 的类视为合法扩展，其类级冲突不报；named 不一致（同名异类 / 同类异名）与成员级冲突
     * 照常报出。
     *
     * @param existing  既有 scope 片段列表（不含候选片段）
     * @param candidate 待校验的候选片段
     * @return 冲突描述行；无冲突返回空列表
     */
    public static List<String> extensionAwareConflictLines(List<ScopeFragment> existing, ScopeFragment candidate) {
        Objects.requireNonNull(existing, "existing");
        Objects.requireNonNull(candidate, "candidate");

        Map<String, String> existingNamedByObfuscated = new HashMap<>();
        for (ScopeFragment fragment : existing) {
            for (MappingEntry entry : fragment.entries()) {
                if (entry.isClass()) {
                    existingNamedByObfuscated.putIfAbsent(entry.obfuscatedName(), entry.namedName());
                }
            }
        }
        List<String> conflicts = conflictLinesAgainst(existing, candidate);
        List<String> filtered = new ArrayList<>();
        for (String conflict : conflicts) {
            boolean extensionConflict = false;
            for (MappingEntry entry : candidate.entries()) {
                if (!entry.isClass()) {
                    continue;
                }
                if (!entry.namedName().equals(existingNamedByObfuscated.get(entry.obfuscatedName()))) {
                    continue;
                }
                // obf + named 均与既有声明一致：该类是合法成员扩展，跳过其两条类级冲突。
                if (conflict.startsWith("混淆类 " + entry.obfuscatedName() + " 同时被")
                        || conflict.startsWith("named 类名 " + entry.namedName() + " 同时被")) {
                    extensionConflict = true;
                    break;
                }
            }
            if (!extensionConflict) {
                filtered.add(conflict);
            }
        }
        return filtered;
    }

    private static String memberKey(MappingEntry entry, Map<String, String> namedToObfuscated) {
        return entry.ownerObfuscatedName() + '#' + entry.kind() + '#'
                + entry.obfuscatedName() + '#' + toObfuscatedDescriptor(entry.descriptor(), namedToObfuscated);
    }

    private static String toObfuscatedDescriptor(String descriptor, Map<String, String> namedToObfuscated) {
        if (descriptor == null || descriptor.indexOf('L') < 0) {
            return descriptor;
        }
        StringBuilder builder = new StringBuilder(descriptor.length());
        int cursor = 0;
        while (cursor < descriptor.length()) {
            char current = descriptor.charAt(cursor);
            if (current != 'L') {
                builder.append(current);
                cursor++;
                continue;
            }
            int end = descriptor.indexOf(';', cursor);
            if (end < 0) {
                throw new MappingLookupException("描述符格式不正确: " + descriptor);
            }
            String internalName = descriptor.substring(cursor + 1, end);
            builder.append('L').append(namedToObfuscated.getOrDefault(internalName, internalName)).append(';');
            cursor = end + 1;
        }
        return builder.toString();
    }
}
