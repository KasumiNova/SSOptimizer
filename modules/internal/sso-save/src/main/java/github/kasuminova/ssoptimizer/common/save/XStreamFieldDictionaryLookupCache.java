package github.kasuminova.ssoptimizer.common.save;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;

import java.util.function.Supplier;

/** 底层字典缓存清空时必须同步清空此缓存。 */
public final class XStreamFieldDictionaryLookupCache<T> {
    private static final Object MISSING = new Object();

    private final Reference2ObjectOpenHashMap<Class<?>, OwnerFieldCache> ownerCaches =
            new Reference2ObjectOpenHashMap<>();

    private Class<?>        lastOwnerType;
    private OwnerFieldCache lastOwnerCache;

    @SuppressWarnings("unchecked")
    public T getOrResolve(final Class<?> ownerType,
                          final String fieldName,
                          final Class<?> definedIn,
                          final Supplier<T> resolver) {
        final Object2ObjectOpenHashMap<String, Object> fieldCache = ownerCacheFor(ownerType).fieldCacheFor(definedIn);
        final Object cached = fieldCache.get(fieldName);
        if (cached != null) {
            return cached == MISSING ? null : (T) cached;
        }

        synchronized (fieldCache) {
            final Object rechecked = fieldCache.get(fieldName);
            if (rechecked != null) {
                return rechecked == MISSING ? null : (T) rechecked;
            }

            final T resolved = resolver.get();
            fieldCache.put(fieldName, resolved != null ? resolved : MISSING);
            return resolved;
        }
    }

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