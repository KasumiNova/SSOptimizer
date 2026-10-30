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

/**
 * 修复 {@code CampaignEngine.getEntityById(String)} 在 id 不存在时的全星区扫描性能灾难。
 * <p>
 * 注入目标：{@code com.fs.starfarer.campaign.CampaignEngine#getEntityById(String)}<br>
 * 问题链路：MagicLib 赏金板对每个市场校验 {@code trigger_market_id} 时会调用
 * {@code Global.getSector().getEntityById(marketId)}；市场 id 通常不存在同名实体，
 * 原版在 {@code idToEntity} 缓存未命中后会遍历超空间与所有星系、逐实体做
 * {@code toLowerCase} 大小写不敏感比较（每次 miss 约 2-5ms），
 * 「市场数 × 赏金数 × 每次打开赏金板」叠加后单次卡顿可达数秒。<br>
 * 注入效果：维护一个大小写不敏感实体索引，命中且位置校验通过时直接返回；
 * 完全未命中时直接返回 null，不再全星区扫描。
 * map 命中但位置校验失败的罕见脏状态不拦截，回退原版完整逻辑。<br>
 * 完备性门控（关键）：原版 {@code idToEntity} 并非权威索引——它可能在读档
 * unmarshal 期间（星系列表尚未填满）被提前重建，且此后不再整体重建，
 * 原版依赖逐星系回退扫描兜底（实证：lv-267 星系的温室实体在 map 中缺失，
 * 原版回退扫描可解析）。因此快速路径仅在索引可证明完备时启用：
 * 引擎首帧 {@code readdChangeListeners}（每帧调用，读档完成后才会执行）时
 * 从超空间 + 全部星系的实体列表整体重建索引并置就绪标志；此前一律不干预，
 * 走原版完整逻辑。就绪后运行期增删由 {@code objectAdded}/{@code objectRemoved}
 * 增量维护（此时各位置监听器已注册，增删必然通知引擎）。
 */
@Mixin(targets = GameMixinSignatures.CampaignEngine.TARGET_CLASS)
public abstract class CampaignEngineGetEntityByIdMixin {

    @Shadow(remap = false)
    private transient Map<String, SectorEntityToken> idToEntity;

    @Unique
    private transient Map<String, SectorEntityToken> ssoptimizer$idToEntityCI;

    /**
     * 索引就绪标志：仅当引擎首帧 advance 链路（{@code readdChangeListeners}）
     * 从完整宇宙重建索引后置 true。注意读档反序列化不经过构造函数，
     * 该字段在新引擎上恒为 false，快速路径自然关闭。
     */
    @Unique
    private boolean ssoptimizer$indexReady;

    @Unique
    private boolean ssoptimizer$fastPathLogged;

    /**
     * 快速路径：索引未就绪（读档窗口内或引擎尚未进入首帧）时不干预；
     * 就绪后精确命中或大小写不敏感命中且位置校验通过时直接返回；
     * 完全未命中时直接返回 null（索引已覆盖全星区实体）；
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

    /** 与原版一致的位置归属校验：实体必须仍在其声称位置的实体列表中。 */
    @Unique
    private boolean ssoptimizer$isEntityEntryValid(final SectorEntityToken entity) {
        final LocationAPI location = entity.getContainingLocation();
        return location != null && location.getAllEntities().contains(entity);
    }

    /**
     * 引擎首帧入口（{@code advance} 每帧调用 {@code readdChangeListeners}）：
     * 从超空间 + 全部星系的实体列表整体重建大小写不敏感索引并置就绪标志。
     * 此时读档 unmarshal 与 onGameLoad 均已完成，宇宙视图完备；
     * 此后运行期增删经监听器通知引擎，由 objectAdded/objectRemoved 增量维护。
     *
     * @param ci 回调
     */
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

    /** {@code idToEntity} 运行期整体重建后同步重建大小写不敏感索引。 */
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

    /** 实体新增时增量维护大小写不敏感索引（仅在索引就绪后）。 */
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

    /** 实体移除时增量维护大小写不敏感索引（仅在索引就绪后）。 */
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

    /** 把一个位置的全部实体按小写 id 装入索引。 */
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
