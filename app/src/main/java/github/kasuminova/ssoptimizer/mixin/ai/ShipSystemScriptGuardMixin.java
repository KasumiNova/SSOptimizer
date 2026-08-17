package github.kasuminova.ssoptimizer.mixin.ai;

import com.fs.starfarer.api.combat.ShipSystemAIScript;
import com.fs.starfarer.combat.entities.Ship;
import github.kasuminova.ssoptimizer.common.combat.ai.ParallelAiDispatcher;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.lwjgl.util.vector.Vector2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 舰船系统 AI 脚本守卫 Mixin。
 * <p>
 * 注入目标：{@code com.fs.starfarer.loading.specs.ShipSystemSpec$SystemAIScriptAdapter#advance}<br>
 * 注入动机：舰船 AI 并行后，模组提供的系统 AI 脚本（如 niko_MPC 系列）在多个工作线程上
 * 并发执行，其内部使用的 LazyLib {@code CombatCache} 等模组级共享静态状态并非线程安全，
 * 长程基准实测抛出 ConcurrentModificationException。模组 jar 由游戏自带的
 * URLClassLoader 加载，不经过 LaunchClassLoader 变换链，无法直接 Mixin 修复模组类，
 * 只能在调用侧（游戏类适配器）加锁串行化。<br>
 * 注入效果：工作线程上执行<b>模组</b>脚本（包名不以 {@code com.fs.starfarer.} 开头）时，
 * 全程持有全局锁串行执行并取消原方法；原版脚本与主线程调用不受影响（原版脚本已在
 * 并行 AI 长程测试中验证无共享静态状态问题）。
 * <p>
 * 并发覆盖完整性：并行阶段与实体 advance/渲染阶段由帧内屏障隔开，LazyLib 缓存的
 * 主线程使用者（EveryFrame 插件等）不会与工作线程并发，故只需串行化工作线程之间的
 * 模组脚本执行。
 */
@Mixin(targets = GameClassNames.SHIP_SYSTEM_SCRIPT_ADAPTER_DOTTED)
public abstract class ShipSystemScriptGuardMixin {
    @Shadow(remap = false)
    private ShipSystemAIScript aiScript;

    @Unique
    private static final Object ssoptimizer$MOD_SCRIPT_LOCK = new Object();

    /**
     * @author KasumiNova
     * @reason 模组系统 AI 脚本在工作线程上串行执行，避免模组级共享静态状态并发损坏。
     */
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
        synchronized (ssoptimizer$MOD_SCRIPT_LOCK) {
            script.advance(amount, missileDangerDir, collisionDangerDir, ship);
        }
        ci.cancel();
    }
}
