package github.kasuminova.ssoptimizer.mixin.ai;

import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.combat.entities.Ship;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * FighterAI.getCarrierTarget 并发读序修复 Mixin。
 * <p>
 * 注入目标：{@code com.fs.starfarer.combat.ai.FighterAI}<br>
 * 注入动机：原版实现先 {@code hasFlag(CARRIER_FIGHTER_TARGET)} 再
 * {@code getCustom(...)}，两次查询之间其他 AI 工作线程可能
 * {@code unsetFlag}/{@code advance} 过期清理同一实例的旗标
 * （{@link ShipwideAiFlagsConcurrentMixin} 只保证 Map 结构安全，无法消除
 * check-then-act 竞态），导致 {@code getCustom} 返回 null 后调用
 * {@code Ship.isHulk()} 抛出 NPE（实机日志已复现）。<br>
 * 注入效果：整体覆写 {@code getCarrierTarget}，{@code CARRIER_FIGHTER_TARGET}
 * 与 {@code MANEUVER_TARGET} 两处均改为单次 {@code getCustom} 读取 +
 * {@code instanceof} 判空——{@code getCustom} 在旗标缺失时本就返回 null，
 * 单次读取在 ConcurrentHashMap 上具备原子性，语义与原版一致。
 */
@Mixin(targets = GameClassNames.FIGHTER_AI_DOTTED)
public abstract class FighterAiCarrierTargetMixin {

    /**
     * @author KasumiNova
     * @reason 消除 hasFlag/getCustom 之间的 check-then-act 竞态（并行 AI 下 NPE）。
     */
    @Overwrite(remap = false)
    public static Ship getCarrierTarget(Ship ship) {
        if (ship.getAI() instanceof ShipAIPlugin) {
            ShipAIPlugin ai = (ShipAIPlugin) ship.getAI();
            ShipwideAIFlags flags = ai.getAIFlags();
            if (flags != null) {
                Object carrierTarget = flags.getCustom(ShipwideAIFlags.AIFlags.CARRIER_FIGHTER_TARGET);
                if (carrierTarget instanceof Ship) {
                    Ship target = (Ship) carrierTarget;
                    if (!target.isHulk() && (!target.isFriendOf(ship) || !target.isFighter())) {
                        return target;
                    }
                }

                Object maneuverTarget = flags.getCustom(ShipwideAIFlags.AIFlags.MANEUVER_TARGET);
                if (maneuverTarget instanceof Ship) {
                    Ship target = (Ship) maneuverTarget;
                    if (!target.isHulk() && target.isEnemyOf(ship)) {
                        return target;
                    }
                }
            }
        }

        Ship shipTarget = ship.getShipTarget();
        return shipTarget == null || shipTarget.isHulk()
                || shipTarget.isFriendOf(ship) && shipTarget.isFighter() ? null : shipTarget;
    }
}
