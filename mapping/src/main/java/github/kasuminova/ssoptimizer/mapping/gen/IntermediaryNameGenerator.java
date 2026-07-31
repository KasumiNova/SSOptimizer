package github.kasuminova.ssoptimizer.mapping.gen;

import github.kasuminova.ssoptimizer.mapping.MappingEntry;
import github.kasuminova.ssoptimizer.mapping.TinyV2MappingRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 占位名（中间名）生成器。
 * <p>
 * 为混淆 jar 中的全部类与成员生成确定性的占位 named 名：
 * 类保留原包前缀、类名取 {@code C_<指纹8>}；成员取 {@code f_<指纹8>} / {@code m_<指纹8>}，
 * 成员指纹输入含描述符，同名重载天然区分。同一作用域内指纹冲突时按内部名排序，
 * 首个不加后缀，其余追加 {@code _2}/{@code _3} 序号。
 * <p>
 * 人工表已覆盖的类与成员一律跳过（人工条目优先，由
 * {@link FullMappingMerger} 合并回最终表）。构造方法与静态初始化块不生成映射。
 * 同类同名字段组（混淆器产生的 name 相同 desc 不同字段）无法按名消歧，整组跳过并保持混淆名。
 */
public final class IntermediaryNameGenerator {
    /**
     * 生成占位映射条目。
     *
     * @param classes          按内部名排序的类结构列表
     * @param humanRepository  人工映射表（已覆盖的类/成员跳过）
     * @param identityClasses  保持原名的类集合（app 编译期直接引用的类；
     *                         类与全部成员都不生成映射，remap 时自然保持原名）
     * @return 占位映射条目，类按内部名排序、成员按声明顺序跟随所属类
     */
    public List<MappingEntry> generate(List<ClassStructure> classes,
                                       TinyV2MappingRepository humanRepository,
                                       java.util.Set<String> identityClasses) {
        Objects.requireNonNull(classes, "classes");
        Objects.requireNonNull(humanRepository, "humanRepository");
        Objects.requireNonNull(identityClasses, "identityClasses");

        Map<String, String> classNamedNames = assignClassNames(classes, humanRepository, identityClasses);

        List<MappingEntry> entries = new ArrayList<>();
        for (ClassStructure classStructure : classes) {
            if (identityClasses.contains(classStructure.name())) {
                // 保持原名类：类条目由合并器从 identity 片段提供，成员不生成映射。
                continue;
            }
            String obfuscatedName = classStructure.name();
            String namedName = classNamedNames.get(obfuscatedName);
            if (namedName == null) {
                // 人工表已覆盖的类：类条目由合并器提供，这里只补未覆盖成员的占位名。
                namedName = humanRepository.requireClassByObfuscatedName(obfuscatedName).namedName();
            } else {
                entries.add(MappingEntry.classEntry(obfuscatedName, namedName));
            }
            entries.addAll(generateMembers(classStructure, namedName, humanRepository));
        }
        return entries;
    }

    private static Map<String, String> assignClassNames(List<ClassStructure> classes,
                                                        TinyV2MappingRepository humanRepository,
                                                        java.util.Set<String> identityClasses) {
        Map<String, List<ClassStructure>> byPackageAndHash = new LinkedHashMap<>();
        Map<String, String> hashes = new LinkedHashMap<>();
        for (ClassStructure classStructure : classes) {
            if (identityClasses.contains(classStructure.name())) {
                continue;
            }
            if (humanRepository.findClassByObfuscatedName(classStructure.name()).isPresent()) {
                continue;
            }
            String hash = StructuralFingerprint.ofClass(classStructure);
            hashes.put(classStructure.name(), hash);
            byPackageAndHash.computeIfAbsent(packageOf(classStructure.name()) + '/' + hash, key -> new ArrayList<>())
                    .add(classStructure);
        }

        Map<String, String> namedNames = new LinkedHashMap<>();
        for (List<ClassStructure> conflictGroup : byPackageAndHash.values()) {
            conflictGroup.sort(Comparator.comparing(ClassStructure::name));
            int ordinal = 1;
            for (ClassStructure classStructure : conflictGroup) {
                String simpleName = "C_" + hashes.get(classStructure.name()) + (ordinal == 1 ? "" : "_" + ordinal);
                String packageName = packageOf(classStructure.name());
                namedNames.put(classStructure.name(), packageName.isEmpty() ? simpleName : packageName + '/' + simpleName);
                ordinal++;
            }
        }
        return namedNames;
    }

