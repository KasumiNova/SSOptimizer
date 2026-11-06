package github.kasuminova.ssoptimizer.common.render.engine;

import com.fs.starfarer.combat.entities.ContrailEngine;
import github.kasuminova.ssoptimizer.mixin.accessor.ContrailGroupAccessor;
import github.kasuminova.ssoptimizer.mixin.accessor.ContrailSegmentAccessor;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ContrailAdvanceHelper {

    private ContrailAdvanceHelper() {
    }

    public static void advance(Map<?, ?> groups, float amount) {
        List<Object> removals = new ArrayList<>();
        for (Object groupObject : groups.values()) {
            ContrailGroupAccessor group = (ContrailGroupAccessor) groupObject;
            advanceGroup(group, amount, removals);
        }
        for (Object key : removals) {
            groups.remove(key);
        }
    }

    private static void advanceGroup(ContrailGroupAccessor group, float amount, List<Object> removals) {
        List<Object> segments = group.ssoptimizer$getSegments();
        boolean ended = group.ssoptimizer$getEnded();
        ContrailEngine.ContrailWidthMode widthMode = group.ssoptimizer$getWidthMode();
        float widthMultiplier = group.ssoptimizer$getWidthMultiplier();

        for (final Object segment : segments) {
            advanceSegment((ContrailSegmentAccessor) segment, amount, ended, widthMode, widthMultiplier);
        }

        if (segments.size() >= 2) {
            while (segments.size() >= 2 && group.ssoptimizer$removeExpiredSegment()) {
            }
        } else if (ended) {
            removals.add(group.ssoptimizer$getKey());
        }
    }

    static void advanceSegment(ContrailSegmentAccessor segment, float amount, boolean ended,
                               ContrailEngine.ContrailWidthMode widthMode, float widthMultiplier) {
        float texU = segment.ssoptimizer$getU();
        texU += amount;
        if (ended) {
            texU += amount / 3.0f;
        }
        float maxAge = segment.ssoptimizer$getMaxAge();
        if (texU > maxAge) {
            texU = maxAge;
        }
        segment.ssoptimizer$setTexU(texU);

        // maxAge 为 0 时保留原除法产生的 NaN。
        float progress = (texU == maxAge && maxAge != 0.0f) ? 1.0f : texU / maxAge;
        segment.ssoptimizer$setProgress(progress);

        if (widthMode == ContrailEngine.ContrailWidthMode.WIDEN) {
            segment.ssoptimizer$setWidth(segment.ssoptimizer$getBaseWidth()
                    * (progress * widthMultiplier + 1.0f));
        } else if (widthMode == ContrailEngine.ContrailWidthMode.NARROW) {
            segment.ssoptimizer$setWidth(segment.ssoptimizer$getBaseWidth()
                    * (0.25f + (1.0f - progress) * 0.75f));
        }

        if (!ended) {
            // 必须先缩放速度，再用缩放后的速度推进位置。
            Vector2f vel = segment.ssoptimizer$getVel();
            vel.scale(1.0f - progress);
            Vector2f position = segment.ssoptimizer$getPosition();
            position.x += vel.x * amount;
            position.y += vel.y * amount;
        }
    }
}
