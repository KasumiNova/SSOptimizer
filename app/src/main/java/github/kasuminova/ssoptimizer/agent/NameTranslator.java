package github.kasuminova.ssoptimizer.agent;

/**
 * Translates sanitized identifiers at runtime.
 * Used by reflection interceptors to map original obfuscated names
 * (e.g. "if.new") to their sanitized equivalents (e.g. "if$dot$new").
 */
public final class NameTranslator {

    private NameTranslator() {
    }

    /**
     * Translates an identifier that may contain illegal JVM characters
     * to its sanitized form. Returns the original name if no translation needed.
     */
    public static String translate(String name) {
        if (name == null) {
            return null;
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
        return name.replace(".", "$dot$")
                   .replace(";", "$semi$")
                   .replace("[", "$arr$")
                   .replace("/", "$slash$");
    }
}
