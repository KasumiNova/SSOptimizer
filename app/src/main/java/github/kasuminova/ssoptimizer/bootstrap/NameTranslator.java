package github.kasuminova.ssoptimizer.bootstrap;

import github.kasuminova.ssoptimizer.mapping.MappingEntry;
import github.kasuminova.ssoptimizer.mapping.TinyV2MappingRepository;

/**
 * 名称翻译器。
 * <p>
 * 优先把 Tiny v2 中登记的混淆名称翻译成可读命名；若没有命中映射，则回退到旧的
 * JVM 非法字符净化逻辑，避免调试和反射辅助仍然依赖老行为时发生回归。
 */
public final class NameTranslator {
    private static final TinyV2MappingRepository MAPPINGS = TinyV2MappingRepository.loadDefault();

    private NameTranslator() {
    }

    /**
     * 翻译一个可能是混淆名、类名或非法 JVM 标识符的字符串。
     * <p>
     * 规则优先级为：Tiny v2 类映射 → Tiny v2 唯一成员映射 → 旧净化 fallback。
     *
     * @param name 待翻译名称
     * @return 可读命名或净化后的名称；若输入为 {@code null} 则返回 {@code null}
     */
    public static String translate(String name) {
        if (name == null) {
            return null;
        }

        String mappedName = translateFromMapping(name);
        if (mappedName != null) {
            return mappedName;
        }

        return sanitize(name);
    }

    private static String translateFromMapping(String name) {
        MappingEntry classEntry = MAPPINGS.findClassByObfuscatedName(name).orElse(null);
        if (classEntry == null) {
            classEntry = MAPPINGS.findClassByObfuscatedName(name.replace('.', '/')).orElse(null);
        }
        if (classEntry != null) {
            return classEntry.namedName();
        }

        MappingEntry memberEntry = null;
        for (MappingEntry entry : MAPPINGS.entries()) {
            if ((entry.isField() || entry.isMethod()) && name.equals(entry.obfuscatedName())) {
                if (memberEntry != null) {
                    return null;
                }
                memberEntry = entry;
            }
        }
        return memberEntry == null ? null : memberEntry.namedName();
    }

    private static String sanitize(String name) {
        boolean needsFix = false;
        for (int i = 0, len = name.length(); i < len; i++) {
            char c = name.charAt(i);
            if (c == '.' || c == ';' || c == '[' || c == '/') {
                needsFix = true;
                break;
            }
        }
        if (!needsFix) {
            return name;
        }
        return name.replace(".", "$dot$")
                   .replace(";", "$semi$")
                   .replace("[", "$arr$")
                   .replace("/", "$slash$");
    }
}
