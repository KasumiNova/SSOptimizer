package github.kasuminova.ssoptimizer.common.combat;

import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import org.apache.log4j.Logger;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** 无盾或相位舰船跳过盾开启调用，并按弱引用舰船实例去重告警。 */
public final class ShieldAlwaysOnGuard {
    private static final Logger LOGGER = Logger.getLogger(ShieldAlwaysOnGuard.class);

    private static final Set<ShipAPI> REPORTED = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));
    private static boolean reportedUnknown;

    private ShieldAlwaysOnGuard() {
    }

    public static boolean isShieldOnOrAbsent(final ShieldAPI shield, final ShipAPI ship) {
        if (shield != null) {
            return shield.isOn();
        }
        reportNullShieldOnce(ship);
        return true;
    }

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

    public static void resetForTesting() {
        synchronized (REPORTED) {
            REPORTED.clear();
            reportedUnknown = false;
        }
    }
}
