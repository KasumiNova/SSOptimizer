package github.kasuminova.ssoptimizer.bootstrap;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 反射工具类，在执行反射查找前先将混淆名称翻译为净化后的名称。
 * <p>
 * Runtime helper that translates obfuscated names before performing
 * reflection lookups. Bytecode in game classes is rewritten to call
 * these static methods instead of the original Class.getMethod/getField
 * family, so that reflection "just works" even though the actual
 * definitions have been sanitized by {@link SanitizingRemapper}.
 */
public final class ReflectionHelper {

    private ReflectionHelper() {
    }

    /**
     * 翻译名称后调用 {@link Class#getMethod}。
     *
     * @param clazz      目标类
     * @param name       原始（可能混淆）方法名
     * @param paramTypes 参数类型数组
     * @return 匹配的 {@link Method} 实例
     * @throws NoSuchMethodException 若方法不存在
     * @throws SecurityException     若安全管理器拒绝访问
     */
    public static Method getMethod(Class<?> clazz, String name, Class<?>... paramTypes)
            throws NoSuchMethodException, SecurityException {
        return clazz.getMethod(NameTranslator.translate(name), paramTypes);
    }

    /**
     * 翻译名称后调用 {@link Class#getDeclaredMethod}。
     *
     * @param clazz      目标类
     * @param name       原始（可能混淆）方法名
     * @param paramTypes 参数类型数组
     * @return 匹配的 {@link Method} 实例
     * @throws NoSuchMethodException 若方法不存在
     * @throws SecurityException     若安全管理器拒绝访问
     */
    public static Method getDeclaredMethod(Class<?> clazz, String name, Class<?>... paramTypes)
            throws NoSuchMethodException, SecurityException {
        return clazz.getDeclaredMethod(NameTranslator.translate(name), paramTypes);
    }

    /**
     * 翻译名称后调用 {@link Class#getField}。
     *
     * @param clazz 目标类
     * @param name  原始（可能混淆）字段名
     * @return 匹配的 {@link Field} 实例
     * @throws NoSuchFieldException 若字段不存在
     * @throws SecurityException    若安全管理器拒绝访问
     */
    public static Field getField(Class<?> clazz, String name)
            throws NoSuchFieldException, SecurityException {
        return clazz.getField(NameTranslator.translate(name));
    }

    /**
     * 翻译名称后调用 {@link Class#getDeclaredField}。
     *
     * @param clazz 目标类
     * @param name  原始（可能混淆）字段名
     * @return 匹配的 {@link Field} 实例
     * @throws NoSuchFieldException 若字段不存在
     * @throws SecurityException    若安全管理器拒绝访问
     */
    public static Field getDeclaredField(Class<?> clazz, String name)
            throws NoSuchFieldException, SecurityException {
        return clazz.getDeclaredField(NameTranslator.translate(name));
    }
}
