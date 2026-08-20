package github.kasuminova.ssoptimizer.common.save;

import com.thoughtworks.xstream.converters.Converter;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;

/**
 * XStream 转换器查询快缓存。
 * <p>
 * 职责：为 {@code DefaultConverterLookup.lookupConverterForType(Class<?>)} 提供实例级正向结果缓存，
 * 在热点类型重复查询时绕过 XStream 原有的同步 {@code WeakHashMap} 读取开销。<br>
 * 设计动机：更新后的热点报告显示 {@code DefaultConverterLookup} 的同步缓存读取仍然占据稳定 CPU，
 * 而保存过程中的转换器映射大多是重复命中；这里增加一层无锁正向缓存，把热路径查询降到普通并发 map。<br>
 * 兼容性策略：仅缓存已经成功解析出的 {@link Converter}，不缓存失败结果；当底层转换器注册表变化或
 * XStream 主缓存被清空时，调用方必须同步调用 {@link #clear()}，确保命中结果与原始语义保持一致。
 */
public final class XStreamConverterLookupCache {
    private final Reference2ObjectOpenHashMap<Class<?>, Converter> converters = new Reference2ObjectOpenHashMap<>();

    /**
     * 查询指定类型已缓存的转换器。
     *
     * @param type 目标类型
     * @return 已缓存的转换器；若未命中则返回 {@code null}
     */
    public synchronized Converter lookup(final Class<?> type) {
        if (type == null) {
            return null;
        }
        return converters.get(type);
    }

    /**
     * 记录指定类型对应的转换器。
     *
     * @param type      目标类型
     * @param converter 已解析出的转换器
     */
    public synchronized void remember(final Class<?> type,
                                      final Converter converter) {
        if (type == null || converter == null) {
            return;
        }
        converters.put(type, converter);
    }

    /**
     * 清空缓存。
     */
    public synchronized void clear() {
        converters.clear();
    }
}