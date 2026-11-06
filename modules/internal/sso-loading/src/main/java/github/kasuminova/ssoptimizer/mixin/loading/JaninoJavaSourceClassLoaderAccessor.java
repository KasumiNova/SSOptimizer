package github.kasuminova.ssoptimizer.mixin.loading;

import org.codehaus.janino.JavaSourceIClassLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Map;

@Mixin(targets = "org.codehaus.janino.JavaSourceClassLoader")
public interface JaninoJavaSourceClassLoaderAccessor {
    @Accessor(value = "iClassLoader", remap = false)
    JavaSourceIClassLoader ssoptimizer$getIClassLoader();

    @Invoker(value = "generateBytecodes", remap = false)
    Map<String, byte[]> ssoptimizer$generateBytecodes(String className) throws ClassNotFoundException;
}
