package github.kasuminova.ssoptimizer.agent;

/**
 * Resolves common super classes for ASM frame computation when the involved
 * types are available to the current JVM, but safely falls back to
 * {@code java/lang/Object} for game-only classes that are not loadable during
 * transformation.
 */
public final class AsmCommonSuperClassResolver {
    private AsmCommonSuperClassResolver() {
    }

    public static String resolve(final String type1, final String type2) {
        if (type1.equals(type2)) {
            return type1;
        }

        final Class<?> class1 = tryLoad(type1);
        final Class<?> class2 = tryLoad(type2);
        if (class1 == null || class2 == null) {
            return "java/lang/Object";
        }

        if (class1.isAssignableFrom(class2)) {
            return type1;
        }
        if (class2.isAssignableFrom(class1)) {
            return type2;
        }
        if (class1.isInterface() || class2.isInterface()) {
            return "java/lang/Object";
        }

        Class<?> candidate = class1;
        while (candidate != null && !candidate.isAssignableFrom(class2)) {
            candidate = candidate.getSuperclass();
        }
        return candidate == null ? "java/lang/Object" : candidate.getName().replace('.', '/');
    }

    private static Class<?> tryLoad(final String internalName) {
        final String className = internalName.replace('/', '.');
        try {
            return Class.forName(className, false, AsmCommonSuperClassResolver.class.getClassLoader());
        } catch (Throwable ignored) {
            try {
                return Class.forName(className, false, null);
            } catch (Throwable ignoredAgain) {
                return null;
            }
        }
    }
}