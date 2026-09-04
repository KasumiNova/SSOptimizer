package github.kasuminova.ssoptimizer.common.campaign;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CombatPairingGridHelper} 的逻辑验证。
 * <p>
 * 核心验证：分桶枚举的「考察配对集合 + 顺序」与「按原版语义必须考察的配对」完全一致——
 * 即任何原版能产生效果的配对（距离命中或 battle XOR 接力，含考察中途加入战斗产生的
 * 新 XOR 配对）都会被枚举，且枚举顺序保持原版外层/内层升序。
 * 验证方法：同一舰队分布下，用脚本化 join 模拟配对体的战斗状态变更，
 * 重放计算「必须考察」谓词（邻格快照 || 处理时点 live XOR），与 helper 实际枚举逐对对比。
 */
class CombatPairingGridHelperTest {

    @Test
    void parseEnabledDefaultsAndFallback() {
        assertTrue(CombatPairingGridHelper.parseEnabled(null), "未设置时默认启用");
        assertTrue(CombatPairingGridHelper.parseEnabled("true"));
        assertTrue(CombatPairingGridHelper.parseEnabled("TRUE"));
        assertFalse(CombatPairingGridHelper.parseEnabled("false"));
        assertFalse(CombatPairingGridHelper.parseEnabled("False"));
        assertTrue(CombatPairingGridHelper.parseEnabled("0"), "非法取值回退默认启用");
    }

    @Test
    void emptyAndSingleFleetProduceNoPairs() {
        final RecordingExaminer examiner = new RecordingExaminer(new boolean[0]);
        CombatPairingGridHelper.forEachCandidatePair(
                new float[0], new float[0], new boolean[0], 1000.0F,
                index -> false, examiner);
        assertTrue(examiner.pairs.isEmpty());

        CombatPairingGridHelper.forEachCandidatePair(
                new float[] { 1.0F }, new float[] { 2.0F }, new boolean[] { false }, 1000.0F,
                index -> false, examiner);
        assertTrue(examiner.pairs.isEmpty());
    }

    @Test
    void invalidArgumentsRejected() {
        assertThrows(IllegalArgumentException.class, () -> CombatPairingGridHelper.forEachCandidatePair(
                new float[] { 0, 1 }, new float[] { 0 }, new boolean[] { false, false }, 1000.0F,
                index -> false, new RecordingExaminer(new boolean[2])));
        assertThrows(IllegalArgumentException.class, () -> CombatPairingGridHelper.forEachCandidatePair(
                new float[] { 0, 1 }, new float[] { 0, 1 }, new boolean[] { false, false }, 0.0F,
                index -> false, new RecordingExaminer(new boolean[2])));
    }

    @Test
    void canLeadFalseSkipsOuterFleet() {
        // 三支舰队同格，0 号不可交战：不应出现任何以 0 为外层的配对
        final float[] xs = { 0, 10, 20 };
        final float[] ys = { 0, 0, 0 };
        final boolean[] inBattle = { false, false, false };
        final boolean[] lead = { false, true, true };
        final RecordingExaminer examiner = new RecordingExaminer(lead);

        CombatPairingGridHelper.forEachCandidatePair(xs, ys, inBattle, 1000.0F,
                index -> false, examiner);

        assertEquals(List.of(pair(1, 2)), examiner.pairs);
    }

    @Test
    void farPairsWithoutBattleXorArePruned() {
        // cell 边长 1000：0 号在格 (0,0)，1 号在格 (3,0)（x=3100，格差 3），无 battle：配对必须被剪掉
        final float[] xs = { 0, 3100 };
        final float[] ys = { 0, 0 };
        final RecordingExaminer examiner = new RecordingExaminer(new boolean[] { true, true });

        CombatPairingGridHelper.forEachCandidatePair(xs, ys, new boolean[] { false, false }, 1000.0F,
                index -> false, examiner);

        assertTrue(examiner.pairs.isEmpty(), "远距且无 battle XOR 的配对必须被剪枝");
    }

