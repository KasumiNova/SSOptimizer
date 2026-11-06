package github.kasuminova.ssoptimizer.common.save;

import com.thoughtworks.xstream.converters.reflection.ObjectAccessException;
import org.apache.log4j.Logger;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.invoke.WrongMethodTypeException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class XStreamFieldAccessHelper {
    public static final String DISABLE_FAST_ACCESS_PROPERTY = "ssoptimizer.disable.xstream.fastfieldaccess";

    private static final Logger LOGGER = Logger.getLogger(XStreamFieldAccessHelper.class);
    private static final boolean FAST_ACCESS_ENABLED = !Boolean.getBoolean(DISABLE_FAST_ACCESS_PROPERTY);
    private static final ConcurrentMap<Field, FieldAccessor> ACCESSOR_CACHE = new ConcurrentHashMap<>();

    private XStreamFieldAccessHelper() {
    }

    public static Object read(final Field field, final Object target) {
        final FieldAccessor accessor = accessorFor(field);
        try {
            return accessor.read(target);
        } catch (final WrongMethodTypeException | ClassCastException e) {
            throw fail("读取", "Cannot read field", field, e);
        } catch (final RuntimeException e) {
            throw e;
        } catch (final Error e) {
            throw e;
        }
    }

    public static void write(final Field field, final Object target, final Object value) {
        if (Modifier.isFinal(field.getModifiers())) {
            throw fail("写入", "Cannot write final field without reflection", field,
                    new IllegalAccessException("VarHandle 不支持写入 final 字段"));
        }

        final FieldAccessor accessor = accessorFor(field);
        try {
            accessor.write(target, value);
        } catch (final WrongMethodTypeException | ClassCastException e) {
            throw fail("写入", "Cannot write field", field, e);
        } catch (final RuntimeException e) {
            throw e;
        } catch (final Error e) {
            throw e;
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
        if (!FAST_ACCESS_ENABLED) {
            throw fail("初始化", "Fast field access is disabled", field,
                    new IllegalStateException("XStream 字段句柄访问已被禁用"));
        }

        try {
            final MethodHandles.Lookup privateLookup = MethodHandles.privateLookupIn(
                    field.getDeclaringClass(),
                    MethodHandles.lookup()
            );
            final VarHandle handle = privateLookup.unreflectVarHandle(field);
            return createAccessor(handle, Modifier.isStatic(field.getModifiers()));
        } catch (final IllegalAccessException | SecurityException e) {
            throw fail("初始化", "Cannot create field accessor", field, e);
        }
    }

    static FieldAccessor createAccessor(final VarHandle handle, final boolean staticField) {
        return staticField
                ? new StaticVarHandleFieldAccessor(handle)
                : new InstanceVarHandleFieldAccessor(handle);
    }

    private static ObjectAccessException fail(final String operation,
                                              final String message,
                                              final Field field,
                                              final Throwable cause) {
        LOGGER.error("[SSOptimizer] XStream 字段" + operation + "句柄访问失败: "
                + field.getDeclaringClass().getName() + '.' + field.getName(), cause);
        return wrap(message, field.getType(), field.getName(), cause);
    }

    private static ObjectAccessException wrap(final String message,
                                              final Class<?> fieldType,
                                              final String fieldName,
                                              final Throwable cause) {
        final ObjectAccessException exception = new ObjectAccessException(message, cause);
        exception.add("field", fieldType.getName() + '.' + fieldName);
        return exception;
    }

    interface FieldAccessor {
        Object read(Object target);

        void write(Object target, Object value);
    }

    private record InstanceVarHandleFieldAccessor(VarHandle handle) implements FieldAccessor {
        @Override
        public Object read(final Object target) {
            return handle.get(target);
        }

        @Override
        public void write(final Object target, final Object value) {
            handle.set(target, value);
        }
    }

    private record StaticVarHandleFieldAccessor(VarHandle handle) implements FieldAccessor {
        @Override
        public Object read(final Object target) {
            return handle.get();
        }

        @Override
        public void write(final Object target, final Object value) {
            handle.set(value);
        }
    }
}
