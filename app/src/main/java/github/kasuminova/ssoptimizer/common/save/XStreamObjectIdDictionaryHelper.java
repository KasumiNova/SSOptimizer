package github.kasuminova.ssoptimizer.common.save;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * XStream {@code ObjectIdDictionary} 查询辅助类。
 * <p>
 * 职责：为 {@code lookupId/containsId/removeId} 提供可复用的线程本地探针 key，
 * 避免 XStream 原实现每次查询都分配新的 {@code IdWrapper} 包装对象。<br>
 * 设计动机：热点报告显示对象引用字典的查找路径在大存档下会频繁触发 `HashMap.get/containsKey/remove`
 * 与包装对象分配；这里利用 `HashMap` 以“查询 key 调用 equals(mapKey)”的实现细节，
 * 复用一个仅用于查询的可变探针。<br>
 * 兼容性策略：只替换查询 key 的构造方式，不改变 XStream 现有的弱引用 key 存储、引用语义与回收逻辑。
 */
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

    private static final ThreadLocal<MutableIdProbe> LOOKUP_PROBE = ThreadLocal.withInitial(MutableIdProbe::new);

    private XStreamObjectIdDictionaryHelper() {
    }

    /**
     * 查询对象的引用 ID。
     *
     * @param map  XStream 内部对象 ID 字典
     * @param item 待查询对象
     * @return 命中的引用 ID；若不存在则返回 {@code null}
     */
    public static Object lookupId(final Map<?, ?> map,
                                  final Object item) {
        final MutableIdProbe probe = LOOKUP_PROBE.get();
        probe.bind(item);
        try {
            return map.get(probe);
        } finally {
            probe.reset();
        }
    }

    /**
     * 判断对象是否已存在引用 ID。
     *
     * @param map  XStream 内部对象 ID 字典
     * @param item 待查询对象
     * @return 若存在引用 ID 则返回 {@code true}
     */
    public static boolean containsId(final Map<?, ?> map,
                                     final Object item) {
        final MutableIdProbe probe = LOOKUP_PROBE.get();
        probe.bind(item);
        try {
            return map.containsKey(probe);
        } finally {
            probe.reset();
        }
    }

    /**
     * 删除对象对应的引用 ID。
     *
     * @param map  XStream 内部对象 ID 字典
     * @param item 待删除对象
     */
    public static void removeId(final Map<?, ?> map,
                                final Object item) {
        final MutableIdProbe probe = LOOKUP_PROBE.get();
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
        if (wrapper instanceof MutableIdProbe probe) {
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

    private static final class MutableIdProbe {
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