package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.graphics.Sprite;
import com.fs.graphics.TextureObject;
import com.fs.graphics.util.Fader;
import com.fs.profiler.Profiler;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.campaign.fleet.CampaignFleetMemberView;
import com.fs.starfarer.campaign.fleet.CampaignShipEngineGlow;
import github.kasuminova.ssoptimizer.common.render.campaign.CampaignEngineGlowRenderHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;

/**
 * 战役舰队引擎辉光（CampaignShipEngineGlow）渲染热点的 Mixin 重写。
 * <p>
 * 注入目标：{@link CampaignShipEngineGlow#render(CampaignFleetMemberView, float, float)}<br>
 * 注入动机：原版每船每次渲染含逐槽 3 个 {@code new Vector2f} 分配与 8×3 次逐顶点
 * immediate 开销（async-profiler 生涯场景 14.2s/5.4%，调用方
 * {@code CampaignFleetMemberView.renderSingle} 27.7s/10.5%）；hitGlow 精灵逐槽
 * 重复设置帧内恒定的颜色。<br>
 * 注入效果：{@code render} 方法体替换为
 * {@link CampaignEngineGlowRenderHelper#render}——整船辉光条 quad 单次
 * {@code glDrawArrays(GL_QUADS)}（无分配标量编码，与原版位级一致）、hitGlow
 * 颜色设置合并为一次 + 视口距离 LOD（语义对照与跨船合批取舍见 helper javadoc）。
 * Profiler 包裹保留原版行为。
 */
@Mixin(CampaignShipEngineGlow.class)
public abstract class CampaignShipEngineGlowMixin {
    @Shadow(remap = false)
    private TextureObject glow;

    @Shadow(remap = false)
    private Sprite hitGlow;

    @Shadow(remap = false)
    private List<CampaignShipEngineGlow.SlotData> slots;

    @Shadow(remap = false)
    private Fader accelFader;

    @Shadow(remap = false)
    private Fader fullFader;

    /**
     * 每船每槽的辉光几何缓存（R3 顶点缓存）：槽位 angle/offset/width/baseLength
     * 为构造期定值，8 顶点坐标是 scale 元组的纯函数，稳态巡航下整帧只写颜色字节。
     * 槽数变化（正常不会发生）时整体重建。
     * <p>
     * 必须为 {@code transient}：{@code CampaignShipEngineGlow} 会被战役存档 XStream
     * 序列化（原版 glow/hitGlow 同样标 transient + readResolve 重建），缓存是纯
     * 运行期派生状态，不随存档持久化；读档后字段为 null，首次渲染经下方的
     * null/槽数守卫自然重建。
     */
    @Unique
    private transient CampaignEngineGlowRenderHelper.GlowGeometryCache ssoptimizer$glowGeometryCache;

    /**
     * 整段替换引擎辉光渲染入口：委托 {@link CampaignEngineGlowRenderHelper#render}
     * 完成矩阵/状态命令、辉光条合批与 hitGlow 逐槽渲染（含视口 LOD）。
     *
     * @param view      舰队成员视图（shifter 与舰队数据源）
     * @param facing    船体朝向角
     * @param alphaMult 全局透明度倍率
     * @author SSOptimizer
     * @reason 原版逐槽 Vector2f 分配 + 逐顶点 immediate 发射是生涯场景引擎辉光
     *         主瓶颈；整体替换为位级等价的合批实现（视觉等价性论证见
     *         {@link CampaignEngineGlowRenderHelper} javadoc）。
     */
    @Overwrite(remap = false)
    public void render(final CampaignFleetMemberView view, final float facing, final float alphaMult) {
        Profiler.begin("Campaign engine glow");
        final SectorAPI sector = Global.getSector();
        if (this.ssoptimizer$glowGeometryCache == null
                || this.ssoptimizer$glowGeometryCache.slotCount() != this.slots.size()) {
            this.ssoptimizer$glowGeometryCache =
                    new CampaignEngineGlowRenderHelper.GlowGeometryCache(this.slots.size());
        }
        CampaignEngineGlowRenderHelper.render(
                view, this.slots, this.glow, this.hitGlow,
                this.accelFader, this.fullFader, facing, alphaMult,
                view.getFleet(),
                sector == null ? null : sector.getViewport(),
                this.ssoptimizer$glowGeometryCache);
        Profiler.end();
    }
}
