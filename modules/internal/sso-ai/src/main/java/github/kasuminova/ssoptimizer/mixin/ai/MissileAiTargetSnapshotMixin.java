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
 * 在目标校验和读取入口分别快照非 volatile 的 {@code target} 字段。
 * 避免并行读写下多次解引用之间目标失效导致空指针异常。
 */
@Mixin(targets = GameClassNames.MISSILE_AI_DOTTED)
public abstract class MissileAiTargetSnapshotMixin {
    @Shadow(remap = false)
    private CollisionEntity target;

    @Shadow(remap = false)
    private Missile missile;

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

    @Overwrite(remap = false)
    public CombatEntityAPI getTarget() {
        final CollisionEntity target = this.target;
        if (target == null) {
            return null;
        }
        return this.isTargetValid() ? target : null;
    }
}
