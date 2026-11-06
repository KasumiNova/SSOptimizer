package github.kasuminova.ssoptimizer.common.render.warroom;

import com.fs.graphics.TextureObject;

public interface StripBatchRenderer {
    /** 实现方不得保留调用方复用的数组引用。 */
    void renderStripBatch(TextureObject texture, boolean additive,
                          float[] geometry, int[] colors, int stripCount);
}
