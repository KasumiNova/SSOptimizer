package github.kasuminova.ssoptimizer.mixin.modopt.dcr;

import github.kasuminova.ssoptimizer.modopt.dcr.DcrBatchSaveWindow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Iterator;
import java.util.List;

/**
 * 在 DCR 读档裁剪循环前开启批量保存窗口，方法返回前关闭。
 * <p>{@code onGameLoad(Z)V} 内只有一处 {@code List.iterator()}，即遍历裁剪后列表的循环入口，
 * 列表长度就是循环将发起的保存次数。</p>
 */
@Mixin(targets = "data.scripts.combatanalytics.DetailedCombatResultsModPlugin")
public abstract class DcrOnGameLoadBatchMixin {

    @Redirect(method = "onGameLoad(Z)V", remap = false, expect = 1,
            at = @At(value = "INVOKE", target = "Ljava/util/List;iterator()Ljava/util/Iterator;", remap = false))
    private Iterator<?> ssoptimizer$beginBatchSave(final List<?> trimmed) {
        DcrBatchSaveWindow.begin(trimmed.size());
        return trimmed.iterator();
    }

    @Inject(method = "onGameLoad(Z)V", at = @At("RETURN"), remap = false)
    private void ssoptimizer$endBatchSave(final boolean newGame, final CallbackInfo ci) {
        DcrBatchSaveWindow.end();
    }
}
