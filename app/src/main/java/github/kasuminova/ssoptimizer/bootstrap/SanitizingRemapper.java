package github.kasuminova.ssoptimizer.bootstrap;

import org.objectweb.asm.commons.Remapper;

/**
 * ASM {@link Remapper} 的净化实现，将游戏引擎的超长混淆标识符映射为可读的短名称。
 * <p>
 * 对方法名、字段名中包含的非法字符（{@code .}、{@code ;}、{@code [}、{@code /}）进行转义替换，
 * 使其成为合法的 JVM 标识符。跳过 {@code <init>} 和 {@code <clinit>} 等特殊方法名。
 */
public final class SanitizingRemapper extends Remapper {
    private boolean modified;

    /**
     * {@inheritDoc} 对方法名进行净化处理。
     */
    @Override
    public String mapMethodName(String owner, String name, String descriptor) {
        return sanitize(name);
    }

    /**
     * {@inheritDoc} 对字段名进行净化处理。
     */
    @Override
    public String mapFieldName(String owner, String name, String descriptor) {
        return sanitize(name);
    }

    /**
     * {@inheritDoc} 对 invokedynamic 方法名进行净化处理。
     */
    @Override
    public String mapInvokeDynamicMethodName(String name, String descriptor) {
        return sanitize(name);
    }

    /**
     * 返回自上次 {@link #reset()} 以来是否有标识符被净化。
     */
    public boolean isModified() {
        return modified;
    }

    /**
     * 重置修改标记。
     */
    public void reset() {
        modified = false;
    }

    String sanitize(String name) {
        if ("<init>".equals(name) || "<clinit>".equals(name)) {
            return name;
        }
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
        modified = true;
        return name.replace(".", "$dot$")
                   .replace(";", "$semi$")
                   .replace("[", "$arr$")
                   .replace("/", "$slash$");
    }
}