    @Test
    void boundaryDistancePairsAreExamined() {
        // 原版距离上限 T = 2·maxSelectionSize + 1000：dist ≤ T 的配对必然落在同格/邻格
        // 构造 dist 恰好略小于 cellSize（=T）且跨格（格差 1）的边界用例
        final float[] xs = { 999.9F, 1000.1F };
        final float[] ys = { 0, 0 };
        final RecordingExaminer examiner = new RecordingExaminer(new boolean[] { true, true });

        CombatPairingGridHelper.forEachCandidatePair(xs, ys, new boolean[] { false, false }, 1000.0F,
                index -> false, examiner);

        assertEquals(List.of(pair(0, 1)), examiner.pairs, "dist < cellSize 的跨格配对必须被考察");
    }

    @Test
    void battleXorPairsAreAlwaysExaminedRegardlessOfDistance() {
        // 0 号在战斗中、1 号无战斗：即使格差 100 也必须考察（接力路径距离无界）
        final float[] xs = { 0, 100_000 };
        final float[] ys = { 0, 0 };
        final RecordingExaminer examiner = new RecordingExaminer(new boolean[] { true, true });

        CombatPairingGridHelper.forEachCandidatePair(xs, ys, new boolean[] { true, false }, 1000.0F,
                index -> index == 0, examiner);

        assertEquals(List.of(pair(0, 1)), examiner.pairs);
    }

    @Test
    void midLoopJoinOfOuterFleetExtendsFarCandidates() {
        // 考察 (0,1) 时 0 号加入战斗：此后 0 号与全部远距非战斗舰队的配对
        // 在原版惰性 XOR 判定下都必须考察
        final int n = 5;
        final float[] xs = { 0, 500, 50_000, 60_000, 70_000 };
        final float[] ys = { 0, 0, 0, 0, 0 };
        final boolean[] liveBattle = new boolean[n];
        final boolean[] lead = allLead(n);
        final RecordingExaminer examiner = new RecordingExaminer(lead) {
            @Override
            public void examine(final int first, final int second) {
                super.examine(first, second);
                if (first == 0 && second == 1) {
                    liveBattle[0] = true; // 模拟 new Battle / join
                }
            }
        };

        CombatPairingGridHelper.forEachCandidatePair(xs, ys, new boolean[n], 1000.0F,
                index -> liveBattle[index], examiner);

        assertEquals(List.of(pair(0, 1), pair(0, 2), pair(0, 3), pair(0, 4)),
                examiner.pairs, "外层舰队中途加入战斗后，剩余远距配对必须全部考察");
    }

    @Test
    void midLoopJoinOfInnerFleetCreatesXorForEarlierBattleFleet() {
        // 1 号（在战斗中）考察 (1,4) 时 4 号加入战斗；
        // 之后外层轮到 2 号（非战斗）时，(2,4) 成为 live XOR 配对——
        // 但 4 已加入战斗后 live XOR 反而消失，真正的用例是：
        // 0 号在战斗中、考察 (0,3) 时 3 号加入战斗，外层 1/2 号与 3 号的远距配对
        // 在加入前是 XOR 必须考察（加入后变同战斗不再 XOR）
        final int n = 4;
        final float[] xs = { 0, 50_000, 60_000, 500 };
        final float[] ys = { 0, 0, 0, 0 };
        final boolean[] liveBattle = new boolean[n];
        liveBattle[0] = true;
        final RecordingExaminer examiner = new RecordingExaminer(allLead(n)) {
            @Override
            public void examine(final int first, final int second) {
                super.examine(first, second);
                if (first == 0 && second == 3) {
                    liveBattle[3] = true;
                }
            }
        };

        CombatPairingGridHelper.forEachCandidatePair(
                xs, ys, new boolean[] { true, false, false, false }, 1000.0F,
                index -> liveBattle[index], examiner);

        // (0,1)(0,2)：0 号战斗侧远距 XOR 必考察；(0,3)：邻格（x 差 500）考察；
        // (1,3)(2,3)：考察时 3 号已加入战斗 → live XOR → 必考察；(1,2)：远距无 XOR → 剪掉
        assertEquals(List.of(pair(0, 1), pair(0, 2), pair(0, 3), pair(1, 3), pair(2, 3)),
                examiner.pairs);
    }

