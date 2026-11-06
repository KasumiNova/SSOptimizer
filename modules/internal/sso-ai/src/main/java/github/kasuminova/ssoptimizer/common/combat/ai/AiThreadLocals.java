package github.kasuminova.ssoptimizer.common.combat.ai;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code AIUtils} 与 {@code AttackAIModule} 共享静态临时状态的线程本地存储。
 * {@code blockingShips} 缓存按帧号失效，访问方法由对应 Mixin 调用且必须保持字段描述符一致。
 */
public final class AiThreadLocals {
    private static final AtomicLong FRAME_ID = new AtomicLong();

    private static final ThreadLocal<BlockingShipsCache> BLOCKING_SHIPS =
            ThreadLocal.withInitial(BlockingShipsCache::new);
    /** 索引 0 和 1 分别对应两个瞄偏参数，数组用于避免装箱分配。 */
    private static final ThreadLocal<float[]> AIM_ERROR_OFFSETS = ThreadLocal.withInitial(() -> new float[2]);
    /** 索引 0 对应 {@code AIUtils} 中名为 {@code null} 的布尔开关。 */
    private static final ThreadLocal<boolean[]> NULL_FLAG = ThreadLocal.withInitial(() -> new boolean[1]);

    private AiThreadLocals() {
    }

    /** 仅在帧内 AI 屏障完成后调用，使所有工作线程的上一帧缓存失效。 */
    public static void nextFrame() {
        FRAME_ID.incrementAndGet();
    }

    public static List getBlockingShips() {
        BlockingShipsCache cache = BLOCKING_SHIPS.get();
        return cache.frameId == FRAME_ID.get() ? cache.list : null;
    }

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

    public static boolean getNullFlag() {
        return NULL_FLAG.get()[0];
    }

    public static void setNullFlag(boolean value) {
        NULL_FLAG.get()[0] = value;
    }

    private static final class BlockingShipsCache {
        private long frameId = -1L;
        private List list;
    }
}
