package github.kasuminova.ssoptimizer.common.save;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * XStream 字段别名查询结果缓存。
 * <p>
 * 职责：缓存 {@code FieldAliasingMapper.serializedMember/realMember} 的最终结果，
 * 避免保存阶段对同一 {@code (type, memberName)} 组合反复沿继承链查找别名映射并回退到后续 mapper 链。<br>
 * 兼容性策略：缓存仅记忆目标 mapper 实例已经解析出的最终字符串结果；当字段别名配置发生变化时，
 * 调用方必须显式调用 {@link #clear()} 失效缓存，确保与 XStream 原始行为保持一致。
 */
public final class XStreamFieldAliasingCache {
    private static final Object MISSING = new Object();

    private final ConcurrentMap<Class<?>, ConcurrentMap<String, Object>> serializedMemberCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<Class<?>, ConcurrentMap<String, Object>> realMemberCache = new ConcurrentHashMap<>();

    /**
     * 获取或解析序列化字段名。
     *
     * @param type      声明类型
     * @param memberName 原始字段名
     * @param resolver  缓存未命中时的真实解析逻辑
     * @return 解析后的序列化字段名；若解析器返回 {@code null}，则返回 {@code null}
     */
    public String getOrResolveSerializedMember(final Class<?> type,
                                               final String memberName,
                                               final Supplier<String> resolver) {
        return getOrResolve(serializedMemberCache, type, memberName, resolver);
    }

    /**
     * 获取或解析真实字段名。
     *
     * @param type       声明类型
     * @param memberName 序列化字段名
     * @param resolver   缓存未命中时的真实解析逻辑
     * @return 解析后的真实字段名；若解析器返回 {@code null}，则返回 {@code null}
     */
    public String getOrResolveRealMember(final Class<?> type,
                                         final String memberName,
                                         final Supplier<String> resolver) {
        return getOrResolve(realMemberCache, type, memberName, resolver);
    }

    /**
     * 清空全部缓存结果。
     */
    public void clear() {
        serializedMemberCache.clear();
        realMemberCache.clear();
    }

    private static String getOrResolve(final ConcurrentMap<Class<?>, ConcurrentMap<String, Object>> cache,
                                       final Class<?> type,
                                       final String memberName,
                                       final Supplier<String> resolver) {
        final ConcurrentMap<String, Object> memberCache = cache.computeIfAbsent(type, ignored -> new ConcurrentHashMap<>());
        final Object cached = memberCache.get(memberName);
        if (cached != null) {
            return cached == MISSING ? null : (String) cached;
        }

        final String resolved = resolver.get();
        final Object value = resolved != null ? resolved : MISSING;
        final Object previous = memberCache.putIfAbsent(memberName, value);
        final Object effective = previous != null ? previous : value;
        return effective == MISSING ? null : (String) effective;
    }
}
