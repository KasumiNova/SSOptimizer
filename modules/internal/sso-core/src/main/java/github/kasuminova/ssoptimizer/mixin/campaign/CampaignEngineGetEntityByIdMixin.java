package github.kasuminova.ssoptimizer.mixin.campaign;

import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 修复 {@code CampaignEngine.getEntityById(String)} 在 id 不存在时的全星区扫描性能灾难。
 * <p>
 * 注入目标：{@code com.fs.starfarer.campaign.CampaignEngine#getEntityById(String)}<br>
 * 问题链路：MagicLib 赏金板对每个市场校验 {@code trigger_market_id} 时会调用
 * {@code Global.getSector().getEntityById(marketId)}；市场 id 通常不存在同名实体，
 * 原版在 {@code idToEntity} 缓存未命中后会遍历超空间与所有星系、逐实体做
 * {@code toLowerCase} 大小写不敏感比较（每次 miss 约 2-5ms），
 * 「市场数 × 赏金数 × 每次打开赏金板」叠加后单次卡顿可达数秒。<br>
 * 注入效果：{@code idToEntity} 由 {@code objectAdded}/{@code objectRemoved} 增量维护，
 * 本身即为权威索引；本 mixin 额外维护一个大小写不敏感索引以覆盖原版兜底扫描的
 * 大小写语义，map 未命中时直接返回 null，不再全星区扫描。
 * map 命中但位置校验失败的罕见脏状态不拦截，回退原版完整逻辑。
 */
@Mixin(targets = GameMixinSignatures.CampaignEngine.TARGET_CLASS)
public abstract class CampaignEngineGetEntityByIdMixin {

    @Shadow(remap = false)
    private transient Map<String, SectorEntityToken> idToEntity;

    @Unique
    private transient Map<String, SectorEntityToken> ssoptimizer$idToEntityCI;

    @Unique
    private boolean ssoptimizer$fastPathLogged;

    /**
     * 快速路径：精确命中或大小写不敏感命中且位置校验通过时直接返回；
     * 完全未命中时直接返回 null（权威索引已覆盖全星区实体）；
     * 命中但校验失败时不取消，回退原版方法体。
     *
     * @param id  实体 id
     * @param cir 可取消返回值
     */
    @Inject(method = GameMixinSignatures.CampaignEngine.GET_ENTITY_BY_ID,
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private void ssoptimizer$fastGetEntityById(final String id, final CallbackInfoReturnable<SectorEntityToken> cir) {
        if (!this.ssoptimizer$fastPathLogged) {
            this.ssoptimizer$fastPathLogged = true;
            org.apache.log4j.Logger.getLogger("ssoptimizer.mixin").info(
                    "[SSOptimizer] CampaignEngine getEntityById 快速路径已生效");
        }
        // 索引尚未建立时不干预：走原版方法体完成重建与兜底扫描，后续调用进入快速路径
        if (id == null || this.idToEntity == null) {
            return;
        }

        SectorEntityToken hit = this.idToEntity.get(id);
        if (hit == null) {
            final Map<String, SectorEntityToken> ci = this.ssoptimizer$idToEntityCI;
            if (ci == null) {
                return;
            }
            hit = ci.get(id.toLowerCase(Locale.ROOT));
        }
        if (hit == null) {
            cir.setReturnValue(null);
            return;
        }
        if (ssoptimizer$isEntityEntryValid(hit)) {
            cir.setReturnValue(hit);
        }
    }

    /** 与原版一致的位置归属校验：实体必须仍在其声称位置的实体列表中。 */
    @Unique
    private boolean ssoptimizer$isEntityEntryValid(final SectorEntityToken entity) {
        final LocationAPI location = entity.getContainingLocation();
        return location != null && location.getAllEntities().contains(entity);
    }

    /** {@code idToEntity} 重建后同步重建大小写不敏感索引。 */
    @Inject(method = GameMixinSignatures.CampaignEngine.REBUILD_ID_TO_ENTITY_MAP,
            at = @At("TAIL"),
            remap = false)
    private void ssoptimizer$rebuildCaseInsensitiveIndex(final CallbackInfo ci) {
        final Map<String, SectorEntityToken> index = new HashMap<>();
        for (final Map.Entry<String, SectorEntityToken> entry : this.idToEntity.entrySet()) {
            if (entry.getKey() != null) {
                index.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
            }
        }
        this.ssoptimizer$idToEntityCI = index;
    }

    /** 实体新增时增量维护大小写不敏感索引。 */
    @Inject(method = GameMixinSignatures.CampaignEngine.OBJECT_ADDED,
            at = @At("TAIL"),
            remap = false)
    private void ssoptimizer$onObjectAdded(final Object object, final CallbackInfo ci) {
        if (this.ssoptimizer$idToEntityCI == null || !(object instanceof SectorEntityToken)) {
            return;
        }
        final String id = ((SectorEntityToken) object).getId();
        if (id != null) {
            this.ssoptimizer$idToEntityCI.put(id.toLowerCase(Locale.ROOT), (SectorEntityToken) object);
        }
    }

    /** 实体移除时增量维护大小写不敏感索引。 */
    @Inject(method = GameMixinSignatures.CampaignEngine.OBJECT_REMOVED,
            at = @At("TAIL"),
            remap = false)
    private void ssoptimizer$onObjectRemoved(final Object object, final CallbackInfo ci) {
        if (this.ssoptimizer$idToEntityCI == null || !(object instanceof SectorEntityToken)) {
            return;
        }
        final String id = ((SectorEntityToken) object).getId();
        if (id != null) {
            this.ssoptimizer$idToEntityCI.remove(id.toLowerCase(Locale.ROOT));
        }
    }
}
