package github.kasuminova.ssoptimizer.mixin.loading;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.io.File;

@Mixin(targets = "org.codehaus.janino.util.resource.DirectoryResourceFinder")
public interface JaninoDirectoryResourceFinderAccessor {
    @Accessor(value = "directory", remap = false)
    File ssoptimizer$getDirectory();
}
