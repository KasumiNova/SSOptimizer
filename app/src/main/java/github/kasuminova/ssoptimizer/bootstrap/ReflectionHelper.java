package github.kasuminova.ssoptimizer.bootstrap;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Runtime helper that translates obfuscated names before performing
 * reflection lookups. Bytecode in game classes is rewritten to call
 * these static methods instead of the original Class.getMethod/getField
 * family, so that reflection "just works" even though the actual
 * definitions have been sanitized by {@link SanitizingRemapper}.
 */
public final class ReflectionHelper {

    private ReflectionHelper() {
    }

    public static Method getMethod(Class<?> clazz, String name, Class<?>... paramTypes)
            throws NoSuchMethodException, SecurityException {
        return clazz.getMethod(NameTranslator.translate(name), paramTypes);
    }

    public static Method getDeclaredMethod(Class<?> clazz, String name, Class<?>... paramTypes)
            throws NoSuchMethodException, SecurityException {
        return clazz.getDeclaredMethod(NameTranslator.translate(name), paramTypes);
    }

    public static Field getField(Class<?> clazz, String name)
            throws NoSuchFieldException, SecurityException {
        return clazz.getField(NameTranslator.translate(name));
    }

    public static Field getDeclaredField(Class<?> clazz, String name)
            throws NoSuchFieldException, SecurityException {
        return clazz.getDeclaredField(NameTranslator.translate(name));
    }
}
