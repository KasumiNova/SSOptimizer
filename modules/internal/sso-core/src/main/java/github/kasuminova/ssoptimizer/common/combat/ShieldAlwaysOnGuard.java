package github.kasuminova.ssoptimizer.common.combat;

import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import org.apache.log4j.Logger;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * {@code ShieldAlwaysOn} hullmod 的无盾判空守卫。
 * <p>
 * 动机：原版 {@code ShieldAlwaysOn.advanceInCombat} 对 {@code ship.getShield()} 不做判空，
 * 而盾实体仅在 {@code ShipFactory} 按船体 ShieldSpec 创建（NONE/PHASE 类型不创建）；
 * 模组若把该 hullmod 配在无盾/相位船体上（实机案例：lwe 模组无人机经
 * {@code CombatFleetManager.deployMember} 部署后首次 advance 即 NPE），
 * 原版同路径同样崩溃，属于原版未防御的模组误配场景。<br>
 * 语义：无盾舰船视为「盾已开启」——跳过 {@code toggleOn()} 调用，
 * 方法其余逻辑（禁用手动盾/相位切换、超载与辐能修正）保持原版行为不变。<br>
 * 日志：按舰船实例去重（弱引用键，不阻碍实体回收），每艘船仅 WARN 一次，
 * 避免每帧刷屏；无法取得舰船身份时全 JVM 只报一次。
 */
public final class ShieldAlwaysOnGuard {
    private static final Logger LOGGER = Logger.getLogger(ShieldAlwaysOnGuard.class);

    /** 已报告过的舰船实例（弱键，同步访问）。 */
    private static final Set<ShipAPI> REPORTED = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));
    /** 无舰船身份时的单次报告标记。 */
    private static boolean reportedUnknown;

    private ShieldAlwaysOnGuard() {
    }

    /**
     * 空安全的盾开启判定。
     *
     * @param shield 舰船盾（可为 null：无盾/相位船体）
     * @param ship 所属舰船（可为 null：身份不可用，仅影响日志）
     * @return 盾开启状态；无盾时返回 {@code true}（跳过 toggleOn）
     */
    public static boolean isShieldOnOrAbsent(final ShieldAPI shield, final ShipAPI ship) {
        if (shield != null) {
            return shield.isOn();
        }
        reportNullShieldOnce(ship);
        return true;
    }

    /**
     * 按舰船实例去重报告一次无盾命中。
     *
     * @param ship 命中舰船（可为 null）
     * @return 本次是否实际输出了日志（供测试验证节流语义）
     */
    static boolean reportNullShieldOnce(final ShipAPI ship) {
        if (ship == null) {
            synchronized (REPORTED) {
                if (reportedUnknown) {
                    return false;
                }
                reportedUnknown = true;
            }
            LOGGER.warn("[SSOptimizer] ShieldAlwaysOn hullmod 作用于无盾舰船（身份不可用），"
                    + "已按「盾常开」处理；请检查模组船体配置（盾类型为 NONE/PHASE 时不应挂此 hullmod）");
            return true;
        }
        if (!REPORTED.add(ship)) {
            return false;
        }
        LOGGER.warn(String.format(
                "[SSOptimizer] ShieldAlwaysOn hullmod 作用于无盾舰船（hull=%s），已按「盾常开」处理；"
                        + "请检查模组船体配置（盾类型为 NONE/PHASE 时不应挂此 hullmod）",
                ship.getHullSpec() == null ? "?" : ship.getHullSpec().getHullId()));
        return true;
    }

    /** 重置节流状态（仅测试使用）。 */
    public static void resetForTesting() {
        synchronized (REPORTED) {
            REPORTED.clear();
            reportedUnknown = false;
        }
    }
}
