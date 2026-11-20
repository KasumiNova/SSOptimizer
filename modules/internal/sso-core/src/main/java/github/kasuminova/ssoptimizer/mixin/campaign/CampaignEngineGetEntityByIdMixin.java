package github.kasuminova.ssoptimizer.mixin.campaign;

import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
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

/** 仅在完整实体视图重建索引后启用快速查询，避免读档期间遗漏实体。 */
@Mixin(targets = GameMixinSignatures.CampaignEngine.TARGET_CLASS)
public abstract class CampaignEngineGetEntityByIdMixin {

    @Shadow(remap = false)
    private transient Map<String, SectorEntityToken> idToEntity;

    @Unique
    private transient Map<String, SectorEntityToken> ssoptimizer$idToEntityCI;

    @Unique
    private transient boolean ssoptimizer$indexReady;

    @Unique
    private transient boolean ssoptimizer$fastPathLogged;

    @Inject(method = GameMixinSignatures.CampaignEngine.GET_ENTITY_BY_ID,
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private void ssoptimizer$fastGetEntityById(final String id, final CallbackInfoReturnable<SectorEntityToken> cir) {
        if (id == null || this.idToEntity == null
                || !this.ssoptimizer$indexReady || this.ssoptimizer$idToEntityCI == null) {
            return;
        }
        if (!this.ssoptimizer$fastPathLogged) {
            this.ssoptimizer$fastPathLogged = true;
            org.apache.log4j.Logger.getLogger("ssoptimizer.mixin").info(
                    "[SSOptimizer] CampaignEngine getEntityById 快速路径已生效");
        }

        SectorEntityToken hit = this.idToEntity.get(id);
        if (hit == null) {
            hit = this.ssoptimizer$idToEntityCI.get(id.toLowerCase(Locale.ROOT));
        }
        if (hit == null) {
            cir.setReturnValue(null);
            return;
        }
        if (ssoptimizer$isEntityEntryValid(hit)) {
            cir.setReturnValue(hit);
        }
    }

    @Unique
    private boolean ssoptimizer$isEntityEntryValid(final SectorEntityToken entity) {
        final LocationAPI location = entity.getContainingLocation();
        return location != null && location.getAllEntities().contains(entity);
    }

    @Inject(method = GameMixinSignatures.CampaignEngine.READD_CHANGE_LISTENERS,
            at = @At("TAIL"),
            remap = false)
    private void ssoptimizer$buildIndexOnFirstFrame(final CallbackInfo ci) {
        if (this.ssoptimizer$indexReady) {
            return;
        }
        final SectorAPI self = (SectorAPI) (Object) this;
        final Map<String, SectorEntityToken> index = new HashMap<>();
        ssoptimizer$putAll(index, self.getHyperspace());
        for (final LocationAPI system : self.getStarSystems()) {
            ssoptimizer$putAll(index, system);
        }
        this.ssoptimizer$idToEntityCI = index;
        this.ssoptimizer$indexReady = true;
    }

    @Inject(method = GameMixinSignatures.CampaignEngine.REBUILD_ID_TO_ENTITY_MAP,
            at = @At("TAIL"),
            remap = false)
    private void ssoptimizer$rebuildCaseInsensitiveIndex(final CallbackInfo ci) {
        if (!this.ssoptimizer$indexReady) {
            return;
        }
        final Map<String, SectorEntityToken> index = new HashMap<>();
        for (final Map.Entry<String, SectorEntityToken> entry : this.idToEntity.entrySet()) {
            if (entry.getKey() != null) {
                index.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
            }
        }
        this.ssoptimizer$idToEntityCI = index;
    }

    @Inject(method = GameMixinSignatures.CampaignEngine.OBJECT_ADDED,
            at = @At("TAIL"),
            remap = false)
    private void ssoptimizer$onObjectAdded(final Object object, final CallbackInfo ci) {
        if (!this.ssoptimizer$indexReady || this.ssoptimizer$idToEntityCI == null
                || !(object instanceof SectorEntityToken)) {
            return;
        }
        final String id = ((SectorEntityToken) object).getId();
        if (id != null) {
            this.ssoptimizer$idToEntityCI.put(id.toLowerCase(Locale.ROOT), (SectorEntityToken) object);
        }
    }

    @Inject(method = GameMixinSignatures.CampaignEngine.OBJECT_REMOVED,
            at = @At("TAIL"),
            remap = false)
    private void ssoptimizer$onObjectRemoved(final Object object, final CallbackInfo ci) {
        if (!this.ssoptimizer$indexReady || this.ssoptimizer$idToEntityCI == null
                || !(object instanceof SectorEntityToken)) {
            return;
        }
        final String id = ((SectorEntityToken) object).getId();
        if (id != null) {
            this.ssoptimizer$idToEntityCI.remove(id.toLowerCase(Locale.ROOT));
        }
    }

    @Unique
    private static void ssoptimizer$putAll(final Map<String, SectorEntityToken> index, final LocationAPI location) {
        if (location == null) {
            return;
        }
        for (final SectorEntityToken entity : location.getAllEntities()) {
            final String id = entity.getId();
            if (id != null) {
                index.put(id.toLowerCase(Locale.ROOT), entity);
            }
        }
    }
}
