package github.kasuminova.ssoptimizer.mixin.save;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * XStream {@code StringConverter} 的 Mixin 重写。
 * <p>
 * 注入目标：{@code com.thoughtworks.xstream.converters.basic.StringConverter}<br>
 * 注入动机：默认构造使用 {@code Collections.synchronizedMap(new WeakCache())} 做字符串去重，
 * 读档时每个长度 ≤38 的字符串字段都会经 {@code fromString} 查询该缓存，是整个读档链路中
 * 调用频率最高的同步点之一（JFR 采样中 {@code SynchronizedMap.get} 与 {@code WeakHashMap.get}
 * 均出现在栈顶）。去重语义为幂等写入，{@link ConcurrentHashMap} 即可等价承载；
 * 短字符串去重集合规模有界（存档中唯一短字符串数量），放弃弱引用回收的内存代价可忽略。<br>
 * 注入效果：构造尾部把默认同步弱缓存替换为 {@link ConcurrentHashMap}；
 * 外部显式传入自定义 Map 的构造路径不干预（仅替换 synchronized 包装类型）。
 */
@Mixin(targets = "com.thoughtworks.xstream.converters.basic.StringConverter")
public abstract class XStreamStringConverterMixin {
    @Final
    @Mutable
    @Shadow
    private Map cache;

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void ssoptimizer$replaceCacheWithConcurrentMap(final CallbackInfo ci) {
        // 仅替换默认构造路径的 synchronized 包装缓存；外部传入的自定义 Map 保持原样
        if (this.cache != null
                && "java.util.Collections$SynchronizedMap".equals(this.cache.getClass().getName())) {
            this.cache = new ConcurrentHashMap<>();
        }
    }
}
