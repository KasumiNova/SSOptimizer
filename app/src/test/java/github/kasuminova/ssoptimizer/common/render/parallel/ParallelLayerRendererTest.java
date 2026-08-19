package github.kasuminova.ssoptimizer.common.render.parallel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ParallelLayerRenderer#shardByGroup} 的分片语义：普通渲染物
 * （非 Ship，组键即自身）按轮询均分且保持列表相对序。
 * 舰载机→母舰的同组约束依赖游戏 Ship 实例，由实机截图验证兜底。
 */
class ParallelLayerRendererTest {
    @Test
    void shardsDistributeRoundRobinPreservingOrder() {
        List<Object> items = List.of("a", "b", "c", "d", "e", "f", "g");
        List<List<Object>> shards = ParallelLayerRenderer.shardByGroup(items, 3);
        assertEquals(3, shards.size());
        assertEquals(List.of("a", "d", "g"), shards.get(0));
        assertEquals(List.of("b", "e"), shards.get(1));
        assertEquals(List.of("c", "f"), shards.get(2));
    }

    @Test
    void shardCountCappedByListSize() {
        List<Object> items = List.of("a", "b");
        List<List<Object>> shards = ParallelLayerRenderer.shardByGroup(items, 2);
        assertEquals(List.of("a"), shards.get(0));
        assertEquals(List.of("b"), shards.get(1));
    }

    @Test
    void emptyListYieldsEmptyShards() {
        List<List<Object>> shards = ParallelLayerRenderer.shardByGroup(List.of(), 4);
        assertEquals(4, shards.size());
        assertTrue(shards.stream().allMatch(List::isEmpty));
    }

    /** 舰载机桩：经 LaunchingShipLink 接口归组（不触碰游戏 Ship 类）。 */
    private static final class FakeFighter implements LaunchingShipLink {
        private final Object mothership;

        FakeFighter(Object mothership) {
            this.mothership = mothership;
        }

        @Override
        public Object ssoptimizer$getLaunchingShip() {
            return mothership;
        }
    }

    @Test
    void fightersGroupWithMothership() {
        Object mothership = new Object();
        FakeFighter fighterA = new FakeFighter(mothership);
        FakeFighter fighterB = new FakeFighter(mothership);
        Object otherShip = new Object();
        // 母舰在列表尾仍先占位分组键：两架舰载机必须与之同段
        List<Object> items = List.of(fighterA, otherShip, fighterB, mothership);
        List<List<Object>> shards = ParallelLayerRenderer.shardByGroup(items, 2);
        boolean sameShard = shards.stream()
                .anyMatch(shard -> shard.contains(fighterA) && shard.contains(fighterB) && shard.contains(mothership));
        assertTrue(sameShard, "舰载机与母舰必须同段");
        int fighterShard = shards.get(0).contains(fighterA) ? 0 : 1;
        assertFalse(shards.get(fighterShard).contains(otherShip) && shards.get(fighterShard).size() > 3,
                "他组实体不挤进母舰段");
    }
}
