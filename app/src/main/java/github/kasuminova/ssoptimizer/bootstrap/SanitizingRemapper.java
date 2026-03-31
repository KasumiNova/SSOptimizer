package github.kasuminova.ssoptimizer.bootstrap;

import org.objectweb.asm.commons.Remapper;

public final class SanitizingRemapper extends Remapper {
    private boolean modified;

    @Override
    public String mapMethodName(String owner, String name, String descriptor) {
        return sanitize(name);
    }

    @Override
    public String mapFieldName(String owner, String name, String descriptor) {
        return sanitize(name);
    }

    @Override
    public String mapInvokeDynamicMethodName(String name, String descriptor) {
        return sanitize(name);
    }

    public boolean isModified() {
        return modified;
    }

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
