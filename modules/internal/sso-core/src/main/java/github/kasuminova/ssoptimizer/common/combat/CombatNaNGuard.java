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
 * 战斗数值 NaN/Inf 哨兵与自愈守卫。
 * <p>
 * 动机：原版战斗代码的阈值守卫全部使用 {@code <=}/{@code >=} 比较，NaN 在任何比较中
 * 都返回 false——一次 NaN 伤害写入即可让舰船结构值/辐能永久污染（{@code NaN<=0} 为 false
 * 不死、辐能不通超载不消散），位置 NaN 经碰撞冲量还会传染交战双方（无敌护盾、结构归零
 * 不爆炸、伤害无效等连锁症状）。原点（第一个 NaN/Inf）位于 MutableStat 叠乘链或模组
 * 数值路径，静态分析无法坐实，故本守卫采用「哨兵取证 + 原位自愈」双层策略：
 * <ul>
 *   <li>每个检查点按「调用链签名」限量输出完整取证日志（ERROR + 调用栈），用于定位原点；</li>
 *   <li>速度/角速度/朝向的 NaN/Inf 归零自愈（{@code CombatEngine.advanceObjects} 是全实体
 *       每帧积分唯一收口，原版 {@code scale(600/Inf)=scale(0)} 会把 Inf 转化为 NaN）；</li>
 *   <li>NaN/Inf 伤害事件整单丢弃（{@code Ship.applyDamageInner} 入口 HEAD + 修正链完成后
 *       第二阶段检查），阻断结构/辐能污染入口且不留「半事件」；</li>
 *   <li>NaN/Inf 的结构值与辐能写入直接拒绝（{@code setHitpoints}/{@code increaseFlux}/
 *       {@code setCurrFlux}/{@code setMinFlux} 收口兜底）；</li>
 *   <li>碰撞冲量 NaN/Inf 在写入双方速度前降级为「无冲量」（返回 0）。</li>
 * </ul>
 * 日志限流为「调用链签名」级：签名 = 检查点 + 守卫之后前若干业务栈帧的
 * {@code className.methodName}（去行号/线程名/mixin 胶水帧）。每签名前
 * {@link #SIGNATURE_SAMPLE_LIMIT} 个不同实体样本输出完整 ERROR + Throwable；
 * 首个超额样本输出一条 WARN 抑制通告；之后只累计计数；每 {@link #SUMMARY_INTERVAL_MS}
 * 输出一行周期汇总。实体样本去重使用弱引用键，不阻碍实体回收。
 * {@code CombatEngine} 是 JVM 生命周期单例（不按场重建），故节流状态不按场重置，
 * 仅依靠周期汇总与弱键回收控制规模。
 * <p>
 * 位置 NaN 不做自愈（无安全恢复值）：记录日志后由伤害链守卫兜底，避免污染扩散。
 */
public final class CombatNaNGuard {
    /** 检查点：运动积分（advanceObjects）。 */
    static final int SITE_MOTION = 1;
    /** 检查点：伤害入口（applyDamageInner HEAD，六参重载）。 */
    static final int SITE_DAMAGE = 2;
    /** 检查点：结构写入（setHitpoints）。 */
    static final int SITE_HITPOINTS = 4;
    /** 检查点：辐能写入（increaseFlux/setCurrFlux/setMinFlux）。 */
    static final int SITE_FLUX = 8;
    /** 检查点：碰撞冲量（applyCollisionImpulse，HEAD 追踪 + 写入前钳制）。 */
    static final int SITE_COLLISION = 16;
    /** 检查点：伤害结算第二阶段（applyDamageInner 修正链完成后、首次副作用前）。 */
    static final int SITE_DAMAGE_INNER = 32;

    /** 每签名允许完整取证的实体样本数。 */
    static final int SIGNATURE_SAMPLE_LIMIT = 3;
    /** 签名携带的业务栈帧数（守卫帧与 mixin 胶水帧已剔除）。 */
    static final int SIGNATURE_FRAMES = 8;
    /** 周期汇总间隔（毫秒）。volatile 以便测试缩短。 */
    static volatile long SUMMARY_INTERVAL_MS = 60_000L;

    private static final Logger LOGGER = Logger.getLogger(CombatNaNGuard.class);

    /** 签名 → 节流统计。全部状态变更在 {@link #SITES} 锁内完成（坏值命中极罕见，无争用问题）。 */
    private static final Map<String, SiteStats> SITES = new ConcurrentHashMap<>();

    /** 上次周期汇总时间。 */
    private static long lastSummaryMillis = System.currentTimeMillis();

    private CombatNaNGuard() {
    }

    /** 单次命中的报告级别。 */
    enum ReportLevel {
        /** 完整取证：ERROR + Throwable 调用栈。 */
        FULL,
        /** 抑制通告：一条 WARN，说明该签名后续命中只计数。 */
        NOTICE,
        /** 完全抑制：只累计计数，等周期汇总。 */
        SUPPRESS
    }

    /** 数值是否为 NaN 或 Inf（两者都会绕过原版比较守卫）。 */
    public static boolean isBad(final float value) {
        return Float.isNaN(value) || Float.isInfinite(value);
    }

    /** 纯函数：任一数值为 NaN/Inf 即 true。第二阶段守卫的判定核心，供直接测试。 */
    public static boolean anyBad(final float... values) {
        for (final float value : values) {
            if (isBad(value)) {
                return true;
            }
        }
        return false;
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

    /**
     * 伤害守卫第一阶段（入口 HEAD）：伤害/辐能预结算值为 NaN/Inf 时整单丢弃。
     * 覆盖第三方调用方直接传入坏 {@code Damage} 的路径；修正链内部产生的坏值由
     * 第二阶段守卫（{@link #shouldDiscardShieldDamage}/{@link #shouldDiscardArmorDamage}）覆盖。
     *
     * @return true 表示该伤害事件应被丢弃
     */
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

    /**
     * 伤害守卫第二阶段（护盾/辐能路径）：所有 damage-taken 修正链完成后、
     * 首次副作用（{@code Shield.shieldHit}）前调用。
     * <p>
     * 由 {@code ShipDamageStageTwoProcessor} 注入在护盾辐能当量（局部槽 13）最终写入之后；
     * 命中即取消整单返回空 {@code ApplyDamageResult}——护盾、辐能、floaty、listener
     * 全部跳过，不留半事件。
     *
     * @param fluxEquivalent 护盾辐能当量（槽 13，含全部 modifier）
     * @param emp            EMP/辐能值（槽 15）
     * @return true 表示该伤害事件应被整单丢弃
     */
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

    /**
     * 伤害守卫第二阶段（装甲/结构路径）：原版 {@code damage<=0 && flux<=0} 放行检查之后、
     * 首次副作用（{@code ArmorGrid.applyDamage}）前调用；同时覆盖 bypassShields 直伤分支。
     * <p>
     * 由 {@code ShipDamageStageTwoProcessor} 注入在该原版守卫的 fall-through 处；命中即取消
     * 整单返回空 {@code ApplyDamageResult}——装甲格、结构、EMP、组件损伤、listener 全部跳过。
     *
     * @param dealt 最终伤害（槽 13，含全部 modifier）
     * @param armorDealt 装甲伤害量（槽 17，ArmorGrid 实参）
     * @param emp   EMP/辐能值（槽 15）
     * @return true 表示该伤害事件应被整单丢弃
     */
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

    /**
     * 结构写入收口：拒绝 NaN/Inf 结构值（保留原值）。兜底所有绕过伤害守卫的写入路径。
     *
     * @return true 表示该写入应被拒绝
     */
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

    /**
     * 辐能写入收口：拒绝 NaN/Inf 辐能量（保留原值）。
     * 覆盖 {@code increaseFlux} 与直写入口 {@code setCurrFlux}/{@code setMinFlux}
     * （{@code setHardFlux} 委托 {@code setMinFlux}，单点覆盖）。
     *
     * @param owner 辐能条所属舰船（FluxTracker.ship），仅用于日志指纹
     * @return true 表示该写入应被拒绝
     */
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
        final String sample = "point=" + point + " a=" + describe(a) + " b=" + describe(b);
        final ReportLevel la = hit(SITE_COLLISION, a, sample);
        final ReportLevel lb = hit(SITE_COLLISION, b, sample);
        report(la.ordinal() < lb.ordinal() ? la : lb,
                "[SSOptimizer] 碰撞冲量涉 NaN/Inf: " + sample,
                "[SSOptimizer] 碰撞追踪签名后续命中已抑制，仅保留周期汇总");
    }

    /**
     * 碰撞冲量钳制：冲量标量（槽 16）算出后、写入双方速度/角速度之前调用。
     * NaN/Inf 时按「无冲量」降级——跳过全部速度写入并返回 0F（碰撞伤害随之归零），
     * 钳 0 不钳 {@code Float.MAX_VALUE}。
     * <p>
     * 由 {@code CollisionImpulseClampProcessor} 注入；冲量经双方速度传染是 NaN 的
     * 主要扩散通道，此处是唯一的写入前收口点。
     *
     * @return true 表示本次碰撞应降级为无冲量
     */
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

    /**
     * 节流核心：记录一次坏值命中并判定报告级别。
     * <p>
     * 先完成签名归一化与配额/计数判定，再由调用方决定是否构造 Throwable——
     * 抑制路径不产生任何取证据外开销之外的分配。
     *
     * @param siteBit 检查点位
     * @param key     实体样本去重键（弱引用持有）
     * @param sample  样本指纹文本（首命中与周期汇总输出）
     */
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

    /**
     * 调用链签名：检查点 + 守卫之后前 {@link #SIGNATURE_FRAMES} 个业务帧的
     * {@code className.methodName}。剔除本类与 mixin 胶水帧（{@code handler$} 随机前缀），
     * 不含行号与线程名，使同一污染源的命中聚成同一签名。
     */
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
                // Mixin 注入复制进目标类的回调帧（随机混淆前缀），非业务帧
                continue;
            }
            sb.append('<').append(className).append('.').append(methodName);
            taken++;
        }
        return sb.toString();
    }

    /** 按报告级别输出日志；仅 FULL 构造 Throwable（先判定后取证）。 */
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

    /** 周期汇总：每 {@link #SUMMARY_INTERVAL_MS} 为每个有新命中的签名输出一行汇总。 */
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

    /**
     * 单个签名的节流统计。实体样本键为弱引用——实体随战斗结束回收，条目随之失效；
     * 签名条目本身仅在有坏值命中时创建，规模受签名种类数约束，无需按场清理。
     */
    static final class SiteStats {
        /** 已取证的实体样本（弱引用键）。 */
        final Set<Object> entities = Collections.newSetFromMap(new WeakHashMap<>());
        /** 坏值命中总次数（每次命中即递增，与是否输出日志无关）。 */
        long total;
        /** 被抑制的次数。 */
        long suppressed;
        /** 首次/末次命中时间。 */
        long firstMillis;
        long lastMillis;
        /** 上次汇总时已统计到的 total 水位。 */
        long summarizedTotal;
        /** 已完整取证的实体样本数。 */
        int samples;
        /** 抑制通告是否已输出。 */
        boolean noticed;
        /** 首个样本指纹。 */
        String sample;
    }

    /** 节流统计只读快照（诊断与测试用）。 */
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

    /** 当前全部签名的统计快照（签名 → 快照）。 */
    static Map<String, StatsSnapshot> snapshotStats() {
        synchronized (SITES) {
            final Map<String, StatsSnapshot> snapshot = new LinkedHashMap<>();
            for (final Map.Entry<String, SiteStats> entry : SITES.entrySet()) {
                snapshot.put(entry.getKey(), new StatsSnapshot(entry.getValue()));
            }
            return snapshot;
        }
    }

    /** 清空节流状态（测试隔离用；生产路径无需调用，见类注释的单例论证）。 */
    static void resetThrottle() {
        synchronized (SITES) {
            SITES.clear();
            lastSummaryMillis = System.currentTimeMillis();
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