    private static List<MappingEntry> generateMembers(ClassStructure classStructure,
                                                      String ownerNamedName,
                                                      TinyV2MappingRepository humanRepository) {
        String ownerObfuscatedName = classStructure.name();
        List<MappingEntry> members = new ArrayList<>();

        // 同名字段组整组跳过：仓库字段索引按 owner#name 不带描述符，同名不同 desc 无法消歧。
        Map<String, Integer> fieldNameCounts = new LinkedHashMap<>();
        for (ClassStructure.Member field : classStructure.fields()) {
            fieldNameCounts.merge(field.name(), 1, Integer::sum);
        }

        Map<String, List<ClassStructure.Member>> fieldsByHash = new LinkedHashMap<>();
        Map<String, ClassStructure.Member> generatedFields = new LinkedHashMap<>();
        for (ClassStructure.Member field : classStructure.fields()) {
            if (fieldNameCounts.get(field.name()) > 1) {
                continue;
            }
            if (humanRepository.findFieldByObfuscatedName(ownerObfuscatedName, field.name()).isPresent()) {
                continue;
            }
            String hash = StructuralFingerprint.ofField(field);
            fieldsByHash.computeIfAbsent(hash, key -> new ArrayList<>()).add(field);
            generatedFields.put(field.name() + ':' + field.desc(), field);
        }
        Map<String, String> fieldNamedNames = assignMemberNames(fieldsByHash, "f_");
        for (ClassStructure.Member field : classStructure.fields()) {
            String key = field.name() + ':' + field.desc();
            if (!generatedFields.containsKey(key)) {
                continue;
            }
            members.add(MappingEntry.fieldEntry(
                    ownerObfuscatedName, ownerNamedName, field.name(), fieldNamedNames.get(key), field.desc()));
        }

        Map<String, List<ClassStructure.Member>> methodsByHash = new LinkedHashMap<>();
        Map<String, ClassStructure.Member> generatedMethods = new LinkedHashMap<>();
        for (ClassStructure.Member method : classStructure.methods()) {
            if ("<init>".equals(method.name()) || "<clinit>".equals(method.name())) {
                continue;
            }
            if (humanRepository.findMethodByObfuscatedName(ownerObfuscatedName, method.name(), method.desc()).isPresent()) {
                continue;
            }
            String hash = StructuralFingerprint.ofMethod(method);
            methodsByHash.computeIfAbsent(hash, key -> new ArrayList<>()).add(method);
            generatedMethods.put(method.name() + ':' + method.desc(), method);
        }
        Map<String, String> methodNamedNames = assignMemberNames(methodsByHash, "m_");
        for (ClassStructure.Member method : classStructure.methods()) {
            String key = method.name() + ':' + method.desc();
            if (!generatedMethods.containsKey(key)) {
                continue;
            }
            members.add(MappingEntry.methodEntry(
                    ownerObfuscatedName, ownerNamedName, method.name(), methodNamedNames.get(key), method.desc()));
        }

        return members;
    }

    private static Map<String, String> assignMemberNames(Map<String, List<ClassStructure.Member>> byHash, String prefix) {
        Map<String, String> namedNames = new LinkedHashMap<>();
        for (Map.Entry<String, List<ClassStructure.Member>> hashGroup : byHash.entrySet()) {
            List<ClassStructure.Member> conflictGroup = hashGroup.getValue();
            conflictGroup.sort(Comparator.comparing((ClassStructure.Member member) -> member.name())
                    .thenComparing(ClassStructure.Member::desc));
            int ordinal = 1;
            for (ClassStructure.Member member : conflictGroup) {
                String namedName = prefix + hashGroup.getKey() + (ordinal == 1 ? "" : "_" + ordinal);
                namedNames.put(member.name() + ':' + member.desc(), namedName);
                ordinal++;
            }
        }
        return namedNames;
    }

    private static String packageOf(String internalName) {
        int lastSlash = internalName.lastIndexOf('/');
        return lastSlash < 0 ? "" : internalName.substring(0, lastSlash);
    }
}
