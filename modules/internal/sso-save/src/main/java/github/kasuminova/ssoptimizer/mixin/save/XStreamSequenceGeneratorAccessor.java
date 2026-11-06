package github.kasuminova.ssoptimizer.mixin.save;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "com.thoughtworks.xstream.core.SequenceGenerator")
public interface XStreamSequenceGeneratorAccessor {
    @Accessor(value = "counter", remap = false)
    int ssoptimizer$getCounter();
}
