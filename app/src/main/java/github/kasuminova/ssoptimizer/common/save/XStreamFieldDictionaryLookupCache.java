package github.kasuminova.ssoptimizer.common.save;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * XStream {@code FieldDictionary} 查询缓存。
 * <p>
 * 职责：缓存 {@code (ownerType, fieldName, definedIn)} 到 {@link Field} 的解析结果，覆盖命中与未命中两种情况，
 * 减少 XStream 在属性映射与反序列化阶段对同一字段反复调用 {@code buildMap(...).get(...)} 的开销。<br>
 * 兼容性策略：缓存只记忆最终解析结果，不改变 XStream 原有字段可见性、继承层级与异常语义；
 * 当底层字典缓存被清空时，调用方需同步调用 {@link #clear()} 清除此查询缓存。
 */
public final class XStreamFieldDictionaryLookupCache {
    private static final Object MISSING = new Object();

    private final ConcurrentMap<Class<?>, ConcurrentMap<String, Object>> unqualifiedCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<Class<?>, ConcurrentMap<Class<?>, ConcurrentMap<String, Object>>> qualifiedCache = new ConcurrentHashMap<>();

    /**
     * 按查询键解析字段，并缓存解析结果。
     *
     * @param ownerType  发起查找的目标类型
     * @param fieldName  字段名
     * @param definedIn  期望声明类；若为 {@code null} 则按 XStream 的未限定查找语义处理
     * @param resolver   首次未命中缓存时执行的真实解析逻辑
     * @return 找到的字段；若不存在则返回 {@code null}
     */
    public Field getOrResolve(final Class<?> ownerType,
                              final String fieldName,
                              final Class<?> definedIn,
                              final Supplier<Field> resolver) {
        final ConcurrentMap<String, Object> fieldCache = cacheFor(ownerType, definedIn);
        final Object cached = fieldCache.get(fieldName);
        if (cached != null) {
            return cached == MISSING ? null : (Field) cached;
        }

        final Field resolved = resolver.get();
        final Object value = resolved != null ? resolved : MISSING;
        final Object previous = fieldCache.putIfAbsent(fieldName, value);
        final Object effective = previous != null ? previous : value;
        return effective == MISSING ? null : (Field) effective;
    }

    /**
     * 清空缓存。
     */
    public void clear() {
        unqualifiedCache.clear();
        qualifiedCache.clear();
    }

    private ConcurrentMap<String, Object> cacheFor(final Class<?> ownerType,
                                                   final Class<?> definedIn) {
        if (definedIn == null) {
            return getOrCreateFieldCache(unqualifiedCache, ownerType);
        }

        final ConcurrentMap<Class<?>, ConcurrentMap<String, Object>> declaredTypeCache = getOrCreateDeclaredTypeCache(ownerType);
        return getOrCreateFieldCache(declaredTypeCache, definedIn);
    }

    private ConcurrentMap<Class<?>, ConcurrentMap<String, Object>> getOrCreateDeclaredTypeCache(final Class<?> ownerType) {
        final ConcurrentMap<Class<?>, ConcurrentMap<String, Object>> cached = qualifiedCache.get(ownerType);
        if (cached != null) {
            return cached;
        }

        final ConcurrentMap<Class<?>, ConcurrentMap<String, Object>> created = new ConcurrentHashMap<>();
        final ConcurrentMap<Class<?>, ConcurrentMap<String, Object>> previous = qualifiedCache.putIfAbsent(ownerType, created);
        return previous != null ? previous : created;
    }

    private static <K> ConcurrentMap<String, Object> getOrCreateFieldCache(final ConcurrentMap<K, ConcurrentMap<String, Object>> cache,
                                                                           final K key) {
        final ConcurrentMap<String, Object> cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        final ConcurrentMap<String, Object> created = new ConcurrentHashMap<>();
        final ConcurrentMap<String, Object> previous = cache.putIfAbsent(key, created);
        return previous != null ? previous : created;
    }
}