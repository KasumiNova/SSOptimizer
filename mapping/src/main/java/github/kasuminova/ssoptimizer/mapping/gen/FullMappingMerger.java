package github.kasuminova.ssoptimizer.mapping.gen;

import github.kasuminova.ssoptimizer.mapping.MappingEntry;
import github.kasuminova.ssoptimizer.mapping.MappingLookupException;
import github.kasuminova.ssoptimizer.mapping.MappingTableExporter;
import github.kasuminova.ssoptimizer.mapping.TinyV2MappingRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 全量映射表合并器。
 * <p>
 * 输入人工映射条目（运行期权威表）与 {@link IntermediaryNameGenerator} 生成的占位条目，
 * 输出构建期全量表条目：人工条目永远优先（同混淆类/成员以人工名为准），
 * 人工条目附带的注释原样保留，占位条目无注释。
 * <p>
 * 输出条目按混淆类名排序，类块内先人工成员（保持人工表顺序）后占位成员（保持 jar 声明顺序），
 * 保证同一输入两次合并输出字节一致。
 * 生成条目的描述符在此统一换算为 named 存储：表内类写 named 名，表外类（JDK / 第三方 /
 * 未混淆的 starfarer.api）保持原样。
 */
public final class FullMappingMerger {
    /**
     * 合并人工条目与占位条目为全量表条目。
     *
     * @param humanEntries     人工映射条目（优先）
     * @param generatedEntries 生成的占位条目
     * @return 全量表条目（排序确定）
     */
    public List<MappingEntry> merge(List<MappingEntry> humanEntries, List<MappingEntry> generatedEntries) {
        Objects.requireNonNull(humanEntries, "humanEntries");
        Objects.requireNonNull(generatedEntries, "generatedEntries");

        Map<String, MappingEntry> classByObfuscated = new LinkedHashMap<>();
        Map<String, String> namedClassOwner = new HashMap<>();
        Map<String, List<MappingEntry>> humanMembersByOwner = new LinkedHashMap<>();
        Map<String, List<MappingEntry>> generatedMembersByOwner = new LinkedHashMap<>();
        Set<String> humanMemberKeys = new HashSet<>();

        for (MappingEntry entry : humanEntries) {
            if (entry.isClass()) {
                classByObfuscated.put(entry.obfuscatedName(), entry);
                namedClassOwner.put(entry.namedName(), entry.obfuscatedName());
            } else {
                humanMembersByOwner.computeIfAbsent(entry.ownerObfuscatedName(), key -> new ArrayList<>()).add(entry);
                humanMemberKeys.add(memberKey(entry));
            }
        }

        Map<String, MappingEntry> generatedClassByObfuscated = new LinkedHashMap<>();
        for (MappingEntry entry : generatedEntries) {
            if (entry.isClass()) {
                generatedClassByObfuscated.put(entry.obfuscatedName(), entry);
            } else {
                generatedMembersByOwner.computeIfAbsent(entry.ownerObfuscatedName(), key -> new ArrayList<>()).add(entry);
            }
        }

        // 人工优先：占位生成器已跳过人工条目，这里再做一次防御性去重与 named 唯一性校验。
        for (MappingEntry generatedClass : generatedClassByObfuscated.values()) {
            if (classByObfuscated.containsKey(generatedClass.obfuscatedName())) {
                continue;
            }
            String existingOwner = namedClassOwner.putIfAbsent(generatedClass.namedName(), generatedClass.obfuscatedName());
            if (existingOwner != null) {
                throw new MappingLookupException("全量表 named 类名冲突: " + generatedClass.namedName()
                        + " 同时映射 " + existingOwner + " 与 " + generatedClass.obfuscatedName());
            }
            classByObfuscated.put(generatedClass.obfuscatedName(), generatedClass);
        }

        Set<String> allClassNames = new TreeSet<>(classByObfuscated.keySet());
        List<MappingEntry> merged = new ArrayList<>();
        for (String className : allClassNames) {
            merged.add(classByObfuscated.get(className));
            List<MappingEntry> humanMembers = humanMembersByOwner.getOrDefault(className, List.of());
            merged.addAll(humanMembers);
            for (MappingEntry generatedMember : generatedMembersByOwner.getOrDefault(className, List.of())) {
                if (humanMemberKeys.contains(memberKey(generatedMember))) {
                    continue;
                }
                merged.add(toNamedDescriptorEntry(generatedMember, classByObfuscated));
            }
        }
        return merged;
    }

