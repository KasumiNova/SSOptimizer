package github.kasuminova.ssoptimizer.mixin.ai;

import com.fs.starfarer.combat.ai.AI;
import com.fs.starfarer.combat.ai.FighterAI;
import com.fs.starfarer.combat.ai.FighterWing;
import com.fs.starfarer.combat.ai.movement.maneuvers.Maneuver;
import com.fs.starfarer.combat.entities.Ship;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.List;

/**
 * 为战机编队成员迭代提供快照，避免与工作线程或主线程的增删冲突。
 * 编队领袖或机动在 {@code instanceof} 后失效时返回 {@code null}，使原调用点跳过对应分支。
 */
@Mixin(targets = GameClassNames.FIGHTER_AI)
public abstract class FighterAiSnapshotMixin {

    @Redirect(method = "*",
            at = @At(value = "INVOKE",
                    target = "Lcom/fs/starfarer/combat/ai/FighterWing;getMembers()Ljava/util/List;"),
            remap = false)
    private List<Ship> ssoptimizer$snapshotWingMembers(FighterWing wing) {
        return new ArrayList<>(wing.getMembers());
    }

    @Redirect(method = "*",
            at = @At(value = "INVOKE",
                    target = "Lcom/fs/starfarer/combat/entities/Ship;getAI()Lcom/fs/starfarer/combat/ai/AI;"),
            remap = false)
    private AI ssoptimizer$guardGetAi(Ship ship) {
        return ship != null ? ship.getAI() : null;
    }

    @Redirect(method = "*",
            at = @At(value = "INVOKE",
                    target = "Lcom/fs/starfarer/combat/ai/FighterAI;getCurrentManeuver()Lcom/fs/starfarer/combat/ai/movement/maneuvers/Maneuver;"),
            remap = false)
    private Maneuver ssoptimizer$guardCurrentManeuver(FighterAI ai) {
        return ai != null ? ai.getCurrentManeuver() : null;
    }
}
