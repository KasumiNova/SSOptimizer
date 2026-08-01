package github.kasuminova.ssoptimizer.mapping.gen;

import github.kasuminova.ssoptimizer.mapping.MappingEntry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 继承成员别名传播器。
 * <p>
 * 动机：游戏的混淆器会把继承成员的引用直接挂在子类 owner 上（例如
 * {@code TitleMusicPlayer} 内出现 {@code Fieldref TitleMusicPlayer.Ò00000}，
 * 而该字段实际声明在父类 {@code BaseMusicPlayer}）。映射表只在声明类一侧登记成员条目，
 * 精确 owner 查询 misses 后引用保留混淆名，named jar 运行期解析即抛
 * {@link NoSuchFieldError} / {@link NoSuchMethodError}（JVM 成员解析沿继承链向上查找，
 * 重映射也必须做同样的事）。
 * <p>
 * 行为：对合并后的全量条目，沿每个类的父类与接口传递闭包查找其声明的成员，
 * 若子类未声明同名成员（同名为单位，隐藏/覆写场景子类条目优先），则为子类生成
 * 同名同目标的别名条目并附"继承传播"注释。输出保持 Tiny 类块结构：别名条目
 * 插入所属类块末尾。生成是确定性的：同一输入两次运行输出字节一致。
 */
public final class InheritedMemberPropagator {
    /** 传播条目注释标记。 */
    static final String PROPAGATED_COMMENT =
            "继承传播(inherited-alias) | 混淆器将继承成员引用挂在子类 owner, 重映射需子类侧别名";

    private InheritedMemberPropagator() {
    }

    /**
     * 为合并后的全量条目补齐继承成员别名。
     *
     * @param mergedEntries 合并后的全量映射条目（描述符为 named 存储）
     * @param classes       混淆 jar 扫描出的类结构（提供继承关系与成员声明真值）
     * @return 含继承别名的新条目列表（输入列表不被修改）
     */
    public static List<MappingEntry> propagate(List<MappingEntry> mergedEntries, List<ClassStructure> classes) {
        Objects.requireNonNull(mergedEntries, "mergedEntries");
        Objects.requireNonNull(classes, "classes");

        Map<String, ClassStructure> structureByName = new HashMap<>();
        for (ClassStructure classStructure : classes) {
            structureByName.put(classStructure.name(), classStructure);
        }

        Map<String, String> namedClassByObfuscated = new HashMap<>();
        Map<String, Map<String, List<MappingEntry>>> membersByOwner = new HashMap<>();
        for (MappingEntry entry : mergedEntries) {
            if (entry.isClass()) {
                namedClassByObfuscated.put(entry.obfuscatedName(), entry.namedName());
                continue;
            }
            membersByOwner.computeIfAbsent(entry.ownerObfuscatedName(), key -> new HashMap<>())
                    .computeIfAbsent(memberLookupKey(entry), key -> new ArrayList<>())
                    .add(entry);
        }

        Map<String, List<MappingEntry>> propagatedByOwner = new LinkedHashMap<>();
        for (ClassStructure classStructure : classes) {
            List<MappingEntry> propagated = collectInheritedEntries(
                    classStructure, structureByName, membersByOwner, namedClassByObfuscated);
            if (!propagated.isEmpty()) {
                propagatedByOwner.put(classStructure.name(), propagated);
            }
        }
        if (propagatedByOwner.isEmpty()) {
            return mergedEntries;
        }

        // 重组输出：别名条目插入所属类块末尾（Tiny 成员行按最近类行归属，块外悬挂会挂错类）
        List<MappingEntry> result = new ArrayList<>(mergedEntries.size() + propagatedByOwner.size() * 2);
        String currentOwner = null;
        for (MappingEntry entry : mergedEntries) {
            if (entry.isClass()) {
                if (currentOwner != null) {
                    result.addAll(propagatedByOwner.getOrDefault(currentOwner, List.of()));
                }
                currentOwner = entry.obfuscatedName();
                result.add(entry);
                continue;
            }
            result.add(entry);
        }
        if (currentOwner != null) {
            result.addAll(propagatedByOwner.getOrDefault(currentOwner, List.of()));
        }
        // 有结构但全量表中无类条目的类（理论上不存在：占位生成覆盖全部扫描类），别名直接追加到末尾
        for (Map.Entry<String, List<MappingEntry>> ownerEntries : propagatedByOwner.entrySet()) {
            if (!namedClassByObfuscated.containsKey(ownerEntries.getKey())) {
                result.addAll(ownerEntries.getValue());
            }
        }
        return result;
    }

