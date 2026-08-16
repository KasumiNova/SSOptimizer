package github.kasuminova.ssoptimizer.common.combat.ai;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 舰船 AI 共享静态状态的线程本地化助手。
 * <p>
 * 动机：原版 {@code AIUtils} 以静态字段充当 AI 计算期的临时状态
 * （blockingShips 缓存、aimErrorOffset1/2 瞄偏参数、名为 {@code null} 的布尔开关），
 * 串行时代安全；AI 并行化后这些状态必须按线程隔离，否则工作线程间互相覆盖。
 * ASM 处理器把 {@code AIUtils} 与 {@code AttackAIModule} 内对这些字段的
 * GETSTATIC/PUTSTATIC 访问重定向到本类的对应方法。
 * <p>
 * 帧失效：blockingShips 缓存原版每帧由主线程调用 clear/reset 清空；并行化后
 * 主线程的清空触达不到工作线程的 ThreadLocal，因此引入帧号——
 * {@link ParallelAiDispatcher#awaitAll()}（帧内屏障）每帧递增 {@link #nextFrame()}，
 * 工作线程读到帧号不匹配即视为未缓存。
 * <p>
 * 注意：本类方法签名（原始 {@code List} / {@code float} / {@code boolean}）与
 * 被重定向字段的描述符一一对应，改动必须同步修改 ASM 处理器。
 */
public final class AiThreadLocals {
    private static final AtomicLong FRAME_ID = new AtomicLong();

    private static final ThreadLocal<BlockingShipsCache> BLOCKING_SHIPS =
            ThreadLocal.withInitial(BlockingShipsCache::new);
    /** 索引 0 = aimErrorOffset1，索引 1 = aimErrorOffset2；数组避免装箱分配。 */
    private static final ThreadLocal<float[]> AIM_ERROR_OFFSETS = ThreadLocal.withInitial(() -> new float[2]);
    /** 索引 0 = AIUtils 中名为 {@code null} 的布尔开关；数组避免装箱分配。 */
    private static final ThreadLocal<boolean[]> NULL_FLAG = ThreadLocal.withInitial(() -> new boolean[1]);

    private AiThreadLocals() {
    }

    /**
     * 推进帧号，使所有工作线程上一帧的 blockingShips 缓存失效。
     * 仅由 AI 屏障（awaitAll）在完成时调用。
     */
    public static void nextFrame() {
        FRAME_ID.incrementAndGet();
    }

    /**
     * 对应 {@code AIUtils.blockingShips} 的读取；帧号不匹配时返回 {@code null}（等同未缓存）。
     */
    public static List getBlockingShips() {
        BlockingShipsCache cache = BLOCKING_SHIPS.get();
        return cache.frameId == FRAME_ID.get() ? cache.list : null;
    }

    /**
     * 对应 {@code AIUtils.blockingShips} 的写入（含 clear/reset 传入 {@code null} 的情形）。
     */
    public static void setBlockingShips(List list) {
        BlockingShipsCache cache = BLOCKING_SHIPS.get();
        cache.frameId = FRAME_ID.get();
        cache.list = list;
    }

    public static float getAimErrorOffset1() {
        return AIM_ERROR_OFFSETS.get()[0];
    }

    public static void setAimErrorOffset1(float value) {
        AIM_ERROR_OFFSETS.get()[0] = value;
    }

    public static float getAimErrorOffset2() {
        return AIM_ERROR_OFFSETS.get()[1];
    }

    public static void setAimErrorOffset2(float value) {
        AIM_ERROR_OFFSETS.get()[1] = value;
    }

    /**
     * 对应 {@code AIUtils} 中名为 {@code null} 的布尔静态字段的读取。
     */
    public static boolean getNullFlag() {
        return NULL_FLAG.get()[0];
    }

    /**
     * 对应 {@code AIUtils} 中名为 {@code null} 的布尔静态字段的写入。
     */
    public static void setNullFlag(boolean value) {
        NULL_FLAG.get()[0] = value;
    }

    /** blockingShips 的线程本地缓存：帧号 + 列表。 */
    private static final class BlockingShipsCache {
        private long frameId = -1L;
        private List list;
    }
}