    /**
     * 用导出器把全量表条目序列化为 Tiny v2 文本。
     *
     * @param mergedEntries 合并后的全量条目
     * @return Tiny v2 文本
     */
    public String exportTiny(List<MappingEntry> mergedEntries) {
        return new MappingTableExporter(TinyV2MappingRepository.of(mergedEntries)).exportTiny();
    }

    /**
     * 计算漂移报告条目：人工映射在 jar 当前结构中找不到对应类/成员的条目列表。
     *
     * @param humanEntries 人工映射条目
     * @param classes      jar 扫描出的类结构
     * @return 漂移描述行（无漂移时为空列表）
     */
    public static List<String> driftLines(List<MappingEntry> humanEntries, List<ClassStructure> classes) {
        Objects.requireNonNull(humanEntries, "humanEntries");
        Objects.requireNonNull(classes, "classes");

        Map<String, ClassStructure> classByName = new HashMap<>();
        for (ClassStructure classStructure : classes) {
            classByName.put(classStructure.name(), classStructure);
        }
        Map<String, String> namedToObfuscated = new HashMap<>();
        for (MappingEntry entry : humanEntries) {
            if (entry.isClass()) {
                namedToObfuscated.put(entry.namedName(), entry.obfuscatedName());
            }
        }

        List<String> drift = new ArrayList<>();
        for (MappingEntry entry : humanEntries) {
            if (entry.isClass()) {
                if (!classByName.containsKey(entry.obfuscatedName())) {
                    drift.add("类缺失: " + entry.namedName() + " (表中混淆类名: " + entry.obfuscatedName() + ")");
                }
                continue;
            }

            ClassStructure owner = classByName.get(entry.ownerObfuscatedName());
            if (owner == null) {
                drift.add("owner 类缺失: " + entry.ownerNamedName() + '#' + entry.namedName()
                        + " (表中混淆 owner: " + entry.ownerObfuscatedName() + ")");
                continue;
            }
            String obfuscatedDescriptor = toObfuscatedDescriptor(entry.descriptor(), namedToObfuscated);
            String expected = entry.obfuscatedName() + ':' + obfuscatedDescriptor;
            boolean found = entry.isField()
                    ? owner.fields().stream().anyMatch(field -> (field.name() + ':' + field.desc()).equals(expected))
                    : owner.methods().stream().anyMatch(method -> (method.name() + ':' + method.desc()).equals(expected));
            if (!found) {
                drift.add((entry.isField() ? "字段缺失: " : "方法缺失: ")
                        + entry.ownerNamedName() + '#' + entry.namedName()
                        + " (表中混淆成员: " + entry.obfuscatedName() + ", 换算后描述符: " + obfuscatedDescriptor + ")");
            }
        }
        return drift;
    }

    private static MappingEntry toNamedDescriptorEntry(MappingEntry entry, Map<String, MappingEntry> classByObfuscated) {
        String namedDescriptor = remapDescriptor(entry.descriptor(), classByObfuscated);
        if (namedDescriptor.equals(entry.descriptor())) {
            return entry;
        }
        if (entry.isField()) {
            return MappingEntry.fieldEntry(entry.ownerObfuscatedName(), entry.ownerNamedName(),
                    entry.obfuscatedName(), entry.namedName(), namedDescriptor);
        }
        return MappingEntry.methodEntry(entry.ownerObfuscatedName(), entry.ownerNamedName(),
                entry.obfuscatedName(), entry.namedName(), namedDescriptor);
    }

    private static String remapDescriptor(String descriptor, Map<String, MappingEntry> classByObfuscated) {
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
            MappingEntry classEntry = classByObfuscated.get(internalName);
            builder.append('L').append(classEntry == null ? internalName : classEntry.namedName()).append(';');
            cursor = end + 1;
        }
        return builder.toString();
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

    private static String memberKey(MappingEntry entry) {
        return entry.ownerObfuscatedName() + '#' + entry.kind() + '#' + entry.obfuscatedName() + '#' + entry.descriptor();
    }
}
