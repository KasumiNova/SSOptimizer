package github.kasuminova.ssoptimizer.mixin.save;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * XStream {@code CachingMapper} 的 Mixin 重写。
 * <p>
 * 注入目标：{@code com.thoughtworks.xstream.mapper.CachingMapper}<br>
 * 注入动机：{@code realClassCache} 使用 {@code Collections.synchronizedMap(HashMap)}，
 * 读档时每个 XML 元素节点都要经 {@code realClass} 查询一次，百万级调用下 synchronized
 * 包装的开销可观。缓存内容为「元素名 → Class/异常」的幂等映射，用 {@link ConcurrentHashMap}
 * 即可保持语义且读路径无锁。<br>
 * 注入效果：覆盖 {@code readResolve}，将缓存替换为 {@link ConcurrentHashMap}。
 */
@Mixin(targets = "com.thoughtworks.xstream.mapper.CachingMapper")
public abstract class XStreamCachingMapperMixin {
    @Shadow
    private transient Map realClassCache;

    /**
     * 将同步包装缓存替换为并发哈希表。
     *
     * @return this
     * @author KasumiNova
     * @reason 幂等缓存映射无需 synchronized 包装，ConcurrentHashMap 读路径无锁。
     */
    @Overwrite(remap = false)
    private Object readResolve() {
        this.realClassCache = new ConcurrentHashMap<>(128);
        return this;
    }
}
