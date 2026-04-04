package github.kasuminova.ssoptimizer.common.save;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;

import java.lang.reflect.Field;
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

    private final Reference2ObjectOpenHashMap<Class<?>, Object2ObjectOpenHashMap<String, Object>> unqualifiedCache =
        new Reference2ObjectOpenHashMap<>();
    private final Reference2ObjectOpenHashMap<Class<?>, Reference2ObjectOpenHashMap<Class<?>, Object2ObjectOpenHashMap<String, Object>>> qualifiedCache =
        new Reference2ObjectOpenHashMap<>();

    /**
     * 按查询键解析字段，并缓存解析结果。
     *
     * @param ownerType  发起查找的目标类型
     * @param fieldName  字段名
     * @param definedIn  期望声明类；若为 {@code null} 则按 XStream 的未限定查找语义处理
     * @param resolver   首次未命中缓存时执行的真实解析逻辑
     * @return 找到的字段；若不存在则返回 {@code null}
     */
    public synchronized Field getOrResolve(final Class<?> ownerType,
                                           final String fieldName,
                                           final Class<?> definedIn,
                                           final Supplier<Field> resolver) {
        final Object2ObjectOpenHashMap<String, Object> fieldCache = cacheFor(ownerType, definedIn);
        final Object cached = fieldCache.get(fieldName);
        if (cached != null) {
            return cached == MISSING ? null : (Field) cached;
        }

        final Field resolved = resolver.get();
        fieldCache.put(fieldName, resolved != null ? resolved : MISSING);
        return resolved;
    }

    /**
     * 清空缓存。
     */
    public synchronized void clear() {
        unqualifiedCache.clear();
        qualifiedCache.clear();
    }

    private Object2ObjectOpenHashMap<String, Object> cacheFor(final Class<?> ownerType,
                                                              final Class<?> definedIn) {
        if (definedIn == null) {
            return getOrCreateFieldCache(unqualifiedCache, ownerType);
        }

        final Reference2ObjectOpenHashMap<Class<?>, Object2ObjectOpenHashMap<String, Object>> declaredTypeCache =
                getOrCreateDeclaredTypeCache(ownerType);
        return getOrCreateFieldCache(declaredTypeCache, definedIn);
    }

    private Reference2ObjectOpenHashMap<Class<?>, Object2ObjectOpenHashMap<String, Object>> getOrCreateDeclaredTypeCache(final Class<?> ownerType) {
        final Reference2ObjectOpenHashMap<Class<?>, Object2ObjectOpenHashMap<String, Object>> cached = qualifiedCache.get(ownerType);
        if (cached != null) {
            return cached;
        }

        final Reference2ObjectOpenHashMap<Class<?>, Object2ObjectOpenHashMap<String, Object>> created =
                new Reference2ObjectOpenHashMap<>();
        qualifiedCache.put(ownerType, created);
        return created;
    }

    private static <K> Object2ObjectOpenHashMap<String, Object> getOrCreateFieldCache(final Reference2ObjectOpenHashMap<K, Object2ObjectOpenHashMap<String, Object>> cache,
                                                                                       final K key) {
        final Object2ObjectOpenHashMap<String, Object> cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        final Object2ObjectOpenHashMap<String, Object> created = new Object2ObjectOpenHashMap<>();
        cache.put(key, created);
        return created;
    }
}