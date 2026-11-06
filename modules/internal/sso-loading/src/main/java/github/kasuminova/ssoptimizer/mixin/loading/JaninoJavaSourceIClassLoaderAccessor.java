package github.kasuminova.ssoptimizer.mixin.loading;

import org.codehaus.janino.util.resource.ResourceFinder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "org.codehaus.janino.JavaSourceIClassLoader")
public interface JaninoJavaSourceIClassLoaderAccessor {
    @Accessor(value = "sourceFinder", remap = false)
    ResourceFinder ssoptimizer$getSourceFinder();
}
