package github.kasuminova.ssoptimizer.mixin.ui;

import com.fs.starfarer.ui.PositionImpl;
import github.kasuminova.ssoptimizer.common.ui.PositionSortHelper;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

@Mixin(targets = GameMixinSignatures.PositionImpl.TARGET_CLASS, remap = false)
public abstract class PositionImplMixin {
    @Shadow
    private List<PositionImpl> children;
    @Shadow
    private boolean suspendRecompute;
    @Shadow
    private boolean withSort;
    @Shadow
    private static boolean suspendSort;

    @Overwrite(remap = false)
    private void sortChildren() {
        if (!this.suspendRecompute) {
            if (this.withSort && !suspendSort) {
                PositionSortHelper.sortByAnchor(this.children, PositionImpl::getBase);
            }
        }
    }
}
