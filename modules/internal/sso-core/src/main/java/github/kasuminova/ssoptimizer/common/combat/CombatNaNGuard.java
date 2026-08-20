package github.kasuminova.ssoptimizer.common.combat;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.combat.CollisionEntity;
import com.fs.starfarer.combat.Damage;
import com.fs.util.container.repo.ObjectRepository;
import org.apache.log4j.Logger;
import org.lwjgl.util.vector.Vector2f;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 战斗数值 NaN/Inf 哨兵与自愈守卫。
 * <p>
 * 动机：原版战斗代码的阈值守卫全部使用 {@code <=}/{@code >=} 比较，NaN 在任何比较中
 * 都返回 false——一次 NaN 伤害写入即可让舰船结构值/辐能永久污染（{@code NaN<=0} 为 false
 * 不死、辐能不通超载不消散），位置 NaN 经碰撞冲量还会传染交战双方（无敌护盾、结构归零
 * 不爆炸、伤害无效等连锁症状）。原点（第一个 NaN/Inf）位于 MutableStat 叠乘链或模组
 * 数值路径，静态分析无法坐实，故本守卫采用「哨兵取证 + 原位自愈」双层策略：
 * <ul>
 *   <li>每个检查点首次命中时输出 ERROR 日志（附实体指纹与调用栈），用于定位原点；</li>
 *   <li>速度/角速度/朝向的 NaN/Inf 归零自愈（{@code CombatEngine.advanceObjects} 是全实体
 *       每帧积分唯一收口，原版 {@code scale(600/Inf)=scale(0)} 会把 Inf 转化为 NaN）；</li>
 *   <li>NaN/Inf 伤害事件整单丢弃（{@code Ship.applyDamageInner}），阻断结构/辐能污染入口；</li>
 *   <li>NaN/Inf 的结构值与辐能写入直接拒绝（{@code setHitpoints}/{@code increaseFlux} 收口兜底）。</li>
 * </ul>
 * 日志按「实体 × 检查点」去重（弱引用键，不阻碍实体回收），同一污染源不重复刷屏。
 * <p>
 * 位置 NaN 不做自愈（无安全恢复值）：记录日志后由伤害链守卫兜底，避免污染扩散。
 */
public final class CombatNaNGuard {
    /** 检查点：运动积分（advanceObjects）。 */
    static final int SITE_MOTION = 1;
    /** 检查点：伤害入口（applyDamageInner）。 */
    static final int SITE_DAMAGE = 2;
    /** 检查点：结构写入（setHitpoints）。 */
    static final int SITE_HITPOINTS = 4;
    /** 检查点：辐能写入（increaseFlux）。 */
    static final int SITE_FLUX = 8;
    /** 检查点：碰撞冲量（applyCollisionImpulse，仅追踪不拦截）。 */
    static final int SITE_COLLISION = 16;

    private static final Logger LOGGER = Logger.getLogger(CombatNaNGuard.class);

    /** 「实体/来源 × 检查点」的已报告位掩码（弱引用键，同步访问）。 */
    private static final Map<Object, Integer> REPORTED = Collections.synchronizedMap(new WeakHashMap<>());

    private CombatNaNGuard() {
    }

    /** 数值是否为 NaN 或 Inf（两者都会绕过原版比较守卫）。 */
    public static boolean isBad(final float value) {
        return Float.isNaN(value) || Float.isInfinite(value);
    }

    /**
     * 运动守卫：每帧对每个碰撞实体检查速度/角速度/朝向/位置，NaN/Inf 分量归零自愈
     * （位置 NaN 仅记录）。必须在 {@code CombatEngine.advanceObjects} 头部调用——
     * 原版限速器 {@code scale(600/Inf)} 会把 Inf 速度转化为 NaN，必须先于它拦截。
     */
    public static void checkAllMotion(final ObjectRepository repo) {
        for (Object obj : repo.getList(CollisionEntity.class)) {
            checkMotion((CollisionEntity) obj);
        }
    }

    static void checkMotion(final CollisionEntity entity) {
        final Vector2f velocity = entity.getVelocity();
        final boolean velocityBad = isBad(velocity.x) || isBad(velocity.y);
        final boolean angularBad = isBad(entity.getAngularVelocity());
        final boolean facingBad = isBad(entity.getFacing());
        final Vector2f location = entity.getLocation();
        final boolean locationBad = isBad(location.x) || isBad(location.y);
        if (!velocityBad && !angularBad && !facingBad && !locationBad) {
            return;
        }
        if (shouldReport(entity, SITE_MOTION)) {
            LOGGER.error("[SSOptimizer] NaN/Inf 运动状态已自愈: " + describe(entity)
                            + " velocity=" + velocity + " angularVelocity=" + entity.getAngularVelocity()
                            + " facing=" + entity.getFacing() + " location=" + location
                            + (locationBad ? "（位置无安全恢复值，仅记录）" : ""),
                    new Throwable("[SSOptimizer] NaN 哨兵栈（非异常，仅用于调用链追溯）"));
        }
        if (velocityBad) {
            velocity.set(0.0F, 0.0F);
        }
        if (angularBad) {
            entity.setAngularVelocity(0.0F);
        }
        if (facingBad) {
            entity.setFacing(0.0F);
        }
    }

