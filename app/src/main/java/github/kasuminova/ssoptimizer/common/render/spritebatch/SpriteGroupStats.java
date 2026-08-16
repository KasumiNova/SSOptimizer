package github.kasuminova.ssoptimizer.common.render.spritebatch;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Sprite 合批 P0 量化统计（纯逻辑，不触碰 GL）。
 * <p>
 * 按帧聚合战斗作用域内的 sprite 绘制：分组键 = (纹理ID, blendSrc, blendDest)，
 * 统计三个关键决策指标：
 * <ul>
 *   <li>平均每帧 quad 数与 distinct 组数 → 平均每组 quad 数（贴图重复度）；</li>
 *   <li>连续同组 run 数 → 严格保序模式下每层 drawcall 数的下界估计；</li>
 *   <li>禁区（display list 编译 / stencil / scissor）命中率 → 透传比例。</li>
 * </ul>
 * 帧内状态与跨帧累计分离，{@link #endFrame()} 折叠；{@link #report()} 输出汇总文本。
 */
public final class SpriteGroupStats {

    /** 打包分组键：texId 占高位，src/dst 各占 10 位（GL 枚举均 &lt; 1024）。 */
    public static long key(int textureId, int blendSrc, int blendDest) {
        return ((long) textureId << 20) | ((long) blendSrc << 10) | (long) blendDest;
    }

    // 帧内状态
    private final Long2LongOpenHashMap frameGroups = new Long2LongOpenHashMap();
    private long frameQuads;
    private long frameNoBind;
    private long frameForbidden;
    private long frameRuns;
    private long lastKey = -1;

    // 跨帧累计
    private final Long2LongOpenHashMap totalGroupQuads = new Long2LongOpenHashMap();
    private long frames;
    private long totalQuads;
    private long totalNoBind;
    private long totalForbidden;
    private long totalRuns;
    private long totalDistinctGroups;

    /**
     * 记录一次 sprite 绘制。
     *
     * @param key       {@link #key(int, int, int)} 打包的分组键
     * @param noBind    是否 renderNoBind 路径（调用方自管纹理绑定）
     * @param forbidden 是否命中禁区（display list 编译 / stencil / scissor）——
     *                  命中时不参与分组与 run 统计，且打断当前 run
     */
    public void record(long key, boolean noBind, boolean forbidden) {
        if (forbidden) {
            frameForbidden++;
            lastKey = -1;
            return;
        }
        frameQuads++;
        if (noBind) {
            frameNoBind++;
        }
        frameGroups.addTo(key, 1);
        totalGroupQuads.addTo(key, 1);
        if (key != lastKey) {
            frameRuns++;
            lastKey = key;
        }
    }

    /** 折叠当前帧进累计并重置帧内状态。 */
    public void endFrame() {
        frames++;
        totalQuads += frameQuads;
        totalNoBind += frameNoBind;
        totalForbidden += frameForbidden;
        totalRuns += frameRuns;
        totalDistinctGroups += frameGroups.size();
        frameGroups.clear();
        frameQuads = 0;
        frameNoBind = 0;
        frameForbidden = 0;
        frameRuns = 0;
        lastKey = -1;
    }

    /** @return 已折叠的帧数。 */
    public long frames() {
        return frames;
    }

    /** @return 汇总报告文本（多行）。 */
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
