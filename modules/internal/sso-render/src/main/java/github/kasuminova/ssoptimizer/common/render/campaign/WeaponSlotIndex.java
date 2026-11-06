package github.kasuminova.ssoptimizer.common.render.campaign;

import com.fs.starfarer.loading.specs.WeaponSlot;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class WeaponSlotIndex {
    private List<WeaponSlot> indexedSlots;
    private int indexedSize = -1;
    private volatile Map<String, WeaponSlot> slotById;

    public WeaponSlot find(final List<WeaponSlot> slots, final String id) {
        Map<String, WeaponSlot> index = slotById;
        if (index == null || indexedSlots != slots || indexedSize != slots.size()) {
            index = buildIndex(slots);
        }
        return index.get(id);
    }

    private Map<String, WeaponSlot> buildIndex(final List<WeaponSlot> slots) {
        final Map<String, WeaponSlot> index = new HashMap<>(slots.size() * 2);
        for (final WeaponSlot slot : slots) {
            index.putIfAbsent(slot.getId(), slot);
        }
        // 守卫字段必须先于 volatile 索引发布。
        indexedSlots = slots;
        indexedSize = slots.size();
        slotById = index;
        return index;
    }
}
