package github.kasuminova.ssoptimizer.common.combat;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.combat.CollisionEntity;
import com.fs.starfarer.combat.Damage;
import com.fs.util.container.repo.ObjectRepository;
import org.apache.log4j.Logger;
import org.lwjgl.util.vector.Vector2f;

import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 检查战斗数值中的 NaN/Inf，按检查点拒绝写入、取消伤害或归零运动分量，并输出限流诊断日志。
 * 位置没有安全恢复值，仅记录；日志按调用链签名与弱引用实体样本去重，状态由 {@link #SITES} 锁保护。
 */
public final class CombatNaNGuard {
    static final int SITE_MOTION = 1;
    static final int SITE_DAMAGE = 2;
    static final int SITE_HITPOINTS = 4;
    static final int SITE_FLUX = 8;
    static final int SITE_COLLISION = 16;
    static final int SITE_DAMAGE_INNER = 32;

    static final int SIGNATURE_SAMPLE_LIMIT = 3;
    static final int SIGNATURE_FRAMES = 8;
    static volatile long SUMMARY_INTERVAL_MS = 60_000L;

    private static final Logger LOGGER = Logger.getLogger(CombatNaNGuard.class);

    private static final Map<String, SiteStats> SITES = new ConcurrentHashMap<>();

    private static long lastSummaryMillis = System.currentTimeMillis();

    private CombatNaNGuard() {
    }

    enum ReportLevel {
        FULL,
        NOTICE,
        SUPPRESS
    }

    public static boolean isBad(final float value) {
        return Float.isNaN(value) || Float.isInfinite(value);
    }

    public static boolean anyBad(final float... values) {
        for (final float value : values) {
            if (isBad(value)) {
                return true;
            }
        }
        return false;
    }

    /** 必须在原版运动积分前调用，避免 Inf 速度经限速计算转化为 NaN。 */
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
        final String sample = describe(entity)
                + " velocity=" + velocity + " angularVelocity=" + entity.getAngularVelocity()
                + " facing=" + entity.getFacing() + " location=" + location
                + (locationBad ? "（位置无安全恢复值，仅记录）" : "");
        report(hit(SITE_MOTION, entity, sample),
                "[SSOptimizer] NaN/Inf 运动状态已自愈: " + sample,
                "[SSOptimizer] 运动守卫签名后续命中已抑制，仅保留周期汇总");
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

    public static boolean shouldDiscardDamage(final Damage damage, final float damageMult,
                                              final Object source, final Object ship) {
        final float dealt = damage.computeDamageDealt(damageMult);
        final float fluxDealt = damage.computeFluxDealt(damageMult);
        if (!anyBad(dealt, fluxDealt)) {
            return false;
        }
        final Object key = source != null ? source : ship;
        final String sample = "dealt=" + dealt + " fluxDealt=" + fluxDealt
                + " damageType=" + damage.getType() + " source=" + describe(source)
                + " target=" + describe(ship);
        report(hit(SITE_DAMAGE, key, sample),
                "[SSOptimizer] NaN/Inf 伤害事件已丢弃: " + sample,
                "[SSOptimizer] 伤害入口守卫签名后续命中已抑制，仅保留周期汇总");
        return true;
    }

    /** 在护盾结算首次副作用前调用，返回 true 时取消整个伤害事件。 */
    public static boolean shouldDiscardShieldDamage(final float fluxEquivalent, final float emp,
                                                    final Damage damage, final float damageMult,
                                                    final Object source, final Object ship) {
        if (!anyBad(fluxEquivalent, emp)) {
            return false;
        }
        final Object key = source != null ? source : ship;
        final String sample = "shieldFlux=" + fluxEquivalent + " emp=" + emp
                + " damageType=" + damage.getType() + " damageMult=" + damageMult
                + " source=" + describe(source) + " target=" + describe(ship);
        report(hit(SITE_DAMAGE_INNER, key, sample),
                "[SSOptimizer] NaN/Inf 护盾伤害事件已整单丢弃: " + sample,
                "[SSOptimizer] 护盾伤害守卫签名后续命中已抑制，仅保留周期汇总");
        return true;
    }

    /** 在装甲与结构结算首次副作用前调用，返回 true 时取消整个伤害事件。 */
    public static boolean shouldDiscardArmorDamage(final float dealt, final float armorDealt,
                                                   final float emp,
                                                   final Damage damage, final float damageMult,
                                                   final Object source, final Object ship) {
        if (!anyBad(dealt, armorDealt, emp)) {
            return false;
        }
        final Object key = source != null ? source : ship;
        final String sample = "dealt=" + dealt + " armorDealt=" + armorDealt + " emp=" + emp
                + " damageType=" + damage.getType() + " damageMult=" + damageMult
                + " source=" + describe(source) + " target=" + describe(ship);
        report(hit(SITE_DAMAGE_INNER, key, sample),
                "[SSOptimizer] NaN/Inf 装甲/结构伤害事件已整单丢弃: " + sample,
                "[SSOptimizer] 装甲伤害守卫签名后续命中已抑制，仅保留周期汇总");
        return true;
    }

    public static boolean shouldRejectHitpoints(final float value, final Object entity) {
        if (!isBad(value)) {
            return false;
        }
        final String sample = describe(entity) + " value=" + value;
        report(hit(SITE_HITPOINTS, entity, sample),
                "[SSOptimizer] NaN/Inf 结构值写入已拒绝: " + sample,
                "[SSOptimizer] 结构守卫签名后续命中已抑制，仅保留周期汇总");
        return true;
    }

    public static boolean shouldRejectFlux(final float amount, final Object tracker, final Object owner) {
        if (!isBad(amount)) {
            return false;
        }
        final String sample = "amount=" + amount + " owner=" + describe(owner);
        report(hit(SITE_FLUX, tracker, sample),
                "[SSOptimizer] NaN/Inf 辐能写入已拒绝: " + sample,
                "[SSOptimizer] 辐能守卫签名后续命中已抑制，仅保留周期汇总");
        return true;
    }

    public static void traceCollisionImpulse(final CollisionEntity a, final CollisionEntity b,
                                             final Vector2f point) {
        final boolean pointBad = isBad(point.x) || isBad(point.y);
        if (!pointBad && !isBad(a.getVelocity().x) && !isBad(a.getVelocity().y)
                && !isBad(b.getVelocity().x) && !isBad(b.getVelocity().y)) {
            return;
        }
        final String sample = "point=" + point + " a=" + describe(a) + " b=" + describe(b);
        final ReportLevel la = hit(SITE_COLLISION, a, sample);
        final ReportLevel lb = hit(SITE_COLLISION, b, sample);
        report(la.ordinal() < lb.ordinal() ? la : lb,
                "[SSOptimizer] 碰撞冲量涉 NaN/Inf: " + sample,
                "[SSOptimizer] 碰撞追踪签名后续命中已抑制，仅保留周期汇总");
    }

    /** 在冲量写入双方运动状态前调用，返回 true 时按零冲量处理。 */
    public static boolean shouldClampImpulse(final float impulse, final CollisionEntity a,
                                             final CollisionEntity b) {
        if (!isBad(impulse)) {
            return false;
        }
        final String sample = "impulse=" + impulse + " a=" + describe(a) + " b=" + describe(b);
        report(hit(SITE_COLLISION, a, sample),
                "[SSOptimizer] NaN/Inf 碰撞冲量已钳为无冲量: " + sample,
                "[SSOptimizer] 碰撞冲量钳制签名后续命中已抑制，仅保留周期汇总");
        return true;
    }

    static ReportLevel hit(final int siteBit, final Object key, final String sample) {
        final String signature = signature(siteBit);
        final SiteStats stats = SITES.computeIfAbsent(signature, k -> new SiteStats());
        synchronized (SITES) {
            final long now = System.currentTimeMillis();
            stats.total++;
            if (stats.total == 1) {
                stats.firstMillis = now;
                stats.sample = sample;
            }
            stats.lastMillis = now;
            emitSummaryLocked(now);
            if (!stats.entities.add(key)) {
                // 同一实体的重复命中：样本已取证过，只计数
                stats.suppressed++;
                return ReportLevel.SUPPRESS;
            }
            if (stats.samples < SIGNATURE_SAMPLE_LIMIT) {
                stats.samples++;
                return ReportLevel.FULL;
            }
            if (!stats.noticed) {
                stats.noticed = true;
                return ReportLevel.NOTICE;
            }
            stats.suppressed++;
            return ReportLevel.SUPPRESS;
        }
    }

    /** 签名由检查点和过滤后的业务栈帧组成，不含行号、线程名与 Mixin 回调帧。 */
    private static String signature(final int siteBit) {
        final StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        final StringBuilder sb = new StringBuilder(160).append(siteBit);
        int taken = 0;
        for (int i = 0; i < stack.length && taken < SIGNATURE_FRAMES; i++) {
            final StackTraceElement frame = stack[i];
            final String className = frame.getClassName();
            final String methodName = frame.getMethodName();
            if (className.startsWith("java.lang.Thread")
                    || className.equals(CombatNaNGuard.class.getName())
                    || className.startsWith(CombatNaNGuard.class.getName() + "$")
                    || className.startsWith("org.spongepowered.")) {
                continue;
            }
            if (methodName.startsWith("handler$") || methodName.contains("$ssoptimizer$")) {
                continue;
            }
            sb.append('<').append(className).append('.').append(methodName);
            taken++;
        }
        return sb.toString();
    }

    private static void report(final ReportLevel level, final String fullMessage, final String noticeMessage) {
        switch (level) {
            case FULL:
                LOGGER.error(fullMessage, new Throwable("[SSOptimizer] NaN 哨兵栈（非异常，仅用于调用链追溯）"));
                break;
            case NOTICE:
                LOGGER.warn(noticeMessage);
                break;
            case SUPPRESS:
            default:
                break;
        }
    }

    private static void emitSummaryLocked(final long now) {
        if (now - lastSummaryMillis < SUMMARY_INTERVAL_MS) {
            return;
        }
        lastSummaryMillis = now;
        for (final Map.Entry<String, SiteStats> entry : SITES.entrySet()) {
            final SiteStats stats = entry.getValue();
            if (stats.total == stats.summarizedTotal) {
                continue;
            }
            LOGGER.warn("[SSOptimizer] NaN/Inf 哨兵周期汇总: 签名=" + entry.getKey()
                    + " 总命中=" + stats.total + " 已抑制=" + stats.suppressed
                    + " 首次=" + new Date(stats.firstMillis) + " 末次=" + new Date(stats.lastMillis)
                    + " 样本=" + stats.sample);
            stats.summarizedTotal = stats.total;
        }
    }

    static final class SiteStats {
        final Set<Object> entities = Collections.newSetFromMap(new WeakHashMap<>());
        long total;
        long suppressed;
        long firstMillis;
        long lastMillis;
        long summarizedTotal;
        int samples;
        boolean noticed;
        String sample;
    }

    static final class StatsSnapshot {
        final long total;
        final long suppressed;
        final long summarizedTotal;
        final int samples;
        final boolean noticed;

        StatsSnapshot(final SiteStats stats) {
            this.total = stats.total;
            this.suppressed = stats.suppressed;
            this.summarizedTotal = stats.summarizedTotal;
            this.samples = stats.samples;
            this.noticed = stats.noticed;
        }
    }

    static Map<String, StatsSnapshot> snapshotStats() {
        synchronized (SITES) {
            final Map<String, StatsSnapshot> snapshot = new LinkedHashMap<>();
            for (final Map.Entry<String, SiteStats> entry : SITES.entrySet()) {
                snapshot.put(entry.getKey(), new StatsSnapshot(entry.getValue()));
            }
            return snapshot;
        }
    }

    static void resetThrottle() {
        synchronized (SITES) {
            SITES.clear();
            lastSummaryMillis = System.currentTimeMillis();
        }
    }

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
