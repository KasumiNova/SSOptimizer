package github.kasuminova.ssoptimizer.bootstrap;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 反射辅助类的 remapped 命名解析测试。
 * <p>
 * 该测试确保 {@link ReflectionHelper} 通过 {@link NameTranslator} 可以把 Tiny v2 中的
 * 混淆成员名翻译为可读命名，然后再回落到标准的 Java 反射查找流程。
 */
class ReflectionHelperTest {

    @Test
    void resolvesMappedFieldNameThroughTranslator() throws Exception {
        Field field = ReflectionHelper.getDeclaredField(RemappedTarget.class, "a");

        assertEquals("cacheSize", field.getName());
    }

    @Test
    void resolvesMappedMethodNameThroughTranslator() throws Exception {
        Method method = ReflectionHelper.getDeclaredMethod(RemappedTarget.class, "b", int.class);

        assertEquals("reloadCache", method.getName());
    }

    private static final class RemappedTarget {
        private int cacheSize;

        @SuppressWarnings("unused")
        private void reloadCache(int value) {
            this.cacheSize = value;
        }
    }
}