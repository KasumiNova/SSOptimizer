package github.kasuminova.ssoptimizer.mixin.loading;

import org.codehaus.janino.util.resource.ResourceFinder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Collection;

@Mixin(targets = "org.codehaus.janino.util.resource.MultiResourceFinder")
public interface JaninoMultiResourceFinderAccessor {
    @Accessor(value = "resourceFinders", remap = false)
    Collection<ResourceFinder> ssoptimizer$getResourceFinders();
}
