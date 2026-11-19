package github.kasuminova.ssoptimizer.common.font;

import github.kasuminova.ssoptimizer.common.render.engine.TextScaleBuckets;

/** 同一渲染器短时间内连续跨过两个尺寸档时启用临时字形。 */
public final class AnimatedFontSize {
    private static final long ACTIVE_NS = 500_000_000L;
    private int bucket, previousBucket;
    private long lastChange;
    private boolean changed, active;

    public boolean observe(float scale, long now) {
        int next = TextScaleBuckets.bucketScaleMillis(scale);
        if (changed && now - lastChange >= ACTIVE_NS) {
            changed = false;
            active = false;
        }
        if (bucket != 0 && next != bucket) {
            active |= changed && next != previousBucket;
            previousBucket = bucket;
            changed = true;
            lastChange = now;
        }
        bucket = next;
        return active;
    }
}
