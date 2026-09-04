package github.kasuminova.ssoptimizer.common.campaign;

import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code BaseLocation.advance}「Checking combat initiation」段的两两配对格网分桶 helper。
 * <p>
 * 职责：把原版 O(F²) 全配对枚举剪枝为「同格 + 邻格」配对枚举，
 * 同时保证被剪掉的配对在原版语义下必然无任何效果——结果集合与原版完全一致。<br>
 * 等价性依据（named 源码实证）：
 * <ul>
 *   <li>配对体中只有两条路径依赖两舰队间距离：交战/遭遇创建
 *       （{@code var43 < var48}，var48 = 双方 selectionSize 之和，fast advance 时 +1000）。
 *       selectionSize ≤ 本 location 舰队最大 selectionSize，故配对距离上限
 *       T = 2·maxSelectionSize + 1000；格网 cell 边长取 T，
 *       两格任一轴相差 ≥2 时两舰队距离必 &gt; T，距离出口必然不命中，配对无效果。</li>
 *   <li>战斗接力路径（空间站支援 join / AI wantsToJoin join）判定的是
 *       「战斗已参与舰队」与配对另一方的距离，与配对双方距离无界——因此凡是
 *       「恰好一方在战斗中」（battle XOR）的配对一律保留考察，不做距离剪枝。</li>
 *   <li>战斗成员资格在本段内单调递增（只有 {@code join}/{@code new Battle} 新增，无移除），
 *       helper 在每次配对考察后回查双方最新战斗状态，追踪「考察中途加入战斗」的舰队，
 *       保证 live XOR 判定与原版的惰性求值逐对一致。</li>
 *   <li>枚举顺序严格保持原版（外层索引升序、内层索引升序），
 *       {@code var22}（遭遇已触发标志）等顺序敏感语义不变。</li>
 * </ul>
 * 前提假设：本段循环体内不存在改变舰队坐标的副作用（原版各 join/遭遇路径均不位移舰队；
 * 监听器若在回调中传送舰队属极端越界行为，原版该场景下结果本身即无意义）。<br>
 * 回退开关：{@value #ENABLED_PROPERTY}=false 时 Mixin 完全走原版双层循环（默认 true=启用）。<br>
 * 线程模型：仅在战役主线程由 {@code BaseLocation.advance} 调用，实现单线程确定。
 */
public final class CombatPairingGridHelper {
    /** 分桶配对开关系统属性名。 */
    public static final String ENABLED_PROPERTY = "ssoptimizer.campaign.combatPairing";
    /** 默认启用。 */
    public static final boolean DEFAULT_ENABLED = true;

    private static final Logger LOGGER = Logger.getLogger(CombatPairingGridHelper.class);

    private static final boolean ENABLED = parseEnabled(System.getProperty(ENABLED_PROPERTY));

    private CombatPairingGridHelper() {
    }

    /**
     * @return 分桶配对是否启用（类初始化时解析一次）
     */
    public static boolean isEnabled() {
        return ENABLED;
    }

    /**
     * 解析开关属性值。
     * <p>
     * 未设置时返回 {@link #DEFAULT_ENABLED}；仅识别 {@code "true"}/{@code "false"}
     * （大小写不敏感），其他取值按默认值处理并记 WARN 日志。
     *
     * @param raw 属性原始值（可为 {@code null}）
     * @return 生效的开关值
     */
    public static boolean parseEnabled(final String raw) {
        if (raw == null) {
            return DEFAULT_ENABLED;
        }
        if ("true".equalsIgnoreCase(raw)) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw)) {
            return false;
        }
        LOGGER.warn("[SSOptimizer] " + ENABLED_PROPERTY + " 取值 \"" + raw
                + "\" 无法识别（仅支持 true/false），按默认 " + DEFAULT_ENABLED + " 处理");
        return DEFAULT_ENABLED;
    }

    /**
     * 配对考察回调。
     */
    public interface PairExaminer {
        /**
         * 外层舰队准入判定，每个外层索引在配对迭代前恰好调用一次。
         * <p>
         * 对应原版外层循环头的 {@code var29.canBeEngaged()}（原版每个 i 只求值一次，
         * 中途变化不影响本次外层迭代，故此处也只在头部求值一次）。
         *
         * @param index 外层舰队索引
         * @return 该外层舰队是否参与配对
         */
        boolean canLead(int index);

        /**
         * 考察一对舰队（i &lt; j，枚举顺序与原版一致）。
         * <p>
         * 实现对应原版内层循环体（含 {@code var38.canBeEngaged()} 惰性判定）；
         * 回调允许触发战斗加入（join / new Battle），helper 会在回调返回后
         * 回查双方战斗状态并纳入后续枚举。
         *
         * @param first  外层舰队索引
         * @param second 内层舰队索引
         */
        void examine(int first, int second);
    }

    /**
     * 战斗状态实时查询（对应 {@code fleet.getBattle() != null}）。
     */
    public interface BattleState {
        /**
         * @param index 舰队索引
         * @return 该舰队当前是否处于战斗中（实时值，配对体 join 后应反映最新状态）
         */
        boolean isInBattle(int index);
    }

    /**
     * 枚举全部「需要考察」的配对（i &lt; j，外层升序、内层升序，与原版顺序一致）。
     * <p>
     * 被枚举的配对 = 同格/邻格配对 ∪ 考察时点仍成立 battle XOR 的配对
     * （含考察中途加入战斗产生的新 XOR 配对；远距候选在考察时点复核 live XOR，
     * 已消失的精确跳过）。其余配对在原版语义下必然无效果（距离出口与接力出口
     * 均不命中），剪枝不改变结果。
     *
     * @param xs               各舰队 x 坐标快照
     * @param ys               各舰队 y 坐标快照
     * @param snapshotInBattle 各舰队进入本段时的战斗状态快照
     * @param cellSize         格网边长，必须 ≥ 2·maxSelectionSize + 1000（fast advance 余量）
     * @param live             战斗状态实时查询
     * @param examiner         配对考察回调
     */
    public static void forEachCandidatePair(final float[] xs, final float[] ys,
                                            final boolean[] snapshotInBattle, final float cellSize,
                                            final BattleState live, final PairExaminer examiner) {
        final int n = xs.length;
        if (n < 2) {
            return;
        }
        if (ys.length != n || snapshotInBattle.length != n) {
            throw new IllegalArgumentException("xs/ys/snapshotInBattle 长度必须一致");
        }
        if (cellSize <= 0.0F) {
            throw new IllegalArgumentException("cellSize 必须为正: " + cellSize);
        }

        // 格网分桶：按索引升序入桶，各桶内索引天然升序
        final int[] cellX = new int[n];
        final int[] cellY = new int[n];
        final Map<Long, List<Integer>> buckets = new HashMap<>();
        for (int i = 0; i < n; i++) {
            cellX[i] = (int) Math.floor(xs[i] / cellSize);
            cellY[i] = (int) Math.floor(ys[i] / cellSize);
            buckets.computeIfAbsent(bucketKey(cellX[i], cellY[i]), k -> new ArrayList<>()).add(i);
        }

        // 快照战斗侧索引（升序）；liveBattle 追踪快照 ∪ 考察中途加入
        final int[] snapshotBattle = new int[n];
        int snapshotBattleCount = 0;
        final boolean[] liveBattle = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (snapshotInBattle[i]) {
                liveBattle[i] = true;
                snapshotBattle[snapshotBattleCount++] = i;
            }
        }
        // 考察中途新加入战斗的索引（按加入先后追加，元素少）
        final List<Integer> joinedMidLoop = new ArrayList<>();

        final int[] marked = new int[n];
        final int[] nearMarked = new int[n];
        final int[] buffer = new int[n];

        for (int i = 0; i < n; i++) {
            if (!examiner.canLead(i)) {
                continue;
            }

            final int generation = i + 1;
            int count = 0;

            // 同格 + 邻格候选（j > i）
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    final List<Integer> bucket = buckets.get(bucketKey(cellX[i] + dx, cellY[i] + dy));
                    if (bucket == null) {
                        continue;
                    }
                    for (final int j : bucket) {
                        if (j > i && marked[j] != generation) {
                            marked[j] = generation;
                            nearMarked[j] = generation;
                            buffer[count++] = j;
                        }
                    }
                }
            }

            // battle XOR 远距候选：双方战斗状态相异时配对必须考察（接力路径距离无界）
            if (liveBattle[i]) {
                // i 在战斗中：全部 live 非战斗 j 都是候选
                for (int j = i + 1; j < n; j++) {
                    if (!liveBattle[j] && marked[j] != generation) {
                        marked[j] = generation;
                        buffer[count++] = j;
                    }
                }
            } else {
                // i 非战斗：快照战斗侧 + 中途加入的战斗侧 j 都是候选
                for (int k = 0; k < snapshotBattleCount; k++) {
                    final int j = snapshotBattle[k];
                    if (j > i && marked[j] != generation) {
                        marked[j] = generation;
                        buffer[count++] = j;
                    }
                }
                for (final int j : joinedMidLoop) {
                    if (j > i && marked[j] != generation) {
                        marked[j] = generation;
                        buffer[count++] = j;
                    }
                }
            }

            Arrays.sort(buffer, 0, count);

            int cursor = 0;
            while (cursor < count) {
                final int j = buffer[cursor++];
                // 远距候选在考察时点复核 live XOR：配对体全部效果要么要求距离命中
                // （远距必然不命中），要么要求 battle XOR（接力路径），两者皆无即
                // 与原版运行一次空判定体等价——精确跳过而非保守多考察
                if (nearMarked[j] != generation && liveBattle[i] == liveBattle[j]) {
                    continue;
                }
                final boolean iWasInBattle = liveBattle[i];
                examiner.examine(i, j);
                // 回调可能触发 join / new Battle：回查双方最新战斗状态
                if (!liveBattle[j] && live.isInBattle(j)) {
                    liveBattle[j] = true;
                    joinedMidLoop.add(j);
                }
                if (!iWasInBattle && live.isInBattle(i)) {
                    liveBattle[i] = true;
                    joinedMidLoop.add(i);
                    // i 在本轮迭代中途加入战斗：此后全部 live 非战斗 j' 与原版的
                    // 惰性 XOR 判定一致地成为候选，追加进剩余段重排
                    for (int rest = j + 1; rest < n; rest++) {
                        if (!liveBattle[rest] && marked[rest] != generation) {
                            marked[rest] = generation;
                            buffer[count++] = rest;
                        }
                    }
                    Arrays.sort(buffer, cursor, count);
                }
            }
        }
    }

    private static long bucketKey(final int cx, final int cy) {
        return ((long) cx << 32) | (cy & 0xFFFFFFFFL);
    }
}
