package github.kasuminova.ssoptimizer.mixin.save;

import com.thoughtworks.xstream.converters.Converter;
import github.kasuminova.ssoptimizer.common.save.XStreamConverterLookupCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * XStream 转换器查询加速 Mixin。
 * <p>
 * 注入目标：{@code com.thoughtworks.xstream.core.DefaultConverterLookup}<br>
 * 注入动机：热点分析显示原实现每次命中已缓存类型时仍需访问同步 {@code Map}，
 * 在保存阶段会形成稳定锁竞争与同步读开销。<br>
 * 注入效果：为成功解析过的类型增加一层实例级无锁正向缓存；命中时直接返回缓存转换器，
 * miss 时仍完全复用 XStream 原始查找与异常语义，并在注册新转换器或清缓存时同步失效。
 */
@Mixin(targets = "com.thoughtworks.xstream.core.DefaultConverterLookup")
public abstract class XStreamDefaultConverterLookupMixin {
    @Unique
    private final XStreamConverterLookupCache ssoptimizer$fastLookupCache = new XStreamConverterLookupCache();

    /**
     * 优先命中实例级快缓存。
     *
     * @param type 目标类型
     * @param cir  返回值回调
     * @author GitHub Copilot
     * @reason 对热点类型绕过 XStream 原始同步缓存读取，减少保存阶段的锁与同步 map 访问开销。
     */
    @Inject(method = "lookupConverterForType", at = @At("HEAD"), cancellable = true, remap = false)
    private void ssoptimizer$lookupFastCachedConverter(final Class<?> type,
                                                       final CallbackInfoReturnable<Converter> cir) {
        final Converter cached = ssoptimizer$fastLookupCache.lookup(type);
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    /**
     * 记录原始查找成功返回的转换器。
     *
     * @param type 目标类型
     * @param cir  返回值回调
     * @author GitHub Copilot
     * @reason 仅缓存 XStream 已成功解析出的结果，保持 miss 路径与异常构造完全由原实现负责。
     */
    @Inject(method = "lookupConverterForType", at = @At("RETURN"), remap = false)
    private void ssoptimizer$rememberResolvedConverter(final Class<?> type,
                                                       final CallbackInfoReturnable<Converter> cir) {
        ssoptimizer$fastLookupCache.remember(type, cir.getReturnValue());
    }

    /**
     * 注册新转换器前清空快缓存。
     *
     * @param converter 新注册的转换器
     * @param priority  优先级
     * @param callbackInfo 回调信息
     * @author GitHub Copilot
     * @reason 新转换器可能改变既有类型的最佳匹配结果，直接整体失效可确保与原始语义一致。
     */
    @Inject(method = "registerConverter", at = @At("HEAD"), remap = false)
    private void ssoptimizer$clearFastLookupCacheOnRegister(final Converter converter,
                                                            final int priority,
                                                            final CallbackInfo callbackInfo) {
        ssoptimizer$fastLookupCache.clear();
    }

    /**
     * 跟随 XStream 主缓存一起清空快缓存。
     *
     * @param callbackInfo 回调信息
     * @author GitHub Copilot
     * @reason 保证额外快缓存与底层同步缓存失效边界一致，避免持有过期转换器结果。
     */
    @Inject(method = "flushCache", at = @At("HEAD"), remap = false)
    private void ssoptimizer$clearFastLookupCacheOnFlush(final CallbackInfo callbackInfo) {
        ssoptimizer$fastLookupCache.clear();
    }
}