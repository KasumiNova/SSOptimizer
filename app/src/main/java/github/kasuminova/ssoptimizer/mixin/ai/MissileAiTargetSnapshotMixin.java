package github.kasuminova.ssoptimizer.mixin.ai;

import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.combat.CollisionEntity;
import com.fs.starfarer.combat.entities.Missile;
import com.fs.starfarer.combat.entities.Ship;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 导弹 AI 目标读取并发修复 Mixin。
 * <p>
 * 注入目标：{@code com.fs.starfarer.combat.ai.missile.MissileAI#isTargetValid} 与
 * {@code MissileAI#getTarget}<br>
 * 注入动机：舰船 AI 并行化后，工作线程上的 {@code FighterAI.advance} 会经由
 * {@code FiringSolutionEval.computeIncomingDamage → MissileAI.getTarget} 读取导弹当前
 * 目标；而导弹自身的 {@code MissileAI.advance} 在另一调度路径上随时会把
 * {@code target} 置 null（重定向阶段）或改写为新目标。原版 {@code isTargetValid}
 * 在方法内多次裸读非 volatile 字段 {@code target}（先判空、再调
 * {@code wasRemoved()}/{@code getOwner()}），vanilla 单线程下安全，并行后两次读之间
 * 目标可被置 null，长程基准实测在 {@code getOwner()} 处抛 NPE。<br>
 * 注入效果：两个方法的逻辑逐行保持原版语义，仅在方法入口把 {@code target}
 * 快照进局部变量，方法内全程使用快照，消除 check-then-act 窗口。
 * 导弹 AI 的写侧（advance/retarget）不在并行白名单内，无需同步；
 * 读取方拿到的是某一瞬间的一致快照，语义偏差可忽略。
 */
@Mixin(targets = GameClassNames.MISSILE_AI_DOTTED)
public abstract class MissileAiTargetSnapshotMixin {
    @Shadow(remap = false)
    private CollisionEntity target;

    @Shadow(remap = false)
    private Missile missile;

    /**
     * @author KasumiNova
     * @reason 原版多次裸读非 volatile 字段 target，并行读写下存在 check-then-act NPE 窗口；改为入口快照。
     */
    @Overwrite(remap = false)
    private boolean isTargetValid() {
        final CollisionEntity target = this.target;
        if (target == null) {
            return false;
        }
        if (target instanceof Ship && ((Ship) target).isHulk()) {
            return false;
        }
        return !target.wasRemoved() && target.getOwner() != this.missile.getOwner();
    }

    /**
     * @author KasumiNova
     * @reason 原版 isTargetValid 通过后又裸读一次 target 返回，与上面的快照校验存在 TOCTOU 窗口；统一使用同一快照。
     */
    @Overwrite(remap = false)
    public CombatEntityAPI getTarget() {
        final CollisionEntity target = this.target;
        if (target == null) {
            return null;
        }
        return this.isTargetValid() ? target : null;
    }
}
