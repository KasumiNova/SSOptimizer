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
 * 成员名提升：Starsector 的混淆器只改写了部分成员名，未改写的成员在混淆 jar 中
 * 保留原始开发者命名（如 {@code ship}、{@code render}、{@code MAX_RANGE}）。
 * 这类可读混淆名直接提升为 named 名（重载方法同名提升，保持 Java 重载语义），
 * 不再落哈希占位；o0 字典垃圾名、Java 关键字/字面量/常见 JDK 类型名、
 * 编译器合成名（含 {@code $}）仍落哈希占位，交由后续语义命名处理。
 * <p>
 * 机械预命名：零歧义字段不落哈希占位，直接按规则派生 named 名——唯一
 * {@code static final long} 且实现 {@code Serializable} 的类 → {@code serialVersionUID}；
 * 带 ConstantValue 的 {@code static final String} 常量字段按值派生 camelCase 名；
 * {@code static} log4j Logger 字段 → {@code logger}。派生名与提升名/已分配名冲突时
 * 按声明顺序追加 {@code _2}/{@code _3}。
 * <p>
 * 人工表已覆盖的类与成员一律跳过（人工条目优先，由
 * {@link FullMappingMerger} 合并回最终表）。构造方法与静态初始化块不生成映射。
 * 同类同名字段组（混淆器产生的 name 相同 desc 不同字段）无法按名消歧，整组跳过并保持混淆名。
 */
public final class IntermediaryNameGenerator {
    /**
     * 混淆器字典保留名：Java 关键字、字面量与常见 JDK 类型名。
     * 这些名字是混淆器（Allatori 系）从字典里挑的合法标识符，不是原始命名，不得提升。
     */
    private static final java.util.Set<String> OBFUSCATOR_DICTIONARY_NAMES = java.util.Set.of(
            // Java 关键字与字面量（named 端必须保持可被 Java 源码引用）
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while",
            "true", "false", "null",
            // 混淆器字典里的常见 JDK 类型名（合法标识符但明显非原始命名）
            "String", "Object", "Class", "Number", "Integer", "Long", "Float", "Double", "Boolean",
            "Byte", "Short", "Character", "StringBuffer", "StringBuilder", "Void", "System",
            "Runtime", "Math", "Thread", "Runnable", "Iterable", "Comparable", "Enum", "Process",
            "Exception", "RuntimeException", "Error", "Throwable");

    /** 纯 ASCII Java 标识符。 */
    private static final java.util.regex.Pattern ASCII_IDENTIFIER =
            java.util.regex.Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");
    /** o0 字典垃圾特征：纯 o/O/0 堆叠。 */
    private static final java.util.regex.Pattern O0_DICTIONARY_NAME =
            java.util.regex.Pattern.compile("[oO0]{3,}");

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
        Map<String, String> promotedFieldNames = new LinkedHashMap<>();
        Map<String, String> mechanicalFieldNames = new LinkedHashMap<>();
        boolean serialVersionUidCandidate = isSerialVersionUidCandidate(classStructure);
        for (ClassStructure.Member field : classStructure.fields()) {
            if (fieldNameCounts.get(field.name()) > 1) {
                continue;
            }
            if (humanRepository.findFieldByObfuscatedName(ownerObfuscatedName, field.name()).isPresent()) {
                continue;
            }
            generatedFields.put(field.name() + ':' + field.desc(), field);
            if (isPromotableObfuscatedName(field.name())) {
                promotedFieldNames.put(field.name() + ':' + field.desc(), field.name());
            } else {
                String mechanicalName = mechanicalFieldName(field, serialVersionUidCandidate);
                if (mechanicalName != null) {
                    mechanicalFieldNames.put(field.name() + ':' + field.desc(), mechanicalName);
                } else {
                    String hash = StructuralFingerprint.ofField(field);
                    fieldsByHash.computeIfAbsent(hash, key -> new ArrayList<>()).add(field);
                }
            }
        }
        Map<String, String> fieldNamedNames = assignMemberNames(fieldsByHash, "f_");
        fieldNamedNames.putAll(promotedFieldNames);
        // 机械预命名按声明顺序去重：与提升名 / 同已分配的机械名冲突时追加 _2/_3。
        java.util.Set<String> usedFieldNames = new java.util.HashSet<>(promotedFieldNames.values());
        for (ClassStructure.Member field : classStructure.fields()) {
            String key = field.name() + ':' + field.desc();
            String baseName = mechanicalFieldNames.get(key);
            if (baseName == null) {
                continue;
            }
            String uniqueName = baseName;
            int ordinal = 2;
            while (usedFieldNames.contains(uniqueName)) {
                uniqueName = baseName + "_" + ordinal;
                ordinal++;
            }
            usedFieldNames.add(uniqueName);
            fieldNamedNames.put(key, uniqueName);
        }
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
        Map<String, String> promotedMethodNames = new LinkedHashMap<>();
        for (ClassStructure.Member method : classStructure.methods()) {
            if ("<init>".equals(method.name()) || "<clinit>".equals(method.name())) {
                continue;
            }
            if (humanRepository.findMethodByObfuscatedName(ownerObfuscatedName, method.name(), method.desc()).isPresent()) {
                continue;
            }
            generatedMethods.put(method.name() + ':' + method.desc(), method);
            if (isPromotableObfuscatedName(method.name())) {
                // 重载方法同名提升：named 端保持 Java 重载语义，不再按指纹区分。
                promotedMethodNames.put(method.name() + ':' + method.desc(), method.name());
            } else {
                String hash = StructuralFingerprint.ofMethod(method);
                methodsByHash.computeIfAbsent(hash, key -> new ArrayList<>()).add(method);
            }
        }
        Map<String, String> methodNamedNames = assignMemberNames(methodsByHash, "m_");
        methodNamedNames.putAll(promotedMethodNames);
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

