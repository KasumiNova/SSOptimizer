package github.kasuminova.ssoptimizer.common.render.campaign;

import com.fs.starfarer.loading.specs.WeaponSlot;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 船体武器槽位 id 索引（{@code ShipHullSpec#getWeaponSlot} 的线性扫描替代）。
 * <p>
 * 职责：把「按槽位 id 查找 {@link WeaponSlot}」从 O(n) 线性扫描降为 O(1) 查表。<br>
 * 设计动机：原版 {@code ShipHullSpec.getWeaponSlot} 逐槽 {@code String.equals} 扫描，
 * 战役舰队成员视图 {@code CampaignFleetMemberView.renderWeapons} 在 zoom 剔除判断之前
 * 对每个已装配槽位都要调用一次，热点报告显示该路径占据稳定 CPU 时间；
 * {@code weaponSlots} 列表加载完成后即不可变，索引成本可一次性摊销。<br>
 * 失效策略：索引以「列表引用 + 列表长度」为守卫——{@code ShipHullSpec#clone()} 会替换
 * 整个 {@code weaponSlots} 列表（引用变化），加载期 {@code addWeaponSlot} 会改变长度，
 * 两种变化都会触发重建，因此无需在 clone/加载路径上额外挂钩。<br>
 * 并发语义：索引构建结果通过 {@code volatile} 发布，多线程并发首次查找时可能各自
 * 构建一次等价索引（内容一致，后者覆盖前者），不会产生脏读。
 */
public final class WeaponSlotIndex {
    /** 最近一次构建索引时对应的槽位列表引用（identity 守卫，配合长度守卫覆盖 clone 场景）。 */
    private List<WeaponSlot> indexedSlots;
    /** 最近一次构建索引时的列表长度（长度守卫，覆盖加载期原地 add 场景）。 */
    private int indexedSize = -1;
    /** 槽位 id → 槽位实例 的索引表；volatile 保证多线程下的安全发布。 */
    private volatile Map<String, WeaponSlot> slotById;

    /**
     * 按槽位 id 查找武器槽，必要时惰性重建索引。
     *
     * @param slots 当前船体的武器槽列表（调用方每次传入，用于失效守卫）
     * @param id    槽位 id
     * @return 匹配的槽位实例；未命中返回 {@code null}（与原版线性扫描语义一致，
     *         重复 id 时保留列表中的首个匹配项）
     */
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
            // putIfAbsent 保持原版线性扫描的「首个匹配生效」语义
            index.putIfAbsent(slot.getId(), slot);
        }
        // 先写守卫字段再 volatile 发布索引表，保证读者看到一致的索引状态
        indexedSlots = slots;
        indexedSize = slots.size();
        slotById = index;
        return index;
    }
}
