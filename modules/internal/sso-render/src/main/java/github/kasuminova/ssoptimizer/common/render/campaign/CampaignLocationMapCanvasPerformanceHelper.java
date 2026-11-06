package github.kasuminova.ssoptimizer.common.render.campaign;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

public final class CampaignLocationMapCanvasPerformanceHelper {
    private static final ThreadLocal<HashSet<Object>> RETAIN_LOOKUP_SET =
            ThreadLocal.withInitial(HashSet::new);

    private CampaignLocationMapCanvasPerformanceHelper() {
    }

    public static boolean retainLiveEntities(final Set<?> targetSet,
                                             final Collection<?> liveEntities) {
        if (targetSet.isEmpty()) {
            return false;
        }
        if (liveEntities.isEmpty()) {
            targetSet.clear();
            return true;
        }
        if (liveEntities instanceof Set<?>) {
            return targetSet.retainAll(liveEntities);
        }

        final HashSet<Object> lookup = RETAIN_LOOKUP_SET.get();
        lookup.clear();
        lookup.addAll(liveEntities);
        try {
            return targetSet.retainAll(lookup);
        } finally {
            lookup.clear();
        }
    }
}