    /**
     * 判定字段是否可机械预命名（零歧义类别），返回派生的 named 名；不可判定时返回 {@code null}。
     * <p>
     * 覆盖三类：实现 {@code Serializable} 且唯一的 {@code static final long} 字段 →
     * {@code serialVersionUID}；带 ConstantValue 的 {@code static final String} 常量字段 →
     * 按常量值派生 camelCase 名；{@code static} 的 log4j Logger 字段 → {@code logger}。
     *
     * @param field                    字段成员
     * @param serialVersionUidCandidate 所属类是否为 serialVersionUID 候选（实现 Serializable 且仅一个 static final long）
     * @return 派生名，或 {@code null}（落回哈希占位）
     */
    private static String mechanicalFieldName(ClassStructure.Member field, boolean serialVersionUidCandidate) {
        int access = field.access();
        boolean isStatic = (access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0;
        boolean isFinal = (access & org.objectweb.asm.Opcodes.ACC_FINAL) != 0;
        if (isStatic && isFinal && "J".equals(field.desc()) && serialVersionUidCandidate) {
            return "serialVersionUID";
        }
        if (isStatic && isFinal && "Ljava/lang/String;".equals(field.desc())
                && field.constantValue() instanceof String constantValue) {
            return deriveNameFromStringConstant(constantValue);
        }
        if (isStatic && "Lorg/apache/log4j/Logger;".equals(field.desc())) {
            return "logger";
        }
        return null;
    }

    /**
     * 判定类是否为 serialVersionUID 候选：直接实现 {@code java/io/Serializable}
     * 且全类仅一个 {@code static final long} 字段。
     */
    private static boolean isSerialVersionUidCandidate(ClassStructure classStructure) {
        if (!classStructure.interfaces().contains("java/io/Serializable")) {
            return false;
        }
        int staticFinalLongCount = 0;
        for (ClassStructure.Member field : classStructure.fields()) {
            int access = field.access();
            if ("J".equals(field.desc())
                    && (access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0
                    && (access & org.objectweb.asm.Opcodes.ACC_FINAL) != 0) {
                staticFinalLongCount++;
            }
        }
        return staticFinalLongCount == 1;
    }

    /**
     * 从字符串常量值派生 camelCase 成员名：按非字母数字切分，取末尾最多 3 个 token
     * 拼接（路径类常量避免过长名称）。值无法派生出合法且非关键字的标识符时返回
     * {@code null}（落回哈希占位）。
     */
    private static String deriveNameFromStringConstant(String value) {
        if (value.isBlank() || value.length() > 60) {
            return null;
        }
        List<String> tokens = new ArrayList<>();
        for (String token : value.split("[^A-Za-z0-9]+")) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        if (tokens.isEmpty()) {
            return null;
        }
        int from = Math.max(0, tokens.size() - 3);
        StringBuilder name = new StringBuilder();
        for (int index = from; index < tokens.size(); index++) {
            String token = tokens.get(index);
            if (index == from) {
                name.append(token.toLowerCase(java.util.Locale.ROOT));
            } else {
                name.append(Character.toUpperCase(token.charAt(0)));
                name.append(token.substring(1).toLowerCase(java.util.Locale.ROOT));
            }
        }
        String derived = name.toString();
        if (!ASCII_IDENTIFIER.matcher(derived).matches() || !Character.isLetter(derived.charAt(0))) {
            return null;
        }
        if (OBFUSCATOR_DICTIONARY_NAMES.contains(derived)) {
            return null;
        }
        return derived;
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

    /**
     * 判定混淆成员名是否为未被混淆器改写的原始名（可提升为 named 名）。
     * <p>
     * 可提升条件：长度 ≥ 3 的纯 ASCII 标识符，不含 {@code $}（排除 {@code this$0}、
     * {@code $SWITCH_TABLE$} 等编译器合成名），不在混淆器字典保留名集合
     * （Java 关键字/字面量/常见 JDK 类型名），且无 o0 字典垃圾特征
     * （连零串 {@code 0000} 或纯 o/O/0 堆叠）。
     *
     * @param name 混淆 jar 中的成员名
     * @return true 表示该名是原始开发者命名，可直接作为 named 名
     */
    static boolean isPromotableObfuscatedName(String name) {
        if (name.length() < 3 || name.indexOf('$') >= 0) {
            return false;
        }
        if (OBFUSCATOR_DICTIONARY_NAMES.contains(name)) {
            return false;
        }
        if (!ASCII_IDENTIFIER.matcher(name).matches()) {
            return false;
        }
        return !name.contains("0000") && !O0_DICTIONARY_NAME.matcher(name).matches();
    }

    private static String packageOf(String internalName) {
        int lastSlash = internalName.lastIndexOf('/');
        return lastSlash < 0 ? "" : internalName.substring(0, lastSlash);
    }
}
