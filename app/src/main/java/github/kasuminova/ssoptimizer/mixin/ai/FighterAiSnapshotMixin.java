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
 * FighterAI 共享集合迭代与引用读取的并发安全注入。
 * <p>
 * 背景：AI 并行化后 {@code FighterAI.advance} 在 worker 线程执行，其内部对
 * {@link FighterWing#getMembers()} 的迭代（pickManeuver 的编队成员遍历、
 * cancelCurrentManeuver 的 contains 检查）读取的是编队内部成员列表的活
 * 引用——并行窗口内航母 BasicShipAI（worker，无 wing stripeKey，与
 * FighterAI 不同 worker 并发）释放/回收战机、或主线程内联 AI 增删成员时，
 * 迭代随并发写抛出 {@link java.util.concurrent.ConcurrentModificationException}
 * （基准 v48b 实测：Parallel ship AI failed → CME in FighterAI.advance）。
 * <p>
 * 修复一：@Redirect 该类全部 {@code getMembers()} 调用点为快照拷贝。FighterAI
 * 对成员列表只读（成员增删由 FleetManager/航母 AI 负责），拷贝不改变写语义；
 * 战机列表在并行下的瞬时一致性无定义，快照保证迭代稳定不抛 CME（行为差异
 * 论证：原版串行下迭代与修改互斥，并行下快照迭代「迭代开始时刻」的成员，
 * 是并行语义下唯一可定义的稳定结果）。
 * <p>
 * 修复二/三：support 分支（advance 内 wing 护航目标传递）对
 * {@code this.wing.getLeader().getAI()} 与 {@code var20.getCurrentManeuver()}
 * 的 check-then-act（instanceof 检查后二次取引用）——并行窗口期 leader 死亡
 * 或 maneuver 队列被并发替换时二次调用返回 null，随后直接调实例方法 NPE
 * （与 AIUtils.isEscortTargetOf 的 local4 null 同源）。@Redirect 拦截
 * {@code Ship.getAI()} 与 {@code FighterAI.getCurrentManeuver()} 调用，
 * receiver 为 null 时返回 null——调用点 instanceof 对 null 判 false → 分支
 * 跳过，语义与「leader/maneuver 已不存在」一致。
 */
@Mixin(targets = GameClassNames.FIGHTER_AI)
public abstract class FighterAiSnapshotMixin {

    /**
     * @param wing getMembers() 的调用目标（编队实例）
     * @return 成员列表的拷贝快照（并行 worker 迭代期间不受并发增删影响）
     */
    @Redirect(method = "*",
            at = @At(value = "INVOKE",
                    target = "Lcom/fs/starfarer/combat/ai/FighterWing;getMembers()Ljava/util/List;"),
            remap = false)
    private List<Ship> ssoptimizer$snapshotWingMembers(FighterWing wing) {
        return new ArrayList<>(wing.getMembers());
    }

    /**
     * @param ship getAI() 的调用目标（wing leader 等，并行窗口期可为 null）
     * @return 非 null 时真实 AI，null 时 null（调用点 instanceof 判 null 走跳过分支）
     */
    @Redirect(method = "*",
            at = @At(value = "INVOKE",
                    target = "Lcom/fs/starfarer/combat/entities/Ship;getAI()Lcom/fs/starfarer/combat/ai/AI;"),
            remap = false)
    private AI ssoptimizer$guardGetAi(Ship ship) {
        return ship != null ? ship.getAI() : null;
    }

    /**
     * @param ai getCurrentManeuver() 的调用目标（support 分支的 leader AI，
     *           并行窗口期二次取引用可为 null）
     * @return 非 null 时真实 maneuver，null 时 null（调用点 instanceof 判 null
     *         走跳过分支，避免 var7.getTargetEntity() NPE）
     */
    @Redirect(method = "*",
            at = @At(value = "INVOKE",
                    target = "Lcom/fs/starfarer/combat/ai/FighterAI;getCurrentManeuver()Lcom/fs/starfarer/combat/ai/movement/maneuvers/Maneuver;"),
            remap = false)
    private Maneuver ssoptimizer$guardCurrentManeuver(FighterAI ai) {
        return ai != null ? ai.getCurrentManeuver() : null;
    }
}
