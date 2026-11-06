package github.kasuminova.ssoptimizer.common.save;

import java.lang.ref.Reference;
import java.util.Map;

/** 可复用探针依赖 HashMap 由查询 key 调用 equals(mapKey) 的实现细节。 */
public final class XStreamObjectIdDictionaryHelper {
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

    private static Object unwrapWrappedObject(final Object wrapper) {
        if (wrapper instanceof ReusableIdProbe probe) {
            return probe.target();
        }
        if (wrapper instanceof Reference<?> reference) {
            return reference.get();
        }
        throw new IllegalStateException("不支持的 XStream 对象 ID 包装器类型: " + wrapper.getClass().getName());
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