package github.kasuminova.ssoptimizer.mixin.warroom;

import github.kasuminova.ssoptimizer.common.render.engine.TexturedStripRenderHelper;
import github.kasuminova.ssoptimizer.common.render.warroom.WarroomTaskLineBatch;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = GameClassNames.TASK_ICON_MANAGER_DOTTED)
public abstract class TaskIconManagerMixin {

    @Inject(method = "render", at = @At("HEAD"), remap = false)
    private void ssoptimizer$beginTaskLineBatch(final float screenScale, final float alpha, final CallbackInfo ci) {
        TexturedStripRenderHelper.beginStripBatch();
    }

    @Inject(method = "render", at = @At("RETURN"), remap = false)
    private void ssoptimizer$endTaskLineBatch(final float screenScale, final float alpha, final CallbackInfo ci) {
        TexturedStripRenderHelper.endStripBatch();
    }
}
