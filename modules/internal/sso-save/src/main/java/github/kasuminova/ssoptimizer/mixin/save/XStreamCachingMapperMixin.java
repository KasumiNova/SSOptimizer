package github.kasuminova.ssoptimizer.mixin.save;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(targets = "com.thoughtworks.xstream.mapper.CachingMapper")
public abstract class XStreamCachingMapperMixin {
    @Shadow
    private transient Map realClassCache;

    @Overwrite(remap = false)
    private Object readResolve() {
        this.realClassCache = new ConcurrentHashMap<>(128);
        return this;
    }
}
