package github.kasuminova.ssoptimizer.common.render.campaign;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CampaignMapEntityRetentionTest {
    @Test
    void retainsOnlyLiveEntitiesFromListInput() {
        final Set<String> target = new LinkedHashSet<>(List.of("alpha", "beta", "gamma"));

        final boolean changed = CampaignMapEntityRetention.retainLiveEntities(
                target,
                List.of("gamma", "alpha", "delta")
        );

        assertTrue(changed);
        assertEquals(List.of("alpha", "gamma"), List.copyOf(target));
    }

    @Test
    void returnsFalseWhenTargetAlreadyMatchesLookupSet() {
        final Set<String> target = new LinkedHashSet<>(List.of("alpha", "gamma"));

        final boolean changed = CampaignMapEntityRetention.retainLiveEntities(
                target,
                List.of("gamma", "alpha", "delta")
        );

        assertFalse(changed);
        assertEquals(List.of("alpha", "gamma"), List.copyOf(target));
    }

    @Test
    void clearsTargetWhenLiveCollectionIsEmpty() {
        final Set<String> target = new LinkedHashSet<>(List.of("alpha"));

        final boolean changed = CampaignMapEntityRetention.retainLiveEntities(target, List.of());

        assertTrue(changed);
        assertTrue(target.isEmpty());
    }
}