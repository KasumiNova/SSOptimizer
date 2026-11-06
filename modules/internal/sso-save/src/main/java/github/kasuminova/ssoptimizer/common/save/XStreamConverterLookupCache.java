package github.kasuminova.ssoptimizer.common.save;

import com.thoughtworks.xstream.converters.Converter;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;

/** 转换器注册表变化或 XStream 主缓存清空时必须同步清空此缓存。 */
public final class XStreamConverterLookupCache {
    private final Reference2ObjectOpenHashMap<Class<?>, Converter> converters = new Reference2ObjectOpenHashMap<>();

    public synchronized Converter lookup(final Class<?> type) {
        if (type == null) {
            return null;
        }
        return converters.get(type);
    }

    public synchronized void remember(final Class<?> type,
                                      final Converter converter) {
        if (type == null || converter == null) {
            return;
        }
        converters.put(type, converter);
    }

    public synchronized void clear() {
        converters.clear();
    }
}