    /**
     * 伤害守卫：伤害/辐能结算值为 NaN/Inf 时整单丢弃（防止结构与辐能链污染）。
     * 返回值指纹（基准伤害、倍率、类型）随首次日志输出，直接指认产生 NaN 的武器/技能。
     *
     * @return true 表示该伤害事件应被丢弃
     */
    public static boolean shouldDiscardDamage(final Damage damage, final float damageMult,
                                              final Object source, final Object ship) {
        final float dealt = damage.computeDamageDealt(damageMult);
        final float fluxDealt = damage.computeFluxDealt(damageMult);
        if (!isBad(dealt) && !isBad(fluxDealt)) {
            return false;
        }
        final Object key = source != null ? source : ship;
        if (shouldReport(key, SITE_DAMAGE)) {
            LOGGER.error("[SSOptimizer] NaN/Inf 伤害事件已丢弃: dealt=" + dealt + " fluxDealt=" + fluxDealt
                            + " damageType=" + damage.getType() + " source=" + describe(source)
                            + " target=" + describe(ship),
                    new Throwable("[SSOptimizer] NaN 哨兵栈（非异常，仅用于调用链追溯）"));
        }
        return true;
    }

    /**
     * 结构写入收口：拒绝 NaN/Inf 结构值（保留原值）。兜底所有绕过伤害守卫的写入路径。
     *
     * @return true 表示该写入应被拒绝
     */
    public static boolean shouldRejectHitpoints(final float value, final Object entity) {
        if (!isBad(value)) {
            return false;
        }
        if (shouldReport(entity, SITE_HITPOINTS)) {
            LOGGER.error("[SSOptimizer] NaN/Inf 结构值写入已拒绝: " + describe(entity) + " value=" + value,
                    new Throwable("[SSOptimizer] NaN 哨兵栈（非异常，仅用于调用链追溯）"));
        }
        return true;
    }

    /**
     * 辐能写入收口：拒绝 NaN/Inf 辐能量。兜底所有非伤害来源的辐能写入。
     *
     * @return true 表示该写入应被拒绝
     */
    public static boolean shouldRejectFlux(final float amount, final Object tracker) {
        if (!isBad(amount)) {
            return false;
        }
        if (shouldReport(tracker, SITE_FLUX)) {
            LOGGER.error("[SSOptimizer] NaN/Inf 辐能写入已拒绝: amount=" + amount,
                    new Throwable("[SSOptimizer] NaN 哨兵栈（非异常，仅用于调用链追溯）"));
        }
        return true;
    }

    /**
     * 碰撞冲量追踪（仅日志）：碰撞点或参与方速度为 NaN/Inf 时记录双方实体，
     * 用于区分运动守卫日志中的实体是 NaN 原发还是被碰撞传染。
     */
    public static void traceCollisionImpulse(final CollisionEntity a, final CollisionEntity b,
                                             final Vector2f point) {
        final boolean pointBad = isBad(point.x) || isBad(point.y);
        if (!pointBad && !isBad(a.getVelocity().x) && !isBad(a.getVelocity().y)
                && !isBad(b.getVelocity().x) && !isBad(b.getVelocity().y)) {
            return;
        }
        if (shouldReport(a, SITE_COLLISION) | shouldReport(b, SITE_COLLISION)) {
            LOGGER.error("[SSOptimizer] 碰撞冲量涉 NaN/Inf: point=" + point
                            + " a=" + describe(a) + " b=" + describe(b),
                    new Throwable("[SSOptimizer] NaN 哨兵栈（非异常，仅用于调用链追溯）"));
        }
    }

    /**
     * 「键 × 检查点」首次命中判定：未报告过则记录并返回 true。
     * 弱引用键同步表——实体随战斗结束回收，条目随之失效。
     */
    static boolean shouldReport(final Object key, final int siteBit) {
        synchronized (REPORTED) {
            final Integer flags = REPORTED.get(key);
            if (flags != null && (flags & siteBit) != 0) {
                return false;
            }
            REPORTED.put(key, (flags == null ? 0 : flags) | siteBit);
            return true;
        }
    }

    /** 实体指纹：类名 + 舰船名/船体 id（可辨识到具体舰船与来源模组）。 */
    private static String describe(final Object obj) {
        if (obj == null) {
            return "null";
        }
        if (obj instanceof ShipAPI) {
            final ShipAPI ship = (ShipAPI) obj;
            return obj.getClass().getSimpleName() + "[" + ship.getName()
                    + " / " + (ship.getHullSpec() == null ? "?" : ship.getHullSpec().getHullId()) + "]";
        }
        return obj.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(obj));
    }
}
