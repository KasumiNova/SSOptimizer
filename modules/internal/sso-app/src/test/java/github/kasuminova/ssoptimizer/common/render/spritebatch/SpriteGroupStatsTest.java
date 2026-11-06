package github.kasuminova.ssoptimizer.common.render.spritebatch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpriteGroupStatsTest {

    @Test
    void keyPacksTextureAndBlendFactorsUniquely() {
        long a = SpriteGroupStats.key(1841, 770, 771);
        assertEquals(1841, a >> 20, "纹理 ID 占高位");
        assertEquals(770, (a >> 10) & 0x3FF, "blendSrc 占中 10 位");
        assertEquals(771, a & 0x3FF, "blendDest 占低 10 位");
        assertNotEquals(SpriteGroupStats.key(1841, 770, 771), SpriteGroupStats.key(1841, 770, 1));
        assertNotEquals(SpriteGroupStats.key(1841, 770, 771), SpriteGroupStats.key(1842, 770, 771));
    }

    @Test
    void consecutiveSameGroupCountsAsOneRun() {
        SpriteGroupStats stats = new SpriteGroupStats();
        long texA = SpriteGroupStats.key(43, 770, 771);
        long texB = SpriteGroupStats.key(44, 770, 771);
        stats.record(texA, false, false);
        stats.record(texA, false, false);
        stats.record(texB, false, false);
        stats.record(texB, false, false);
        stats.record(texA, false, false);
        stats.endFrame();

        String report = stats.report();
        assertTrue(report.contains("quads=5.0"), "总 quad 数：" + report);
        assertTrue(report.contains("distinct组=2.0"), "distinct 组数：" + report);
        assertTrue(report.contains("保序run=3.0"), "run 数：" + report);
        assertTrue(report.contains("40%"), "节省率 (1-3/5)=40%：" + report);
    }

    @Test
    void forbiddenHitBreaksRunAndIsExcludedFromGrouping() {
        SpriteGroupStats stats = new SpriteGroupStats();
        long texA = SpriteGroupStats.key(43, 770, 771);
        stats.record(texA, false, false);
        stats.record(texA, false, true);
        stats.record(texA, false, false);
        stats.endFrame();

        String report = stats.report();
        assertTrue(report.contains("quads=2.0"), "禁区 quad 不计入：" + report);
        assertTrue(report.contains("保序run=2.0"), "禁区打断 run：" + report);
        assertTrue(report.contains("禁区=1.0"), "禁区计数：" + report);
    }

    @Test
    void reportWithoutFramesIsGraceful() {
        assertTrue(new SpriteGroupStats().report().contains("尚无战斗帧"));
    }

    @Test
    void topGroupsSortedByQuadCount() {
        SpriteGroupStats stats = new SpriteGroupStats();
        long small = SpriteGroupStats.key(1, 770, 771);
        long large = SpriteGroupStats.key(2, 770, 771);
        stats.record(small, false, false);
        stats.record(large, false, false);
        stats.record(large, false, false);
        stats.record(large, false, false);
        stats.endFrame();

        String report = stats.report();
        int top1 = report.indexOf("top1 tex=2");
        int top2 = report.indexOf("top2 tex=1");
        assertTrue(top1 >= 0 && top2 > top1, "top 榜按 quad 数降序：" + report);
    }
}
