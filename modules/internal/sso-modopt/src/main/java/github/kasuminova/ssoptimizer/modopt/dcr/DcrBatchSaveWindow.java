package github.kasuminova.ssoptimizer.modopt.dcr;

/**
 * 描述 DCR 读档裁剪循环的批量保存窗口。
 * <p>循环对每条战报调用一次 {@code saveCombatResult}，每次都序列化并压缩整份列表，耗时随条数平方增长。
 * 窗口按预期调用次数计数，只有最后一次保存真正序列化，列表与存档内容和逐条保存的最终结果一致。</p>
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
        if (owner != Thread.currentThread()) {
            return false;
        }
        remaining--;
        if (remaining > 0) {
            return true;
        }
        end();
        return false;
    }

    public static synchronized void end() {
        owner = null;
        remaining = 0;
    }
}
