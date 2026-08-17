package github.kasuminova.ssoptimizer.common.render.atlas;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AtlasPacker} shelf 装箱逻辑验证：确定性、边界内、无重叠、超尺寸条目跳过。
 */
class AtlasPackerTest {

    @Test
    void packsEntriesIntoShelvesWithinBounds() {
        List<AtlasPacker.Entry> entries = List.of(
                new AtlasPacker.Entry("a.png", 100, 100),
                new AtlasPacker.Entry("b.png", 200, 150),
                new AtlasPacker.Entry("c.png", 50, 300));
        AtlasPacker.Result result = AtlasPacker.pack(entries, 1024, 16);

        assertEquals(1, result.pages().size());
        assertTrue(result.skipped().isEmpty());
        // 高度降序：c(300) → b(150) → a(100)，同行依次排布
        List<AtlasPacker.Placement> placements = result.pages().get(0).placements();
        assertEquals("c.png", placements.get(0).path());
        assertEquals(16, placements.get(0).x());
        assertEquals(16, placements.get(0).y());
        // 同行续排：下一内容原点 = 前一内容原点 + 前一内容宽 + 2×padding
        assertEquals(16 + (50 + 32), placements.get(1).x());
        assertEquals(16, placements.get(1).y());
        assertEquals(16 + (50 + 32) + (200 + 32), placements.get(2).x());
        assertEquals(16, placements.get(2).y());
    }

    @Test
    void wrapsToNewRowAndNewPage() {
        // 单格 300+32=332，页宽 512 → 每行一个；页高 512 → 每页一个，共三页
        List<AtlasPacker.Entry> entries = List.of(
                new AtlasPacker.Entry("a.png", 300, 300),
                new AtlasPacker.Entry("b.png", 300, 300),
                new AtlasPacker.Entry("c.png", 300, 300));
        AtlasPacker.Result result = AtlasPacker.pack(entries, 512, 16);

        assertEquals(3, result.pages().size());
        for (int i = 0; i < 3; i++) {
            assertEquals(i, result.pages().get(i).index());
            assertEquals(1, result.pages().get(i).placements().size());
            assertEquals(i, result.pages().get(i).placements().get(0).page());
        }
    }

    @Test
    void skipsOversizeEntries() {
        List<AtlasPacker.Entry> entries = List.of(
                new AtlasPacker.Entry("huge.png", 600, 600),
                new AtlasPacker.Entry("ok.png", 100, 100));
        AtlasPacker.Result result = AtlasPacker.pack(entries, 512, 16);

        assertEquals(1, result.pages().size());
        assertEquals(1, result.skipped().size());
        assertEquals("huge.png", result.skipped().get(0).path());
        assertEquals("ok.png", result.pages().get(0).placements().get(0).path());
    }

    @Test
    void emptyInputProducesNoPages() {
        AtlasPacker.Result result = AtlasPacker.pack(List.of(), 512, 16);
        assertTrue(result.pages().isEmpty());
        assertTrue(result.skipped().isEmpty());
    }

    @Test
    void placementsNeverOverlap() {
        List<AtlasPacker.Entry> entries = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            entries.add(new AtlasPacker.Entry("s" + i + ".png", 20 + i * 5, 30 + i * 6));
        }
        AtlasPacker.Result result = AtlasPacker.pack(entries, 512, 16);
        assertTrue(result.skipped().isEmpty());

        for (AtlasPacker.Page page : result.pages()) {
            List<AtlasPacker.Placement> placements = page.placements();
            for (int i = 0; i < placements.size(); i++) {
                for (int j = i + 1; j < placements.size(); j++) {
                    AtlasPacker.Placement a = placements.get(i);
                    AtlasPacker.Placement b = placements.get(j);
                    // 单元格（内容 + padding）不相交
                    boolean separated = a.x() + a.width() + 16 <= b.x() - 16
                            || b.x() + b.width() + 16 <= a.x() - 16
                            || a.y() + a.height() + 16 <= b.y() - 16
                            || b.y() + b.height() + 16 <= a.y() - 16;
                    assertTrue(separated, "overlap: " + a + " vs " + b);
                }
            }
        }
    }
}
