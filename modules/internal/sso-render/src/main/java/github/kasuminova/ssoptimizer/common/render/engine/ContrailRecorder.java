package github.kasuminova.ssoptimizer.common.render.engine;

import github.kasuminova.ssoptimizer.bridge.opengl.ContrailDraw;
import github.kasuminova.ssoptimizer.mixin.accessor.ContrailGroupAccessor;
import github.kasuminova.ssoptimizer.mixin.accessor.ContrailSegmentAccessor;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.List;
import java.util.function.Consumer;

final class ContrailRecorder implements AutoCloseable {
    private final float alphaScale;
    private final Consumer<ContrailBatch> submit;
    private final ContrailDraw.CurrentColor currentColor = new ContrailDraw.CurrentColor();
    private ContrailBatch pending;
    private boolean submitted;

    ContrailRecorder(float alphaScale, Consumer<ContrailBatch> submit) {
        this.alphaScale = alphaScale;
        this.submit = submit;
    }

    void capture(ContrailGroupAccessor group, List<Object> segments) {
        Color color = group.ssoptimizer$getColor();
        if (color == null) {
            return;
        }
        int argb = (color.getAlpha() << 24) | (color.getRed() << 16) | (color.getGreen() << 8) | color.getBlue();
        float duration = group.ssoptimizer$getSegmentDuration();
        Vector2f tail = group.ssoptimizer$getTail();
        int remaining = segments.size();
        ContrailBatch batch = startGroup(argb, duration, remaining, tail != null);
        ContrailSegmentAccessor last = null;
        for (Object value : segments) {
            ContrailSegmentAccessor segment = (ContrailSegmentAccessor) value;
            Vector2f pos = segment.ssoptimizer$getPosition();
            Vector2f normal = segment.ssoptimizer$getNormal();
            if (pos != null && normal != null) {
                if (batch.full()) {
                    batch = startGroup(argb, duration, remaining, tail != null);
                }
                batch.segment(pos.x, pos.y, normal.x, normal.y, segment.ssoptimizer$getWidth(),
                        segment.ssoptimizer$getU(), segment.ssoptimizer$getProgress(), segment.ssoptimizer$getAlphaMult());
                last = segment;
            }
            remaining--;
        }
        if (tail != null && last != null) {
            if (batch.full()) {
                batch = startGroup(argb, duration, 0, true);
            }
            Vector2f normal = last.ssoptimizer$getNormal();
            batch.tail(tail.x, tail.y, normal.x, normal.y, last.ssoptimizer$getWidth(), last.ssoptimizer$getU());
        }
    }

    private ContrailBatch startGroup(int argb, float duration, int remaining, boolean hasTail) {
        // 刚好写满的条带要等下一对顶点或显式 flush，期间的 texture.bind() 仍会影响这次绘制。
        if (pending != null && pending.full()) {
            flush();
        }
        if (pending == null) {
            pending = ContrailBatch.acquire(alphaScale, currentColor);
        }
        pending.beginGroup(argb, duration, remaining, hasTail);
        return pending;
    }

    void flush() {
        if (pending != null) {
            ContrailBatch ready = pending;
            pending = null;
            if (ready.empty()) {
                ready.discard();
            } else {
                submit.accept(ready);
                submitted = true;
            }
        }
    }

    void finish(boolean queued) {
        flush();
        if (submitted) {
            currentColor.restore(queued);
        }
    }

    @Override
    public void close() {
        if (pending != null) {
            pending.discard();
            pending = null;
        }
    }
}
