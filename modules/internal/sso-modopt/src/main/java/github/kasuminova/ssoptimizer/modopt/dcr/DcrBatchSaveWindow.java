package github.kasuminova.ssoptimizer.modopt.dcr;

/**
 * 描述 DCR 读档裁剪循环的批量保存窗口。
 * <p>循环对每条战报调用一次 {@code saveCombatResult}，每次都序列化并压缩整份列表，耗时随条数平方增长。
 * 窗口按预期调用次数计数，只有最后一次保存真正序列化。全部保存都未抛出异常时，列表与存档内容和逐条保存的最终结果一致；
 * 最后一次保存失败时，存档不会保留逐条保存留下的中间快照，原方法照常记录错误并返回。
 * 窗口在最后一次保存登记时自行关闭，且只能由持有线程关闭，不支持重入。</p>
 */
public final class DcrBatchSaveWindow {
    private static Thread owner;
    private static int remaining;

    private DcrBatchSaveWindow() {
    }

    /**
     * 开启窗口。
     *
     * @param pendingSaves 循环将要发起的保存次数，不足 2 次时无需合并，窗口保持关闭
     */
    public static synchronized void begin(final int pendingSaves) {
        if (pendingSaves < 2) {
            end();
            return;
        }
        owner = Thread.currentThread();
        remaining = pendingSaves;
    }

    /**
     * 登记一次保存并判断是否可以跳过序列化。
     *
     * @return {@code true} 表示本次保存不是窗口内最后一次，应跳过序列化与落盘
     */
    public static synchronized boolean deferCurrentSave() {
        final boolean deferred;
        if (owner != Thread.currentThread()) {
            deferred = false;
        } else {
            remaining--;
            deferred = remaining > 0;
            if (!deferred) {
                clear();
            }
        }
        DEFERRED.set(deferred);
        return deferred;
    }

    /**
     * 本线程最近一次登记的保存是否被判定为跳过。登记发生在保存入口，保存内部抛出异常也不会改变计数。
     */
    public static boolean isCurrentSaveDeferred() {
        return DEFERRED.get();
    }

    /** 仅窗口持有线程可以关闭窗口，其他线程的调用无效。 */
    public static synchronized void end() {
        if (owner == null || owner == Thread.currentThread()) {
            clear();
        }
    }

    private static void clear() {
        owner = null;
        remaining = 0;
    }

    private static final ThreadLocal<Boolean> DEFERRED = ThreadLocal.withInitial(() -> false);
}
