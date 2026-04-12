package github.kasuminova.ssoptimizer.mixin.render;

import github.kasuminova.ssoptimizer.common.render.campaign.CampaignLocationMapCanvasPerformanceHelper;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Collection;
import java.util.Set;

/**
 * 战役地图画布实体图标保留热点优化 Mixin。
 * <p>
 * 注入目标：{@code com.fs.starfarer.coreui.CampaignLocationMapCanvas#renderStuff(float, boolean)}<br>
 * 注入动机：原版在地图渲染阶段会对图标缓存执行
 * {@code keySet().retainAll(ArrayList)}，导致大量实体场景下出现 O(m × n) 的 contains 热点。<br>
 * 注入效果：将保留操作重定向到查找集合 helper，把每帧图标缓存修剪降为近似线性时间。
 */
@Mixin(targets = GameMixinSignatures.CampaignLocationMapCanvas.TARGET_CLASS)
public abstract class CampaignLocationMapCanvasMixin {
    @Redirect(
            method = GameMixinSignatures.CampaignLocationMapCanvas.RENDER_STUFF,
            at = @At(value = "INVOKE", target = GameMixinSignatures.CampaignLocationMapCanvas.SET_RETAIN_ALL_TARGET),
            remap = false)
    private boolean ssoptimizer$retainLiveEntitiesWithLookupSet(final Set<?> targetSet,
                                                                final Collection<?> liveEntities) {
        return CampaignLocationMapCanvasPerformanceHelper.retainLiveEntities(targetSet, liveEntities);
    }
}