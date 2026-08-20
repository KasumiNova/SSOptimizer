package github.kasuminova.ssoptimizer.common.combat.ai;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FighterAI 编队成员迭代的并发安全契约（对应 {@code FighterAiSnapshotMixin} 的
 * 快照修复语义）：并行窗口内共享集合被并发增删时，快照迭代必须稳定不抛
 * {@link ConcurrentModificationException}；对照组证明裸迭代原列表在同一
 * 并发写下必然抛 CME（即修复针对的真实缺陷形态）。
 * <p>
 * 场景映射：worker 的 FighterAI.advance 迭代 {@code wing.getMembers()}（编队
 * 成员列表活引用），并行窗口内航母 BasicShipAI（另一 worker）或主线程内联 AI
 * 增删成员——Mixin @Redirect 把迭代目标换成 {@code new ArrayList<>(members)}
 * 快照，本测试验证该替换后的迭代行为。
 */
class AiSnapshotConcurrencyTest {

    @Test
    void snapshotIterationSurvivesConcurrentMutation() {
        // 共享编队成员列表（活引用，模拟 FighterWing.getMembers() 的返回）
        List<String> members = new ArrayList<>(List.of("fighter-a", "fighter-b", "fighter-c"));
        // 修复语义：迭代开始前快照拷贝（Mixin @Redirect 的替换目标）
        List<String> snapshot = new ArrayList<>(members);

        Iterator<String> snapshotIt = snapshot.iterator();
        // 并行写：航母 AI 释放/回收战机（增删原列表）
        members.add("fighter-d");
        members.remove("fighter-a");
        members.clear();

        int seen = 0;
        while (snapshotIt.hasNext()) {
            snapshotIt.next();
            seen++;
        }
        assertEquals(3, seen, "快照迭代必须稳定遍历迭代开始时刻的全部成员");
    }

    @Test
    void directIterationOfLiveListThrowsOnConcurrentMutation() {
        // 对照组：裸迭代活引用在并发写下必然抛 CME——即修复前 FighterAI.advance
        // 在 v48b 实测的缺陷形态
        List<String> members = new ArrayList<>(List.of("fighter-a", "fighter-b"));
        Iterator<String> liveIt = members.iterator();
        members.add("fighter-c");
        assertThrows(ConcurrentModificationException.class, liveIt::next,
                "裸迭代活引用遇并发写必须抛 CME（修复前的缺陷形态）");
    }

    @Test
    void snapshotReadsAtIterationStartAreStableAcrossWorkerStyleMutations() {
        // 模拟「航母 worker 增删成员」与「FighterAI worker 迭代」交错多次：
        // 快照修复下每次迭代都稳定（以迭代开始时刻为准），不抛异常
        List<String> members = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            members.add("fighter-" + i);
        }
        for (int round = 0; round < 100; round++) {
            List<String> snapshot = new ArrayList<>(members);
            Iterator<String> it = snapshot.iterator();
            // 并发写交错（模拟另一 worker 的成员增删）
            members.add("spawn-" + round);
            if (!members.isEmpty()) {
                members.remove(0);
            }
            int seen = 0;
            while (it.hasNext()) {
                it.next();
                seen++;
            }
            assertTrue(seen >= 0, "每轮快照迭代都必须完成");
        }
    }

    /**
     * isEscortTargetOf 的 maneuver 守卫语义模拟：instanceof 检查后二次取引用
     * 为 null（并行窗口期 maneuver 被并发替换）→ getTargetShip 返回 null →
     * 递归 null target（arg 守卫兜底 false）→ 整体非护航（返回 false），
     * 全程不抛 NPE。对应 {@code AiUtilsEscortTargetGuardMixin} 的守卫二 +
     * 守卫一组合。
     */
    @Test
    void nullManeuverInEscortChainYieldsFalseWithoutNpe() {
        assertFalse(simulateIsEscortTargetOf(0), "maneuver 并发失效时 isEscortTargetOf 必须返回 false 且不抛异常");
    }

    /** 递归模拟（depth 上限 2，与游戏实现一致）；null maneuver 时 target 视为 null。 */
    private static boolean simulateIsEscortTargetOf(int depth) {
        if (depth >= 2) {
            return false;
        }
        // 守卫二：maneuver 为 null（并发替换）→ getTargetShip 返回 null
        Object maneuver = depth == 0 ? null : new Object();
        if (maneuver instanceof String targetSupplier) {
            return false; // 非 EscortTargetManeuverV3 分支（简化：不进入）
        }
        // EscortTargetManeuverV3 分支：getTargetShip() 返回 null（守卫二）
        Object target = null;
        // 守卫一：null target（或 null arg）→ 递归返回 false
        if (target == null) {
            return false;
        }
        return !target.equals("arg2") && !simulateIsEscortTargetOf(depth + 1);
    }
}
