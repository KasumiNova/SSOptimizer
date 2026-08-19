package github.kasuminova.ssoptimizer.common.combat.ai;

import com.fs.starfarer.api.combat.AutofireAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import com.fs.starfarer.combat.CombatEngine;
import com.fs.starfarer.combat.entities.Ship;
import com.fs.starfarer.combat.systems.Weapon;
import com.fs.starfarer.combat.systems.WeaponGroup;
import org.apache.log4j.Logger;
import org.lwjgl.util.vector.Vector2f;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自动开火 AI 半并行化：并行收集开火意图，主线程应用开火。
 * <p>
 * 原版 {@code WeaponGroup.advanceAuto} 在实体 advance 阶段对组内每个插件串行执行
 * 「{@code plugin.advance}（决策：目标搜索/弹道预判）→ {@code shouldFire}/{@code getTarget}
 * → {@code weapon.advance}（实际开火）」。其中插件决策是主要 CPU 开销（基准实测约占
 * {@code Ship.advance} 的 3.3%），且不同舰船之间的决策相互独立。
 * <p>
 * 本类在舰船 AI 帧内屏障之后、实体 advance 之前，把白名单插件的 advance 与决策读取
 * 投递到 AI 线程池（stripeKey=Ship，同舰插件串行，消除 AI Tweaks {@code SyncFire$State}
 * 等插件内共享状态的跨舰竞争之外的所有并发写），决策按 {@link Weapon} 身份缓存；
 * {@code WeaponGroupAutofireBatchMixin} 随后在 {@code advanceAuto} 头部命中缓存时，
 * 在主线程复刻原版应用段并取消原方法。
 * <p>
 * 组级原子性：仅当组内全部插件都命中白名单时才收集；任一未命中则整组走原版内联路径，
 * 避免同一插件一帧内被 advance 两次。
 * <p>
 * 已知语义偏差：决策读取自收集时刻（较原版提前到实体 advance 之前），且同组内所有
 * 插件的 advance 先于本组任一 weapon.advance 执行；{@code isHoldFire} 等即时状态判定
 * 保留在应用期，与原版一致。
 * <p>
 * 开关：{@code -Dssoptimizer.autofire.batch=false} 关闭（默认开启）；复用 AI 并行
 * 执行器，AI 并行关闭（{@code ssoptimizer.ai.parallel=false}）时本功能自动停用。
 */
public final class AutofireBatchRunner {
    public static final String ENABLED_PROPERTY = "ssoptimizer.autofire.batch";

    private static final Logger LOGGER = Logger.getLogger(AutofireBatchRunner.class);

    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty(ENABLED_PROPERTY, "true"));

    /**
     * 允许并行决策的插件精确类名白名单。模组（AI Tweaks）可能不存在，
     * 故用类名字符串而非类引用匹配。名单外的插件整组回退原版路径。
     */
    private static final Set<String> PLUGIN_WHITELIST = Set.of(
            "com.fs.starfarer.combat.ai.PointDefenseAutofireAI",
            "com.genir.aitweaks.core.shipai.autofire.AutofireAI",
            "com.genir.aitweaks.core.shipai.autofire.RecklessAutofireAI");

    /** 本帧收集的开火决策，收集线程写入、主线程实体 advance 阶段读取。 */
    private static final Map<Weapon, Decision> DECISIONS = new ConcurrentHashMap<>();

    static {
        if (!ENABLED) {
            LOGGER.info("[SSOptimizer] Autofire batch disabled via -D" + ENABLED_PROPERTY + "=false");
        }
    }

    private AutofireBatchRunner() {
    }

    /**
     * 一帧内单把武器的开火决策快照。
     *
     * @param fire       收集时刻 {@code shouldFire()} 的结果
     * @param aim        收集时刻 {@code getTarget()} 的拷贝（插件内部可能复用同一 Vector2f）
     * @param targetShip 收集时刻 {@code getTargetShip()} 的结果，应用期写入 shipTarget
     */
    public record Decision(boolean fire, Vector2f aim, Ship targetShip) {
    }

    /**
     * 收集阶段入口：在舰船 AI 屏障之后、实体 advance 之前由
     * {@code CombatEngineAiParallelMixin} 调用（主线程）。
     * <p>
     * 遍历全场舰船的自动开火组，把白名单插件的 advance + 决策读取投递到线程池，
     * 并在返回前等待全部任务完成（复用 AI 执行器的帧内屏障语义）。
     *
     * @param engine 当前战斗引擎
     * @param amount 本帧推进时长（秒）
     */
    public static void collectAndCompute(final CombatEngine engine, final float amount) {
        final AiParallelExecutor executor = ParallelAiDispatcher.executor();
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
            // 手动覆盖组：WeaponGroup.advance 中 var6 分支不会调用 advanceAuto
            final boolean playerControlled = ship == playerShip && ship.getShipAI() == null;
            for (WeaponGroupAPI groupApi : ship.getWeaponGroupsCopy()) {
                if (!(groupApi instanceof WeaponGroup group) || !group.isAutofiring()) {
                    continue;
                }
                if (playerControlled && ship.getSelectedGroup() == group) {
                    continue;
                }
                List<AutofireAIPlugin> plugins = group.getAIPlugins();
                if (plugins.isEmpty() || !allPluginsWhitelisted(plugins)) {
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

    /**
     * 查询本帧收集阶段记录的开火决策。仅主线程实体 advance 阶段调用。
     *
     * @param weapon 武器实体
     * @return 命中返回决策；未收集（未白名单/组回退/功能停用）返回 null
     */
    public static Decision getDecision(final Weapon weapon) {
        return DECISIONS.get(weapon);
    }

    /**
     * 组级原子性检查：组内全部插件命中白名单才允许收集。
     */
    static boolean allPluginsWhitelisted(final List<AutofireAIPlugin> plugins) {
        for (AutofireAIPlugin plugin : plugins) {
            if (!isPluginClassWhitelisted(plugin.getClass().getName())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 白名单谓词：按插件精确类名匹配（模组可能不存在，不能用类引用）。
     *
     * @param className 插件实现类的全限定名
     * @return 允许并行决策返回 true
     */
    static boolean isPluginClassWhitelisted(final String className) {
        return PLUGIN_WHITELIST.contains(className);
    }

    /**
     * 单个插件的并行收集任务：先 advance 再立即读取决策快照。
     */
    private static void collect(final AutofireAIPlugin plugin, final float amount) {
        plugin.advance(amount);
        Vector2f aim = plugin.getTarget();
        DECISIONS.put((Weapon) plugin.getWeapon(), new Decision(
                plugin.shouldFire(),
                aim == null ? null : new Vector2f(aim),
                (Ship) plugin.getTargetShip()));
    }
}
