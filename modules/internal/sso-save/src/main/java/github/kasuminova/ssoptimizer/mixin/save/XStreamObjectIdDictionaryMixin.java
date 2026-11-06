package github.kasuminova.ssoptimizer.mixin.save;

import github.kasuminova.ssoptimizer.common.save.XStreamObjectIdDictionaryHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;

@Mixin(targets = "com.thoughtworks.xstream.core.util.ObjectIdDictionary")
public abstract class XStreamObjectIdDictionaryMixin {
    @Mutable
    @Final
    @Shadow(remap = false)
    private Map<?, ?> map;

    @Shadow(remap = false)
    private void cleanup() {
        throw new AssertionError();
    }

    @Unique
    private static final int SSOPTIMIZER_INITIAL_REFERENCE_MAP_CAPACITY = 4096;

    @Unique
    private final XStreamObjectIdDictionaryHelper.ReusableIdProbe ssoptimizer$lookupProbe =
            XStreamObjectIdDictionaryHelper.createReusableProbe();

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void ssoptimizer$preSizeReferenceMap(final CallbackInfo callbackInfo) {
        map = new HashMap<>(SSOPTIMIZER_INITIAL_REFERENCE_MAP_CAPACITY);
    }

    @Overwrite(remap = false)
    public Object lookupId(final Object item) {
        return XStreamObjectIdDictionaryHelper.lookupId(map, item, ssoptimizer$lookupProbe);
    }

    @Overwrite(remap = false)
    public boolean containsId(final Object item) {
        return XStreamObjectIdDictionaryHelper.containsId(map, item, ssoptimizer$lookupProbe);
    }

    @Overwrite(remap = false)
    public void removeId(final Object item) {
        XStreamObjectIdDictionaryHelper.removeId(map, item, ssoptimizer$lookupProbe);
        cleanup();
    }
}