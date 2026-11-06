package github.kasuminova.ssoptimizer.mixin.ai;

import com.fs.starfarer.api.combat.ShipSystemAIScript;
import com.fs.starfarer.combat.entities.Ship;
import github.kasuminova.ssoptimizer.common.combat.ai.ParallelAiDispatcher;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.lwjgl.util.vector.Vector2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 工作线程执行模组系统脚本时持有 {@link ParallelAiDispatcher#MOD_SCRIPT_LOCK}。
 * 主线程内联模组 AI 使用同一把锁，保护模组中非线程安全的共享静态状态；原版脚本不加锁。
 */
@Mixin(targets = GameClassNames.SHIP_SYSTEM_SCRIPT_ADAPTER_DOTTED)
public abstract class ShipSystemScriptGuardMixin {
    @Shadow(remap = false)
    private ShipSystemAIScript aiScript;

    @Inject(method = "advance", at = @At("HEAD"), cancellable = true, remap = false)
    private void ssoptimizer$guardModScript(final float amount, final Vector2f missileDangerDir,
                                            final Vector2f collisionDangerDir, final Ship ship,
                                            final CallbackInfo ci) {
        if (!ParallelAiDispatcher.isWorkerThread()) {
            return;
        }
        final ShipSystemAIScript script = this.aiScript;
        if (script == null || script.getClass().getName().startsWith("com.fs.starfarer.")) {
            return;
        }
        synchronized (ParallelAiDispatcher.MOD_SCRIPT_LOCK) {
            script.advance(amount, missileDangerDir, collisionDangerDir, ship);
        }
        ci.cancel();
    }
}
