package github.kasuminova.ssoptimizer.common.combat.ai;

import com.fs.starfarer.api.combat.AutofireAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import com.fs.starfarer.combat.CombatEngine;
import com.fs.starfarer.combat.ai.PointDefenseAutofireAI;
import com.fs.starfarer.combat.entities.Ship;
import com.fs.starfarer.combat.systems.Weapon;
import com.fs.starfarer.combat.systems.WeaponGroup;
import github.kasuminova.ssoptimizer.common.concurrent.FrameParallelExecutor;
import org.apache.log4j.Logger;
import org.lwjgl.util.vector.Vector2f;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 并行收集原版点防御自动开火决策，同舰插件在同一工作线程串行执行。
 */
public final class AutofireBatchRunner {
    public static final String ENABLED_PROPERTY = "ssoptimizer.autofire.batch";

    private static final Logger LOGGER = Logger.getLogger(AutofireBatchRunner.class);

    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty(ENABLED_PROPERTY, "true"));

    /** 工作线程在收集阶段写入，主线程在实体推进阶段读取。 */
    private static final Map<Weapon, Decision> DECISIONS = new ConcurrentHashMap<>();

    static {
        if (!ENABLED) {
            LOGGER.info("[SSOptimizer] Autofire batch disabled via -D" + ENABLED_PROPERTY + "=false");
        }
    }

    private AutofireBatchRunner() {
    }

    /** @param aim 目标向量的拷贝，插件可能复用其内部向量 */
    public record Decision(boolean fire, Vector2f aim, Ship targetShip) {
    }

    /**
     * 由主线程在舰船 AI 屏障后调用，返回前等待本帧全部收集任务完成。
     * AI 并行或批处理关闭时不收集决策。
     */
    public static void collectAndCompute(final CombatEngine engine, final float amount) {
        final FrameParallelExecutor executor = ParallelAiDispatcher.executor();
        if (!ENABLED || executor == null) {
            return;
        }
        DECISIONS.clear();

        final Ship playerShip = engine.getPlayerShip();
        int submitted = 0;
        for (ShipAPI shipApi : engine.getShips()) {
            if (!(shipApi instanceof Ship ship) || ship.isHulk()) {
                continue;
            }
            final boolean playerControlled = ship == playerShip && ship.getShipAI() == null;
            for (WeaponGroupAPI groupApi : ship.getWeaponGroupsCopy()) {
                if (!(groupApi instanceof WeaponGroup group) || !group.isAutofiring()) {
                    continue;
                }
                if (playerControlled && ship.getSelectedGroup() == group) {
                    continue;
                }
                List<AutofireAIPlugin> plugins = group.getAIPlugins();
                if (plugins.isEmpty() || !allPluginsAreVanillaPointDefense(plugins)) {
                    continue;
                }
                for (AutofireAIPlugin plugin : plugins) {
                    executor.submit(() -> collect(plugin, amount), ship);
                    submitted++;
                }
            }
        }
        if (submitted > 0) {
            executor.awaitAll();
        }
    }

    /** 仅供主线程查询；未收集的武器返回 {@code null}。 */
    public static Decision getDecision(final Weapon weapon) {
        return DECISIONS.get(weapon);
    }

    private static boolean allPluginsAreVanillaPointDefense(final List<AutofireAIPlugin> plugins) {
        for (AutofireAIPlugin plugin : plugins) {
            if (plugin.getClass() != PointDefenseAutofireAI.class) {
                return false;
            }
        }
        return true;
    }

    private static void collect(final AutofireAIPlugin plugin, final float amount) {
        plugin.advance(amount);
        Vector2f aim = plugin.getTarget();
        DECISIONS.put((Weapon) plugin.getWeapon(), new Decision(
                plugin.shouldFire(),
                aim == null ? null : new Vector2f(aim),
                (Ship) plugin.getTargetShip()));
    }
}
