package github.kasuminova.ssoptimizer.common.save;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Map;

/** 可复用探针依赖 HashMap 由查询 key 调用 equals(mapKey) 的实现细节。 */
public final class XStreamObjectIdDictionaryHelper {
    private static final ClassValue<WrapperAccessor> WRAPPER_ACCESSORS = new ClassValue<>() {
        @Override
        protected WrapperAccessor computeValue(final Class<?> type) {
            try {
                final Method method = findGetMethod(type);
                method.setAccessible(true);
                try {
                    final MethodHandle handle = MethodHandles.privateLookupIn(type, MethodHandles.lookup())
                                                             .unreflect(method)
                                                             .asType(MethodType.methodType(Object.class, Object.class));
                    return new MethodHandleWrapperAccessor(handle);
                } catch (final IllegalAccessException ignored) {
                    return new ReflectionWrapperAccessor(method);
                }
            } catch (final ReflectiveOperationException e) {
                return UnsupportedWrapperAccessor.INSTANCE;
            }
        }
    };

    private static final ThreadLocal<ReusableIdProbe> LOOKUP_PROBE = ThreadLocal.withInitial(ReusableIdProbe::new);

    private XStreamObjectIdDictionaryHelper() {
    }

    public static ReusableIdProbe createReusableProbe() {
        return new ReusableIdProbe();
    }

    public static Object lookupId(final Map<?, ?> map,
                                  final Object item) {
        final ReusableIdProbe probe = LOOKUP_PROBE.get();
        return lookupId(map, item, probe);
    }

    public static Object lookupId(final Map<?, ?> map,
                                  final Object item,
                                  final ReusableIdProbe probe) {
        probe.bind(item);
        try {
            return map.get(probe);
        } finally {
            probe.reset();
        }
    }

    public static boolean containsId(final Map<?, ?> map,
                                     final Object item) {
        final ReusableIdProbe probe = LOOKUP_PROBE.get();
        return containsId(map, item, probe);
    }

    public static boolean containsId(final Map<?, ?> map,
                                     final Object item,
                                     final ReusableIdProbe probe) {
        probe.bind(item);
        try {
            return map.containsKey(probe);
        } finally {
            probe.reset();
        }
    }

    public static void removeId(final Map<?, ?> map,
                                final Object item) {
        final ReusableIdProbe probe = LOOKUP_PROBE.get();
        removeId(map, item, probe);
    }

    public static void removeId(final Map<?, ?> map,
                                final Object item,
                                final ReusableIdProbe probe) {
        probe.bind(item);
        try {
            map.remove(probe);
        } finally {
            probe.reset();
        }
    }

    private static Method findGetMethod(final Class<?> type) throws NoSuchMethodException {
        try {
            return type.getMethod("get");
        } catch (final NoSuchMethodException ignored) {
            return type.getDeclaredMethod("get");
        }
    }

    private static Object unwrapWrappedObject(final Object wrapper) {
        if (wrapper instanceof ReusableIdProbe probe) {
            return probe.target();
        }
        return WRAPPER_ACCESSORS.get(wrapper.getClass()).get(wrapper);
    }

    private interface WrapperAccessor {
        Object get(Object wrapper);
    }

    private record MethodHandleWrapperAccessor(MethodHandle handle) implements WrapperAccessor {
        @Override
        public Object get(final Object wrapper) {
            try {
                return handle.invokeExact(wrapper);
            } catch (final Throwable throwable) {
                throw new IllegalStateException("读取 XStream 对象 ID 包装器失败", throwable);
            }
        }
    }

    private record ReflectionWrapperAccessor(Method method) implements WrapperAccessor {
        @Override
        public Object get(final Object wrapper) {
            try {
                return method.invoke(wrapper);
            } catch (final ReflectiveOperationException e) {
                throw new IllegalStateException("通过反射读取 XStream 对象 ID 包装器失败", e);
            }
        }
    }

    private enum UnsupportedWrapperAccessor implements WrapperAccessor {
        INSTANCE;

        @Override
        public Object get(final Object wrapper) {
            throw new IllegalStateException("不支持的 XStream 对象 ID 包装器类型: " + wrapper.getClass().getName());
        }
    }

    public static final class ReusableIdProbe {
        private Object target;
        private int    identityHashCode;

        private void bind(final Object value) {
            target = value;
            identityHashCode = System.identityHashCode(value);
        }

        private Object target() {
            return target;
        }

        private void reset() {
            target = null;
            identityHashCode = 0;
        }

        @Override
        public int hashCode() {
            return identityHashCode;
        }

        @Override
        public boolean equals(final Object other) {
            if (this == other) {
                return true;
            }
            if (other == null) {
                return false;
            }
            return target == unwrapWrappedObject(other);
        }
    }
}