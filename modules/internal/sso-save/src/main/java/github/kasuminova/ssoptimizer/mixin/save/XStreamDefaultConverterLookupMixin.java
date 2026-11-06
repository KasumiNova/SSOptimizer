package github.kasuminova.ssoptimizer.mixin.save;

import com.thoughtworks.xstream.converters.Converter;
import github.kasuminova.ssoptimizer.common.save.XStreamConverterLookupCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.thoughtworks.xstream.core.DefaultConverterLookup")
public abstract class XStreamDefaultConverterLookupMixin {
    @Unique
    private final XStreamConverterLookupCache ssoptimizer$fastLookupCache = new XStreamConverterLookupCache();

    @Inject(method = "lookupConverterForType", at = @At("HEAD"), cancellable = true, remap = false)
    private void ssoptimizer$lookupFastCachedConverter(final Class<?> type,
                                                       final CallbackInfoReturnable<Converter> cir) {
        if (type == null) {
            return;
        }

        final Converter cached = ssoptimizer$fastLookupCache.lookup(type);
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "lookupConverterForType", at = @At("RETURN"), remap = false)
    private void ssoptimizer$rememberResolvedConverter(final Class<?> type,
                                                       final CallbackInfoReturnable<Converter> cir) {
        if (type == null) {
            return;
        }

        ssoptimizer$fastLookupCache.remember(type, cir.getReturnValue());
    }

    @Inject(method = "registerConverter", at = @At("HEAD"), remap = false)
    private void ssoptimizer$clearFastLookupCacheOnRegister(final Converter converter,
                                                            final int priority,
                                                            final CallbackInfo callbackInfo) {
        ssoptimizer$fastLookupCache.clear();
    }

    @Inject(method = "flushCache", at = @At("HEAD"), remap = false)
    private void ssoptimizer$clearFastLookupCacheOnFlush(final CallbackInfo callbackInfo) {
        ssoptimizer$fastLookupCache.clear();
    }
}