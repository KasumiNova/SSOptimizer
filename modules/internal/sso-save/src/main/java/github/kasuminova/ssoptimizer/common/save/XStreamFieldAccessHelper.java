package github.kasuminova.ssoptimizer.common.save;

import com.thoughtworks.xstream.converters.reflection.ObjectAccessException;
import org.apache.log4j.Logger;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.invoke.WrongMethodTypeException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

public final class XStreamFieldAccessHelper {
    public static final String DISABLE_FAST_ACCESS_PROPERTY = "ssoptimizer.disable.xstream.fastfieldaccess";

    private static final Logger LOGGER = Logger.getLogger(XStreamFieldAccessHelper.class);
    private static final long FALLBACK_LOG_INTERVAL_NANOS = 5_000_000_000L;
    private static final boolean FAST_ACCESS_ENABLED = !Boolean.getBoolean(DISABLE_FAST_ACCESS_PROPERTY);
    private static final ConcurrentMap<Field, FieldAccessor> ACCESSOR_CACHE = new ConcurrentHashMap<>();
    private static final AtomicLong LAST_FALLBACK_LOG_NANOS = new AtomicLong();

    private XStreamFieldAccessHelper() {
    }

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
        } catch (final IllegalAccessException | WrongMethodTypeException | ClassCastException e) {
            logHandleFallback("读取", field, e);
            return reflectReadAfterHandleFailure(field, target, e);
        } catch (final RuntimeException | Error e) {
            throw e;
        } catch (final Throwable t) {
            throw wrap("Cannot read field", field.getType(), field.getName(), t);
        }
    }

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
        } catch (final IllegalAccessException | WrongMethodTypeException | ClassCastException e) {
            logHandleFallback("写入", field, e);
            reflectWriteAfterHandleFailure(field, target, value, e);
        } catch (final RuntimeException | Error e) {
            throw e;
        } catch (final Throwable t) {
            throw wrap("Cannot write field", field.getType(), field.getName(), t);
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
            return new ReflectionFieldAccessor(field, null);
        }

        try {
            final MethodHandles.Lookup privateLookup = MethodHandles.privateLookupIn(
                    field.getDeclaringClass(),
                    MethodHandles.lookup()
            );
            final VarHandle handle = privateLookup.unreflectVarHandle(field);
            // 预先适配固定签名，避免 VarHandle 多态调用点反复重定制 LambdaForm。
            if (Modifier.isStatic(field.getModifiers())) {
                final MethodHandle getter = handle.toMethodHandle(VarHandle.AccessMode.GET)
                        .asType(MethodType.methodType(Object.class));
                final MethodHandle setter = handle.toMethodHandle(VarHandle.AccessMode.SET)
                        .asType(MethodType.methodType(void.class, Object.class));
                return new StaticVarHandleFieldAccessor(getter, setter);
            }
            final MethodHandle getter = handle.toMethodHandle(VarHandle.AccessMode.GET)
                    .asType(MethodType.methodType(Object.class, Object.class));
            final MethodHandle setter = handle.toMethodHandle(VarHandle.AccessMode.SET)
                    .asType(MethodType.methodType(void.class, Object.class, Object.class));
            return new InstanceVarHandleFieldAccessor(getter, setter);
        } catch (final IllegalAccessException | SecurityException | WrongMethodTypeException | ClassCastException e) {
            logHandleFallback("初始化", field, e);
            return new ReflectionFieldAccessor(field, e);
        }
    }

    private static Object reflectReadAfterHandleFailure(final Field field,
                                                        final Object target,
                                                        final Throwable handleFailure) {
        try {
            return reflectRead(field, target);
        } catch (final RuntimeException reflectionFailure) {
            reflectionFailure.addSuppressed(handleFailure);
            throw reflectionFailure;
        }
    }

    private static void reflectWriteAfterHandleFailure(final Field field,
                                                       final Object target,
                                                       final Object value,
                                                       final Throwable handleFailure) {
        try {
            reflectWrite(field, target, value);
        } catch (final RuntimeException reflectionFailure) {
            reflectionFailure.addSuppressed(handleFailure);
            throw reflectionFailure;
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

    private static void logHandleFallback(final String operation,
                                          final Field field,
                                          final Throwable cause) {
        final long now = System.nanoTime();
        final long lastLog = LAST_FALLBACK_LOG_NANOS.get();
        if (lastLog != 0L && now - lastLog < FALLBACK_LOG_INTERVAL_NANOS) {
            return;
        }
        if (!LAST_FALLBACK_LOG_NANOS.compareAndSet(lastLog, now)) {
            return;
        }
        LOGGER.warn("[SSOptimizer] XStream 字段" + operation + "句柄访问失败，回退反射: "
                + field.getDeclaringClass().getName() + '.' + field.getName(), cause);
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
        Object read(Object target) throws Throwable;

        void write(Object target, Object value) throws Throwable;
    }

    private record ReflectionFieldAccessor(Field field, Throwable handleFailure) implements FieldAccessor {
        @Override
        public Object read(final Object target) {
            if (handleFailure != null) {
                return reflectReadAfterHandleFailure(field, target, handleFailure);
            }
            return reflectRead(field, target);
        }

        @Override
        public void write(final Object target, final Object value) {
            if (handleFailure != null) {
                reflectWriteAfterHandleFailure(field, target, value, handleFailure);
                return;
            }
            reflectWrite(field, target, value);
        }
    }

    private record InstanceVarHandleFieldAccessor(MethodHandle getter, MethodHandle setter)
            implements FieldAccessor {
        @Override
        public Object read(final Object target) throws Throwable {
            return getter.invokeExact(target);
        }

        @Override
        public void write(final Object target, final Object value) throws Throwable {
            setter.invokeExact(target, value);
        }
    }

    private record StaticVarHandleFieldAccessor(MethodHandle getter, MethodHandle setter)
            implements FieldAccessor {
        @Override
        public Object read(final Object target) throws Throwable {
            return getter.invokeExact();
        }

        @Override
        public void write(final Object target, final Object value) throws Throwable {
            setter.invokeExact(value);
        }
    }
}
