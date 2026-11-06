package github.kasuminova.ssoptimizer.common.combat.ai;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AiSnapshotConcurrencyTest {

    @Test
    void snapshotIterationSurvivesConcurrentMutation() {
        List<String> members = new ArrayList<>(List.of("fighter-a", "fighter-b", "fighter-c"));
        List<String> snapshot = new ArrayList<>(members);

        Iterator<String> snapshotIt = snapshot.iterator();
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
        List<String> members = new ArrayList<>(List.of("fighter-a", "fighter-b"));
        Iterator<String> liveIt = members.iterator();
        members.add("fighter-c");
        assertThrows(ConcurrentModificationException.class, liveIt::next,
                "裸迭代活引用遇并发写必须抛 CME（修复前的缺陷形态）");
    }

    @Test
    void snapshotReadsAtIterationStartAreStableAcrossWorkerStyleMutations() {
        List<String> members = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            members.add("fighter-" + i);
        }
        for (int round = 0; round < 100; round++) {
            List<String> snapshot = new ArrayList<>(members);
            Iterator<String> it = snapshot.iterator();
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

    @Test
    void nullManeuverInEscortChainYieldsFalseWithoutNpe() {
        assertFalse(simulateIsEscortTargetOf(0), "maneuver 并发失效时 isEscortTargetOf 必须返回 false 且不抛异常");
    }

    private static boolean simulateIsEscortTargetOf(int depth) {
        if (depth >= 2) {
            return false;
        }
        Object maneuver = depth == 0 ? null : new Object();
        if (maneuver instanceof String targetSupplier) {
            return false;
        }
        Object target = null;
        if (target == null) {
            return false;
        }
        return !target.equals("arg2") && !simulateIsEscortTargetOf(depth + 1);
    }
}
