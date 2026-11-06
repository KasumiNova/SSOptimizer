package github.kasuminova.ssoptimizer.common.campaign;

import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 以格网剪枝舰队两两配对，同时保留同格、邻格及实时战斗状态相异的配对。
 * 枚举顺序保持原索引升序，回调后必须回查双方战斗状态；仅限战役主线程调用。
 */
public final class CombatPairingGridHelper {
    public static final String ENABLED_PROPERTY = "ssoptimizer.campaign.combatPairing";
    public static final boolean DEFAULT_ENABLED = true;

    private static final Logger LOGGER = Logger.getLogger(CombatPairingGridHelper.class);

    private static final boolean ENABLED = parseEnabled(System.getProperty(ENABLED_PROPERTY));

    private CombatPairingGridHelper() {
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

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

    public interface PairExaminer {
        /** 每个外层索引仅在迭代开始前判定一次。 */
        boolean canLead(int index);

        void examine(int first, int second);
    }

    public interface BattleState {
        boolean isInBattle(int index);
    }

    /**
     * 按索引升序枚举同格、邻格及实时战斗状态相异的配对。
     * {@code cellSize} 必须覆盖原版交战距离上限，回调返回后会重新读取双方战斗状态。
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

        final int[] cellX = new int[n];
        final int[] cellY = new int[n];
        final Map<Long, List<Integer>> buckets = new HashMap<>();
        for (int i = 0; i < n; i++) {
            cellX[i] = (int) Math.floor(xs[i] / cellSize);
            cellY[i] = (int) Math.floor(ys[i] / cellSize);
            buckets.computeIfAbsent(bucketKey(cellX[i], cellY[i]), k -> new ArrayList<>()).add(i);
        }

        final int[] snapshotBattle = new int[n];
        int snapshotBattleCount = 0;
        final boolean[] liveBattle = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (snapshotInBattle[i]) {
                liveBattle[i] = true;
                snapshotBattle[snapshotBattleCount++] = i;
            }
        }
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

            // 战斗接力不受配对距离限制，实时状态相异的远距配对也必须保留。
            if (liveBattle[i]) {
                for (int j = i + 1; j < n; j++) {
                    if (!liveBattle[j] && marked[j] != generation) {
                        marked[j] = generation;
                        buffer[count++] = j;
                    }
                }
            } else {
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
                if (nearMarked[j] != generation && liveBattle[i] == liveBattle[j]) {
                    continue;
                }
                final boolean iWasInBattle = liveBattle[i];
                examiner.examine(i, j);
                if (!liveBattle[j] && live.isInBattle(j)) {
                    liveBattle[j] = true;
                    joinedMidLoop.add(j);
                }
                if (!iWasInBattle && live.isInBattle(i)) {
                    liveBattle[i] = true;
                    joinedMidLoop.add(i);
                    // 外层舰队中途加入战斗后，剩余非战斗舰队都成为接力候选。
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
