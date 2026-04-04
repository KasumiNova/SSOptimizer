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

    private final Reference2ObjectOpenHashMap<Class<?>, OwnerFieldCache> ownerCaches =
            new Reference2ObjectOpenHashMap<>();

    private Class<?>        lastOwnerType;
    private OwnerFieldCache lastOwnerCache;

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
        final Object2ObjectOpenHashMap<String, Object> fieldCache = ownerCacheFor(ownerType).fieldCacheFor(definedIn);
        final Object cached = fieldCache.get(fieldName);
        if (cached != null) {
            return cached == MISSING ? null : (Field) cached;
        }

        synchronized (fieldCache) {
            final Object rechecked = fieldCache.get(fieldName);
            if (rechecked != null) {
                return rechecked == MISSING ? null : (Field) rechecked;
            }

            final Field resolved = resolver.get();
            fieldCache.put(fieldName, resolved != null ? resolved : MISSING);
            return resolved;
        }
    }

    /**
     * 清空缓存。
     */
    public synchronized void clear() {
        ownerCaches.clear();
        lastOwnerType = null;
        lastOwnerCache = null;
    }

    private OwnerFieldCache ownerCacheFor(final Class<?> ownerType) {
        if (ownerType == lastOwnerType && lastOwnerCache != null) {
            return lastOwnerCache;
        }

        final OwnerFieldCache cached = ownerCaches.get(ownerType);
        if (cached != null) {
            lastOwnerType = ownerType;
            lastOwnerCache = cached;
            return cached;
        }

        synchronized (this) {
            final OwnerFieldCache rechecked = ownerCaches.get(ownerType);
            if (rechecked != null) {
                lastOwnerType = ownerType;
                lastOwnerCache = rechecked;
                return rechecked;
            }

            final OwnerFieldCache created = new OwnerFieldCache();
            ownerCaches.put(ownerType, created);
            lastOwnerType = ownerType;
            lastOwnerCache = created;
            return created;
        }
    }

    private static final class OwnerFieldCache {
        private final Object2ObjectOpenHashMap<String, Object> unqualifiedCache =
                new Object2ObjectOpenHashMap<>(16);
        private final Reference2ObjectOpenHashMap<Class<?>, Object2ObjectOpenHashMap<String, Object>> qualifiedCache =
                new Reference2ObjectOpenHashMap<>();

        private Class<?> lastDefinedIn;
        private Object2ObjectOpenHashMap<String, Object> lastQualifiedCache;

        private Object2ObjectOpenHashMap<String, Object> fieldCacheFor(final Class<?> definedIn) {
            if (definedIn == null) {
                return unqualifiedCache;
            }
            if (definedIn == lastDefinedIn && lastQualifiedCache != null) {
                return lastQualifiedCache;
            }

            final Object2ObjectOpenHashMap<String, Object> cached = qualifiedCache.get(definedIn);
            if (cached != null) {
                lastDefinedIn = definedIn;
                lastQualifiedCache = cached;
                return cached;
            }

            synchronized (this) {
                final Object2ObjectOpenHashMap<String, Object> rechecked = qualifiedCache.get(definedIn);
                if (rechecked != null) {
                    lastDefinedIn = definedIn;
                    lastQualifiedCache = rechecked;
                    return rechecked;
                }

                final Object2ObjectOpenHashMap<String, Object> created = new Object2ObjectOpenHashMap<>(8);
                qualifiedCache.put(definedIn, created);
                lastDefinedIn = definedIn;
                lastQualifiedCache = created;
                return created;
            }
        }
    }
}