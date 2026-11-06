package github.kasuminova.ssoptimizer.common.render.spritebatch;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class SpriteGroupStats {

    public static long key(int textureId, int blendSrc, int blendDest) {
        return ((long) textureId << 20) | ((long) blendSrc << 10) | (long) blendDest;
    }

    private final Long2LongOpenHashMap frameGroups = new Long2LongOpenHashMap();
    private long frameQuads;
    private long frameNoBind;
    private long frameForbidden;
    private long frameRuns;
    private long lastKey = -1;

    private final LongOpenHashSet segmentGroups = new LongOpenHashSet();
    private long frameSegments;
    private long frameMergedDraws;

    private final Long2LongOpenHashMap totalGroupQuads = new Long2LongOpenHashMap();
    private long frames;
    private long totalQuads;
    private long totalNoBind;
    private long totalForbidden;
    private long totalRuns;
    private long totalDistinctGroups;
    private long totalSegments;
    private long totalMergedDraws;

    public void record(long key, boolean noBind, boolean forbidden) {
        if (forbidden) {
            frameForbidden++;
            lastKey = -1;
            closeSegment();
            return;
        }
        frameQuads++;
        if (noBind) {
            frameNoBind++;
        }
        frameGroups.addTo(key, 1);
        totalGroupQuads.addTo(key, 1);
        segmentGroups.add(key);
        if (key != lastKey) {
            frameRuns++;
            lastKey = key;
        }
    }

    public void barrier() {
        closeSegment();
        lastKey = -1;
    }

    private void closeSegment() {
        if (segmentGroups.isEmpty()) {
            return;
        }
        frameSegments++;
        frameMergedDraws += segmentGroups.size();
        segmentGroups.clear();
    }

    public void endFrame() {
        closeSegment();
        frames++;
        totalQuads += frameQuads;
        totalNoBind += frameNoBind;
        totalForbidden += frameForbidden;
        totalRuns += frameRuns;
        totalDistinctGroups += frameGroups.size();
        totalSegments += frameSegments;
        totalMergedDraws += frameMergedDraws;
        frameGroups.clear();
        frameQuads = 0;
        frameNoBind = 0;
        frameForbidden = 0;
        frameRuns = 0;
        frameSegments = 0;
        frameMergedDraws = 0;
        lastKey = -1;
    }

    public long frames() {
        return frames;
    }

    public String report() {
        if (frames == 0) {
            return "[SSOptimizer] Sprite 合批统计：尚无战斗帧";
        }
        StringBuilder sb = new StringBuilder(512);
        sb.append(String.format(
                "[SSOptimizer] Sprite 合批统计（%d 帧均值）：quads=%.1f（noBind=%.1f） "
                        + "distinct组=%.1f 平均每组quad=%.2f 保序run=%.1f（drawcall 节省 %.0f%%） 禁区=%.2f",
                frames,
                (double) totalQuads / frames,
                (double) totalNoBind / frames,
                (double) totalDistinctGroups / frames,
                totalDistinctGroups == 0 ? 0.0 : (double) totalQuads / totalDistinctGroups,
                (double) totalRuns / frames,
                totalQuads == 0 ? 0.0 : (1.0 - (double) totalRuns / totalQuads) * 100.0,
                (double) totalForbidden / frames));
        sb.append(String.format(
                "%n  段内归并：段数/帧=%.1f Σ段内distinct/帧=%.1f drawcall 节省上限 %.0f%%（相对保序 run）",
                (double) totalSegments / frames,
                (double) totalMergedDraws / frames,
                totalRuns == 0 ? 0.0 : (1.0 - (double) totalMergedDraws / totalRuns) * 100.0));
        List<long[]> top = new ArrayList<>();
        for (var entry : totalGroupQuads.long2LongEntrySet()) {
            top.add(new long[]{entry.getLongKey(), entry.getLongValue()});
        }
        top.sort(Comparator.comparingLong(e -> -e[1]));
        int limit = Math.min(5, top.size());
        for (int i = 0; i < limit; i++) {
            long k = top.get(i)[0];
            sb.append(String.format("%n  top%d tex=%d blend=%d/%d quads=%d",
                    i + 1, k >> 20, (k >> 10) & 0x3FF, k & 0x3FF, top.get(i)[1]));
        }
        return sb.toString();
    }
}