    private static List<MappingEntry> collectInheritedEntries(
            ClassStructure classStructure,
            Map<String, ClassStructure> structureByName,
            Map<String, Map<String, List<MappingEntry>>> membersByOwner,
            Map<String, String> namedClassByObfuscated) {
        Set<String> ownFieldNames = memberNames(classStructure.fields());
        Set<String> ownMethodNames = memberNames(classStructure.methods());
        String ownerNamed = namedClassByObfuscated.getOrDefault(classStructure.name(), classStructure.name());

        List<MappingEntry> propagated = new ArrayList<>();
        Set<String> addedKeys = new HashSet<>();
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        if (classStructure.superName() != null) {
            queue.add(classStructure.superName());
        }
        queue.addAll(classStructure.interfaces());

        while (!queue.isEmpty()) {
            String ancestorName = queue.poll();
            if (!visited.add(ancestorName)) {
                continue;
            }
            ClassStructure ancestor = structureByName.get(ancestorName);
            if (ancestor == null) {
                continue;
            }
            for (ClassStructure.Member field : ancestor.fields()) {
                if (ownFieldNames.contains(field.name())) {
                    continue;
                }
                for (MappingEntry entry : lookup(membersByOwner, ancestorName, MappingEntry.Kind.FIELD, field.name())) {
                    if (addedKeys.add("FIELD#" + entry.obfuscatedName() + '#' + entry.descriptor())) {
                        propagated.add(MappingEntry.fieldEntry(classStructure.name(), ownerNamed,
                                entry.obfuscatedName(), entry.namedName(), entry.descriptor())
                                .withComment(PROPAGATED_COMMENT));
                    }
                }
            }
            for (ClassStructure.Member method : ancestor.methods()) {
                if (method.name().startsWith("<") || ownMethodNames.contains(method.name())) {
                    continue;
                }
                for (MappingEntry entry : lookup(membersByOwner, ancestorName, MappingEntry.Kind.METHOD, method.name())) {
                    if (addedKeys.add("METHOD#" + entry.obfuscatedName() + '#' + entry.descriptor())) {
                        propagated.add(MappingEntry.methodEntry(classStructure.name(), ownerNamed,
                                entry.obfuscatedName(), entry.namedName(), entry.descriptor())
                                .withComment(PROPAGATED_COMMENT));
                    }
                }
            }
            if (ancestor.superName() != null) {
                queue.add(ancestor.superName());
            }
            queue.addAll(ancestor.interfaces());
        }
        return propagated;
    }

    private static List<MappingEntry> lookup(Map<String, Map<String, List<MappingEntry>>> membersByOwner,
                                             String owner,
                                             MappingEntry.Kind kind,
                                             String obfuscatedName) {
        Map<String, List<MappingEntry>> byKey = membersByOwner.get(owner);
        if (byKey == null) {
            return List.of();
        }
        return byKey.getOrDefault(kind + "#" + obfuscatedName, List.of());
    }

    private static String memberLookupKey(MappingEntry entry) {
        return entry.kind() + "#" + entry.obfuscatedName();
    }

    private static Set<String> memberNames(List<ClassStructure.Member> members) {
        Set<String> names = new HashSet<>();
        for (ClassStructure.Member member : members) {
            names.add(member.name());
        }
        return names;
    }
}
