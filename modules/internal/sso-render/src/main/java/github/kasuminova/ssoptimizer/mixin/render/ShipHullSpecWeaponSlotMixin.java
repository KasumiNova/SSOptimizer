package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.starfarer.loading.specs.ShipHullSpec;
import com.fs.starfarer.loading.specs.WeaponSlot;
import github.kasuminova.ssoptimizer.common.render.campaign.WeaponSlotIndex;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 船体武器槽位查找索引化 Mixin。
 * <p>
 * 注入目标：{@link ShipHullSpec#getWeaponSlot(String)}<br>
 * 注入动机：原版按 id 查找武器槽是对 {@code weaponSlots} 的线性扫描
 * （逐槽 {@code String.equals}），而战役舰队成员视图
 * {@code CampaignFleetMemberView.renderWeapons} 在 zoom 剔除判断之前对每个已装配槽位
 * 无条件调用一次，单船每帧产生数百至上千次字符串比较；{@code weaponSlots}
 * 加载完成后即不可变，查找结果可以索引化摊销。<br>
 * 注入效果：方法头短路返回 {@link WeaponSlotIndex} 的 O(1) 查表结果，返回值语义与原版
 * 完全一致（含未命中返回 {@code null}、重复 id 取首个匹配）；索引实例本身按需创建，
 * 且通过列表引用/长度守卫自动感知 {@code clone()} 与加载期槽位追加，无需额外挂钩。<br>
 * 字段为 {@code transient}：{@code ShipHullSpec} 存在 clone/序列化路径，索引不随实例持久化。
 */
@Mixin(ShipHullSpec.class)
public abstract class ShipHullSpecWeaponSlotMixin {
    @Unique
    private transient volatile WeaponSlotIndex ssoptimizer$slotIndex;

    @Inject(method = "getWeaponSlot", at = @At("HEAD"), cancellable = true, remap = false)
    private void ssoptimizer$getWeaponSlotIndexed(final String id,
                                                  final CallbackInfoReturnable<WeaponSlot> cir) {
        WeaponSlotIndex index = ssoptimizer$slotIndex;
        if (index == null) {
            index = new WeaponSlotIndex();
            ssoptimizer$slotIndex = index;
        }
        cir.setReturnValue(index.find(((ShipHullSpec) (Object) this).getAllWeaponSlots(), id));
    }
}
