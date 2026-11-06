package github.kasuminova.ssoptimizer.common.save;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;

import java.util.function.Supplier;

/** 字段别名配置变化后必须清空此缓存。 */
public final class XStreamFieldAliasingCache {
    private static final Object MISSING = new Object();

    private final Reference2ObjectOpenHashMap<Class<?>, Object2ObjectOpenHashMap<String, Object>> serializedMemberCache =
        new Reference2ObjectOpenHashMap<>();
    private final Reference2ObjectOpenHashMap<Class<?>, Object2ObjectOpenHashMap<String, Object>> realMemberCache =
        new Reference2ObjectOpenHashMap<>();

    public String getOrResolveSerializedMember(final Class<?> type,
                                               final String memberName,
                                               final Supplier<String> resolver) {
        return getOrResolve(serializedMemberCache, type, memberName, resolver);
    }

    public String getOrResolveRealMember(final Class<?> type,
                                         final String memberName,
                                         final Supplier<String> resolver) {
        return getOrResolve(realMemberCache, type, memberName, resolver);
    }

    public synchronized void clear() {
        serializedMemberCache.clear();
        realMemberCache.clear();
    }

    private synchronized String getOrResolve(final Reference2ObjectOpenHashMap<Class<?>, Object2ObjectOpenHashMap<String, Object>> cache,
                                             final Class<?> type,
                                             final String memberName,
                                             final Supplier<String> resolver) {
        final Object2ObjectOpenHashMap<String, Object> memberCache = getOrCreateMemberCache(cache, type);
        final Object cached = memberCache.get(memberName);
        if (cached != null) {
            return cached == MISSING ? null : (String) cached;
        }

        final String resolved = resolver.get();
        memberCache.put(memberName, resolved != null ? resolved : MISSING);
        return resolved;
    }

    private static Object2ObjectOpenHashMap<String, Object> getOrCreateMemberCache(final Reference2ObjectOpenHashMap<Class<?>, Object2ObjectOpenHashMap<String, Object>> cache,
                                                                                    final Class<?> type) {
        final Object2ObjectOpenHashMap<String, Object> cached = cache.get(type);
        if (cached != null) {
            return cached;
        }

        final Object2ObjectOpenHashMap<String, Object> created = new Object2ObjectOpenHashMap<>();
        cache.put(type, created);
        return created;
    }
}