    @Test
    void fuzzMatchesBruteForceReplay() {
        final Random random = new Random(20260904L);
        for (int trial = 0; trial < 300; trial++) {
            final int n = 2 + random.nextInt(40);
            final float[] xs = new float[n];
            final float[] ys = new float[n];
            final float[] radii = new float[n];
            final boolean[] snapshotBattle = new boolean[n];
            final boolean[] lead = new boolean[n];
            for (int i = 0; i < n; i++) {
                // 混合正负坐标与小数，覆盖跨格边界
                xs[i] = (random.nextFloat() - 0.5F) * 20_000.0F;
                ys[i] = (random.nextFloat() - 0.5F) * 20_000.0F;
                radii[i] = 10.0F + random.nextFloat() * 200.0F;
                snapshotBattle[i] = random.nextFloat() < 0.1F;
                lead[i] = random.nextFloat() > 0.05F;
            }
            float maxRadius = 0.0F;
            for (final float radius : radii) {
                maxRadius = Math.max(maxRadius, radius);
            }
            final float cellSize = 2.0F * maxRadius + 1000.0F;

            // 随机脚本：被考察的配对有小概率触发其中一方加入战斗（模拟配对体副作用）
            final Set<Long> joinScript = new HashSet<>();
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    if (random.nextFloat() < 0.03F) {
                        joinScript.add(pair(i, j));
                    }
                }
            }

            // helper 实际枚举
            final boolean[] liveBattle = snapshotBattle.clone();
            final List<Long> actual = new ArrayList<>();
            final boolean[] finalLead = lead;
            CombatPairingGridHelper.forEachCandidatePair(xs, ys, snapshotBattle, cellSize,
                    index -> liveBattle[index],
                    new CombatPairingGridHelper.PairExaminer() {
                        @Override
                        public boolean canLead(final int index) {
                            return finalLead[index];
                        }

                        @Override
                        public void examine(final int first, final int second) {
                            actual.add(pair(first, second));
                            if (joinScript.contains(pair(first, second))) {
                                // 模拟 join/new Battle：战斗状态只增不减
                                liveBattle[first] = true;
                                liveBattle[second] = true;
                            }
                        }
                    });

            // 重放：按原版顺序遍历全部配对，「必须考察」谓词 =
            // 邻格（快照坐标） || 处理时点 liveXOR；被考察时应用同一脚本
            final boolean[] replayBattle = snapshotBattle.clone();
            final List<Long> expected = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (!lead[i]) {
                    continue;
                }
                final int cellXi = cellOf(xs[i], cellSize);
                final int cellYi = cellOf(ys[i], cellSize);
                for (int j = i + 1; j < n; j++) {
                    final boolean near = Math.abs(cellOf(xs[j], cellSize) - cellXi) <= 1
                            && Math.abs(cellOf(ys[j], cellSize) - cellYi) <= 1;
                    final boolean liveXor = replayBattle[i] ^ replayBattle[j];
                    if (near || liveXor) {
                        expected.add(pair(i, j));
                        if (joinScript.contains(pair(i, j))) {
                            replayBattle[i] = true;
                            replayBattle[j] = true;
                        }
                    }
                }
            }

            assertEquals(expected, actual, "trial=" + trial + " n=" + n
                    + "：分桶枚举与「必须考察」谓词重放结果必须完全一致");
        }
    }

    private static int cellOf(final float v, final float cellSize) {
        return (int) Math.floor(v / cellSize);
    }

    private static boolean[] allLead(final int n) {
        final boolean[] lead = new boolean[n];
        java.util.Arrays.fill(lead, true);
        return lead;
    }

    private static long pair(final int i, final int j) {
        return ((long) i << 32) | j;
    }

    private static class RecordingExaminer implements CombatPairingGridHelper.PairExaminer {
        final List<Long> pairs = new ArrayList<>();
        private final boolean[] lead;

        RecordingExaminer(final boolean[] lead) {
            this.lead = lead;
        }

        @Override
        public boolean canLead(final int index) {
            return lead[index];
        }

        @Override
        public void examine(final int first, final int second) {
            pairs.add(pair(first, second));
        }
    }
}
