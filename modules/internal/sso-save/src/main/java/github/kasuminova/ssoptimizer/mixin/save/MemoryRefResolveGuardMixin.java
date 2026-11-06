package github.kasuminova.ssoptimizer.mixin.save;

import github.kasuminova.ssoptimizer.common.save.MemoryRefResolveGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.LinkedHashMap;

@Mixin(targets = "com.fs.starfarer.campaign.rules.Memory", remap = false)
public abstract class MemoryRefResolveGuardMixin implements MemoryRefResolveGuard.PendingResolution {
    @Shadow
    private LinkedHashMap<String, Object> data;

    @Shadow
    public abstract void replaceIdsWithEntities(LinkedHashMap<String, Object> data);

    @Redirect(method = "replaceIdsWithEntities(Ljava/util/LinkedHashMap;)V",
            at = @At(value = "INVOKE",
                    target = "Ljava/util/LinkedHashMap;remove(Ljava/lang/Object;)Ljava/lang/Object;"),
            expect = 2, remap = false)
    private Object ssoptimizer$guardRemoval(final LinkedHashMap map, final Object key) {
        if (MemoryRefResolveGuard.isUnsafeWindow()) {
            MemoryRefResolveGuard.recordSuppressed(this);
            return null;
        }
        return map.remove(key);
    }

    @Unique
    @Override
    public void ssoptimizer$rerunResolution() {
        replaceIdsWithEntities(data);
    }
}
