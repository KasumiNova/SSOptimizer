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

@Mixin(targets = "com.thoughtworks.xstream.converters.basic.StringConverter")
public abstract class XStreamStringConverterMixin {
    @Final
    @Mutable
    @Shadow
    private Map cache;

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void ssoptimizer$replaceCacheWithConcurrentMap(final CallbackInfo ci) {
        if (this.cache != null
                && "java.util.Collections$SynchronizedMap".equals(this.cache.getClass().getName())) {
            this.cache = new ConcurrentHashMap<>();
        }
    }
}
