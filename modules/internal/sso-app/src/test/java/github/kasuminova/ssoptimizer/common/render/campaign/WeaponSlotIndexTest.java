package github.kasuminova.ssoptimizer.common.render.campaign;

import com.fs.starfarer.loading.specs.WeaponSlot;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class WeaponSlotIndexTest {
    @Test
    void findReturnsSlotWithMatchingId() {
        final List<WeaponSlot> slots = new ArrayList<>();
        final WeaponSlot first = slot("WS001");
        final WeaponSlot second = slot("WS002");
        slots.add(first);
        slots.add(second);

        final WeaponSlotIndex index = new WeaponSlotIndex();

        assertSame(first, index.find(slots, "WS001"));
        assertSame(second, index.find(slots, "WS002"));
    }

    @Test
    void findUnknownIdReturnsNull() {
        final List<WeaponSlot> slots = new ArrayList<>();
        slots.add(slot("WS001"));

        assertNull(new WeaponSlotIndex().find(slots, "WS999"));
    }

    @Test
    void nullIdLookupReturnsNullLikeLinearScan() {
        final List<WeaponSlot> slots = new ArrayList<>();
        slots.add(slot("WS001"));

        assertNull(new WeaponSlotIndex().find(slots, null));
    }

    @Test
    void duplicateIdKeepsFirstMatchLikeLinearScan() {
        final List<WeaponSlot> slots = new ArrayList<>();
        final WeaponSlot first = slot("WS001");
        slots.add(first);
        slots.add(slot("WS001"));

        assertSame(first, new WeaponSlotIndex().find(slots, "WS001"));
    }

    @Test
    void indexIsBuiltOnceAndReusedAcrossLookups() {
        final IterationCountingList slots = new IterationCountingList();
        slots.add(slot("WS001"));
        slots.add(slot("WS002"));
        final WeaponSlotIndex index = new WeaponSlotIndex();

        index.find(slots, "WS001");
        assertEquals(1, slots.iterations);

        index.find(slots, "WS002");
        index.find(slots, "WS999");
        assertEquals(1, slots.iterations);
    }

    @Test
    void slotAdditionOnSameListTriggersRebuild() {
        final IterationCountingList slots = new IterationCountingList();
        slots.add(slot("WS001"));
        final WeaponSlotIndex index = new WeaponSlotIndex();
        assertNull(index.find(slots, "WS002"));

        final WeaponSlot added = slot("WS002");
        slots.add(added);

        assertSame(added, index.find(slots, "WS002"));
        assertEquals(2, slots.iterations);
    }

    @Test
    void replacedListTriggersRebuildEvenWithSameSize() {
        final List<WeaponSlot> original = new ArrayList<>();
        original.add(slot("WS001"));
        final WeaponSlotIndex index = new WeaponSlotIndex();
        index.find(original, "WS001");

        final List<WeaponSlot> cloned = new ArrayList<>();
        final WeaponSlot clonedSlot = slot("WS001");
        cloned.add(clonedSlot);

        assertSame(clonedSlot, index.find(cloned, "WS001"));
    }

    private static WeaponSlot slot(final String id) {
        return new WeaponSlot(id, null, null, null, new Vector2f(), null, 0f, 0f);
    }

    private static final class IterationCountingList extends ArrayList<WeaponSlot> {
        private int iterations;

        @Override
        public Iterator<WeaponSlot> iterator() {
            iterations++;
            return super.iterator();
        }
    }
}
