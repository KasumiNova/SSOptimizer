package github.kasuminova.ssoptimizer.mixin.save;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * XStream {@code MemberStore} 的 Mixin 重写。
 * <p>
 * 注入目标：{@code com.thoughtworks.xstream.core.util.MemberStore}<br>
 * 注入动机：{@code SerializationMembers}（readResolve/writeReplace/readObject 等序列化回调查找）
 * 使用 {@code MemberStore.newSynchronizedInstance()}，外层与内层均为
 * {@code Collections.synchronizedMap(HashMap)}。读档时每次序列化回调解析都会查询该缓存，
 * synchronized 包装在高频路径上产生可观开销。缓存填充为幂等操作，{@link ConcurrentHashMap}
 * 语义等价且读路径无锁。<br>
 * 注入效果：构造尾部将 {@code types} 替换为 {@link ConcurrentHashMap}，
 * 并重写 {@code get}/{@code put} 为无锁读 + 幂等填充实现。
 */
@Mixin(targets = "com.thoughtworks.xstream.core.util.MemberStore")
public abstract class XStreamMemberStoreMixin {
    @Final
    @Mutable
    @Shadow
    private Map types;

    @Unique
    private final Map<String, Object> ssoptimizer$nullClassStore = new ConcurrentHashMap<>();

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void ssoptimizer$replaceStoreWithConcurrentMap(final CallbackInfo ci) {
        this.types = new ConcurrentHashMap();
    }

    /**
     * 查询类型成员的缓存值（无锁读路径）。
     *
     * @param definedIn 成员所属类型，可为 null
     * @param member    成员名
     * @return 缓存值或 null
     * @author KasumiNova
     * @reason 消除外层 synchronizedMap 的读锁开销；幂等缓存读无需同步。
     *         definedIn 为 null 的条目走独立内层表（ConcurrentHashMap 不接受 null key）。
     */
    @SuppressWarnings("unchecked")
    @Overwrite(remap = false)
    public Object get(final Class definedIn, final String member) {
        if (definedIn == null) {
            return ssoptimizer$nullClassStore.get(member);
        }
        final Map<String, Object> store = (Map<String, Object>) this.types.get(definedIn.getName());
        return store == null ? null : store.get(member);
    }

    /**
     * 写入类型成员的缓存值（幂等填充）。
     *
     * @param definedIn 成员所属类型，可为 null
     * @param member    成员名
     * @param value     缓存值
     * @return 旧值或 null
     * @author KasumiNova
     * @reason 外层 computeIfAbsent 保证内层表单次创建，内层 ConcurrentHashMap 消除写锁。
     *         definedIn 为 null 的条目走独立内层表（ConcurrentHashMap 不接受 null key）。
     */
    @SuppressWarnings("unchecked")
    @Overwrite(remap = false)
    public Object put(final Class definedIn, final String member, final Object value) {
        if (definedIn == null) {
            return ssoptimizer$nullClassStore.put(member, value);
        }
        final Map<String, Object> store = (Map<String, Object>) ((ConcurrentHashMap<String, Object>) this.types)
                .computeIfAbsent(definedIn.getName(), key -> new ConcurrentHashMap<>());
        return store.put(member, value);
    }
}
