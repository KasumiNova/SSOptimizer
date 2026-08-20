package github.kasuminova.ssoptimizer.common.save;

import com.thoughtworks.xstream.converters.reflection.ObjectAccessException;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * XStream 字段访问加速辅助类。
 * <p>
 * 职责：为 {@code com.thoughtworks.xstream.core.util.Fields} 提供带缓存的字段读写快路径，优先使用
 * {@link VarHandle} 访问实例/静态字段，失败时回退到 XStream 原本的反射语义。<br>
 * 设计动机：存档保存热点中会高频调用 {@code Fields.read(Field, Object)}，原实现每次都会走
 * {@link Field#get(Object)}；这里把字段解析成本前移到首次访问，并保留系统属性开关作为回退阀门。<br>
 * 兼容性策略：若 {@link VarHandle} 建立失败、字段为 {@code final}、或用户显式禁用快路径，则继续使用
 * 反射路径，避免破坏 XStream 既有读写行为。
 */
public final class XStreamFieldAccessHelper {
    /**
     * 禁用 XStream 字段访问快路径的系统属性。
     */
    public static final String DISABLE_FAST_ACCESS_PROPERTY = "ssoptimizer.disable.xstream.fastfieldaccess";

    private static final boolean FAST_ACCESS_ENABLED = !Boolean.getBoolean(DISABLE_FAST_ACCESS_PROPERTY);
    private static final ConcurrentMap<Field, FieldAccessor> ACCESSOR_CACHE = new ConcurrentHashMap<>();

    private XStreamFieldAccessHelper() {
    }

    /**
     * 读取指定字段的值。
     *
     * @param field  要读取的字段
     * @param target 字段所属对象；静态字段时可为 {@code null}
     * @return 字段当前值；原始类型会按 Java 反射语义装箱后返回
     */
    public static Object read(final Field field, final Object target) {
        if (!FAST_ACCESS_ENABLED) {
            return reflectRead(field, target);
        }

        final FieldAccessor accessor = accessorFor(field);
        if (accessor instanceof ReflectionFieldAccessor reflectionAccessor) {
            return reflectionAccessor.read(target);
        }

        try {
            return accessor.read(target);
        } catch (final Throwable ignored) {
            return reflectRead(field, target);
        }
    }

    /**
     * 向指定字段写入值。
     *
     * @param field  要写入的字段
     * @param target 字段所属对象；静态字段时可为 {@code null}
     * @param value  新值
     */
    public static void write(final Field field, final Object target, final Object value) {
        if (!FAST_ACCESS_ENABLED || Modifier.isFinal(field.getModifiers())) {
            reflectWrite(field, target, value);
            return;
        }

        final FieldAccessor accessor = accessorFor(field);
        if (accessor instanceof ReflectionFieldAccessor reflectionAccessor) {
            reflectionAccessor.write(target, value);
            return;
        }

        try {
            accessor.write(target, value);
        } catch (final Throwable ignored) {
            reflectWrite(field, target, value);
        }
    }

    private static FieldAccessor accessorFor(final Field field) {
        final FieldAccessor cached = ACCESSOR_CACHE.get(field);
        if (cached != null) {
            return cached;
        }

        final FieldAccessor created = createAccessor(field);
        final FieldAccessor previous = ACCESSOR_CACHE.putIfAbsent(field, created);
        return previous != null ? previous : created;
    }

    private static FieldAccessor createAccessor(final Field field) {
        if (!FAST_ACCESS_ENABLED || Modifier.isFinal(field.getModifiers())) {
            return new ReflectionFieldAccessor(field);
        }

        try {
            final MethodHandles.Lookup privateLookup = MethodHandles.privateLookupIn(
                    field.getDeclaringClass(),
                    MethodHandles.lookup()
            );
            final VarHandle handle = privateLookup.unreflectVarHandle(field);
            // 一次性把访问句柄适配到固定签名并固化 LambdaForm，避免运行期
            // 经 VarHandle.get/set 多态调用点反复触发 LambdaForm 重定制（checkCustomized）。
            if (Modifier.isStatic(field.getModifiers())) {
                final MethodHandle getter = handle.toMethodHandle(VarHandle.AccessMode.GET)
                        .asType(MethodType.methodType(Object.class));
                final MethodHandle setter = handle.toMethodHandle(VarHandle.AccessMode.SET)
                        .asType(MethodType.methodType(void.class, Object.class));
                return new StaticVarHandleFieldAccessor(field, getter, setter);
            }
            final MethodHandle getter = handle.toMethodHandle(VarHandle.AccessMode.GET)
                    .asType(MethodType.methodType(Object.class, Object.class));
            final MethodHandle setter = handle.toMethodHandle(VarHandle.AccessMode.SET)
                    .asType(MethodType.methodType(void.class, Object.class, Object.class));
            return new InstanceVarHandleFieldAccessor(field, getter, setter);
        } catch (final Throwable ignored) {
            return new ReflectionFieldAccessor(field);
        }
    }

    private static Object reflectRead(final Field field, final Object target) {
        try {
            return field.get(target);
        } catch (final SecurityException
                       | IllegalArgumentException
                       | IllegalAccessException
                       | NoClassDefFoundError e) {
            throw wrap("Cannot read field", field.getType(), field.getName(), e);
        }
    }

    private static void reflectWrite(final Field field, final Object target, final Object value) {
        try {
            field.set(target, value);
        } catch (final SecurityException
                       | IllegalArgumentException
                       | IllegalAccessException
                       | NoClassDefFoundError e) {
            throw wrap("Cannot write field", field.getType(), field.getName(), e);
        }
    }

    private static ObjectAccessException wrap(final String message,
                                              final Class<?> fieldType,
                                              final String fieldName,
                                              final Throwable cause) {
        final ObjectAccessException exception = new ObjectAccessException(message, cause);
        exception.add("field", fieldType.getName() + '.' + fieldName);
        return exception;
    }

    private interface FieldAccessor {
        Object read(Object target);

        void write(Object target, Object value);
    }

    private record ReflectionFieldAccessor(Field field) implements FieldAccessor {
        @Override
        public Object read(final Object target) {
            return reflectRead(field, target);
        }

        @Override
        public void write(final Object target, final Object value) {
            reflectWrite(field, target, value);
        }
    }

    private record InstanceVarHandleFieldAccessor(Field field, MethodHandle getter, MethodHandle setter)
            implements FieldAccessor {
        @Override
        public Object read(final Object target) {
            try {
                return getter.invokeExact(target);
            } catch (final Throwable t) {
                return reflectRead(field, target);
            }
        }

        @Override
        public void write(final Object target, final Object value) {
            try {
                setter.invokeExact(target, value);
            } catch (final Throwable t) {
                reflectWrite(field, target, value);
            }
        }
    }

    private record StaticVarHandleFieldAccessor(Field field, MethodHandle getter, MethodHandle setter)
            implements FieldAccessor {
        @Override
        public Object read(final Object target) {
            try {
                return getter.invokeExact();
            } catch (final Throwable t) {
                return reflectRead(field, target);
            }
        }

        @Override
        public void write(final Object target, final Object value) {
            try {
                setter.invokeExact(value);
            } catch (final Throwable t) {
                reflectWrite(field, target, value);
            }
        }
    }
}