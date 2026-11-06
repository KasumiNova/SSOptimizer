package github.kasuminova.ssoptimizer.common.render.campaign;

import com.fs.starfarer.campaign.fleet.ContrailEngineV2;
import com.fs.starfarer.combat.entities.ContrailEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class CampaignContrailAdvanceHelper {

    private static final List<Object> REMOVALS = new ArrayList<>();

    private CampaignContrailAdvanceHelper() {
    }

    public static void advance(Map<Object, ContrailEngineV2.Contrail> contrails, float amount) {
        List<Object> removals = REMOVALS;
        removals.clear();
        for (ContrailEngineV2.Contrail contrail : contrails.values()) {
            advanceContrail(contrail, amount, removals);
        }
        for (Object key : removals) {
            contrails.remove(key);
        }
    }

    static void advanceContrail(ContrailEngineV2.Contrail contrail, float amount, List<Object> removals) {
        List<ContrailEngineV2.ContrailPoint> points = contrail.points;
        boolean remove = contrail.remove;
        ContrailEngine.ContrailWidthMode mode = contrail.mode;
        float widthMultiplier = contrail.widthMultiplier;
        float removeExtraAge = amount / 3.0f;
        float fadeOutStep = amount * 2.0f;

        boolean agedSteady = amount >= 0.0f
                && allPointsAgedSteady(points, remove, mode, widthMultiplier);
        if (!agedSteady) {
            int size = points.size();
            for (int i = 0; i < size; i++) {
                advancePoint(points.get(i), amount, removeExtraAge, fadeOutStep, remove, mode, widthMultiplier);
            }
        }

        if (points.size() >= 3) {
            while (points.size() >= 3 && contrail.removeFirstIfNecessary()) {
            }
        }
        if ((remove || contrail.autoCleanup) && points.size() < 3) {
            boolean allAged = true;
            for (int i = 0; i < points.size(); i++) {
                ContrailEngineV2.ContrailPoint point = points.get(i);
                if (point.elapsed < point.duration) {
                    allAged = false;
                }
            }
            if (allAged) {
                removals.add(contrail.source);
            }
        }
    }

    private static boolean allPointsAgedSteady(List<ContrailEngineV2.ContrailPoint> points,
                                               boolean remove,
                                               ContrailEngine.ContrailWidthMode mode,
                                               float widthMultiplier) {
        int size = points.size();
        for (int i = 0; i < size; i++) {
            ContrailEngineV2.ContrailPoint point = points.get(i);
            float duration = point.duration;
            if (point.elapsed != duration || duration == 0.0f || point.progress != 1.0f) {
                return false;
            }
            if (!point.fadeOut || Float.floatToRawIntBits(point.maxBrightness) != 0) {
                return false;
            }
            if (mode == ContrailEngine.ContrailWidthMode.WIDEN) {
                if (point.width != point.maxWidth * (widthMultiplier + 1.0f)) {
                    return false;
                }
            } else if (mode == ContrailEngine.ContrailWidthMode.NARROW) {
                if (point.width != point.maxWidth * 0.25f) {
                    return false;
                }
            }
            if (!remove && (point.vel.x != 0.0f || point.vel.y != 0.0f)) {
                return false;
            }
        }
        return true;
    }

    static void advancePoint(ContrailEngineV2.ContrailPoint point, float amount,
                             float removeExtraAge, float fadeOutStep, boolean remove,
                             ContrailEngine.ContrailWidthMode mode, float widthMultiplier) {
        point.elapsed += amount;
        if (remove) {
            point.elapsed += removeExtraAge;
        }
        if (point.elapsed > point.duration) {
            point.elapsed = point.duration;
        }

        // duration==0 时必须保留 0/0 的 NaN 语义。
        float duration = point.duration;
        point.progress = (point.elapsed == duration && duration != 0.0f)
                ? 1.0f
                : point.elapsed / duration;

        if (point.fadeOut) {
            point.maxBrightness -= fadeOutStep;
            if (point.maxBrightness < 0.0f) {
                point.maxBrightness = 0.0f;
            }
        }

        if (mode == ContrailEngine.ContrailWidthMode.WIDEN) {
            point.width = point.maxWidth * (point.progress * widthMultiplier + 1.0f);
        } else if (mode == ContrailEngine.ContrailWidthMode.NARROW) {
            point.width = point.maxWidth * (0.25f + (1.0f - point.progress) * 0.75f);
        }

        if (!remove) {
            point.point.x = point.point.x + point.vel.x * amount;
            point.point.y = point.point.y + point.vel.y * amount;
        }
    }
}
