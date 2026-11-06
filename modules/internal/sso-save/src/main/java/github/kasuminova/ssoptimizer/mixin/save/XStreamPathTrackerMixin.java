package github.kasuminova.ssoptimizer.mixin.save;

import com.thoughtworks.xstream.io.path.Path;
import github.kasuminova.ssoptimizer.common.save.XStreamPathTrackerHelper;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;

@Mixin(targets = "com.thoughtworks.xstream.io.path.PathTracker")
public abstract class XStreamPathTrackerMixin {
    @Shadow(remap = false)
    private int pointer;

    @Shadow(remap = false)
    private int capacity;

    @Shadow(remap = false)
    private String[] pathStack;

    @Shadow(remap = false)
    private Map[] indexMapStack;

    @Shadow(remap = false)
    private Path currentPath;

    @Shadow(remap = false)
    private void resizeStacks(final int newCapacity) {
        throw new AssertionError();
    }

    @Unique
    private String[] ssoptimizer$formattedPathStack;

    @Inject(method = "<init>(I)V", at = @At("RETURN"), remap = false)
    private void ssoptimizer$initFormattedPathStack(final int initialCapacity, final CallbackInfo callbackInfo) {
        ssoptimizer$formattedPathStack = XStreamPathTrackerHelper.createFormattedPathStack(capacity);
    }

    @Overwrite(remap = false)
    public void pushElement(final String name) {
        if (pointer + 1 >= capacity) {
            resizeStacks(capacity * 2);
            ssoptimizer$formattedPathStack = XStreamPathTrackerHelper.resizeFormattedPathStack(
                    ssoptimizer$formattedPathStack,
                    capacity
            );
        }

        pathStack[pointer] = name;
        Map<String, Integer> indexMap = indexMapStack[pointer];
        if (indexMap == null) {
            indexMap = new Object2ObjectOpenHashMap<>();
            indexMapStack[pointer] = indexMap;
        }

        final Integer currentIndex = indexMap.get(name);
        final int nextIndex = currentIndex != null ? currentIndex + 1 : 1;
        indexMap.put(name, nextIndex);
        ssoptimizer$formattedPathStack[pointer] = XStreamPathTrackerHelper.formatElement(name, nextIndex);

        pointer++;
        currentPath = null;
    }

    @Overwrite(remap = false)
    public void popElement() {
        indexMapStack[pointer] = null;
        pathStack[pointer] = null;
        ssoptimizer$formattedPathStack[pointer] = null;
        currentPath = null;
        pointer--;
    }

    @Overwrite(remap = false)
    public String peekElement() {
        return peekElement(0);
    }

    @Overwrite(remap = false)
    public String peekElement(final int offset) {
        if (offset < -pointer || offset > 0) {
            throw new ArrayIndexOutOfBoundsException(offset);
        }
        return ssoptimizer$formattedPathStack[pointer + offset - 1];
    }

    @Overwrite(remap = false)
    public Path getPath() {
        if (currentPath == null) {
            currentPath = XStreamPathTrackerHelper.buildPath(ssoptimizer$formattedPathStack, pointer);
        }
        return currentPath;
    }
}