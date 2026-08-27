package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.starfarer.campaign.fleet.ContrailEngineV2;
import github.kasuminova.ssoptimizer.common.render.campaign.CampaignContrailAdvanceHelper;
import github.kasuminova.ssoptimizer.common.render.campaign.CampaignContrailBatchHelper;
import github.kasuminova.ssoptimizer.common.render.campaign.CampaignFleetPerformanceHelper;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * 战役舰队尾迹（ContrailEngineV2）的 Mixin 重写。
 * <p>
 * 注入目标：{@link ContrailEngineV2}，覆盖 {@code render(F)} 与 {@code advance(F)}
 * 两个入口，并在 {@code addPoint} 方法头做点数上限拦截。<br>
 * 注入动机：原版 {@code render} 逐条尾迹 {@code glBegin(GL_QUAD_STRIP)} + 逐点
 * immediate 发射（每点 2 次 segmentIntersection + 4+ 个 new Vector2f + fade/
 * proximity 数学），是生涯场景 12.6s/7% 的尾迹热点；{@code advance} 逐点重复读
 * 组字段并含仅写局部变量的死计算；原版尾迹点数只增不减，高速场景点数膨胀放大
 * 上述开销。<br>
 * 注入效果：{@code render(float)} 方法体替换为「glEnable ×2 +
 * {@link CampaignContrailBatchHelper#renderContrails}」（按混合模式分段的批量
 * {@code glDrawArrays(GL_QUAD_STRIP)}，语义对照见 helper javadoc）；
 * {@code advance(float)} 委托 {@link CampaignContrailAdvanceHelper#advance}
 * （与原版逐点公式位级一致）；{@code addPoint} 方法头检查目标尾迹点数，达到
 * {@link CampaignFleetPerformanceHelper#CONTRAIL_MAX_POINTS} 上限时取消本次补点，
 * 旧点仍由 {@code advance} 的老化逻辑移除，正常飞行（稳态百级点数）不触发拦截。
 */
@Mixin(ContrailEngineV2.class)
public abstract class ContrailEngineV2Mixin {
    @Shadow(remap = false)
    private Map<Object, ContrailEngineV2.Contrail> contrails;

    /**
     * 整段替换尾迹渲染入口：开启纹理与混合后交给批量 helper 按混合模式分段 flush。
     *
     * @param alphaMult 全局透明度缩放
     * @author SSOptimizer
     * @reason 原版逐条 immediate-mode 发射产生全场景约 45k 流调用 + 500 次
     *         begin/end 每帧，逐点 Vector2f 分配与 segmentIntersection 是生涯场景
     *         主瓶颈；整体替换为战斗侧已验证的合批范式（视觉等价性论证见
     *         {@link CampaignContrailBatchHelper} javadoc）。
     */
    @Overwrite(remap = false)
    public void render(float alphaMult) {
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        CampaignContrailBatchHelper.renderContrails(contrails, alphaMult);
    }

    /**
     * 整段替换尾迹推进入口：委托 {@link CampaignContrailAdvanceHelper} 完成逐条
     * 点更新、过期头段移除与 remove/autoCleanup 尾迹清理（与原版逐点公式位级
     * 一致，语义等价论证见 {@link CampaignContrailAdvanceHelper} 的 javadoc）。
     *
     * @param amount 帧推进量
     * @author SSOptimizer
     * @reason 原版逐点重读组字段（remove/mode/widthMultiplier）、逐点重算帧内
     *         恒量（amount/3、amount*2）并含死计算（var5/var6），与逐点 Vector2f
     *         数学叠加成 advance 热点；整体替换为等价优化实现。
     */
    @Overwrite(remap = false)
    public void advance(float amount) {
        CampaignContrailAdvanceHelper.advance(contrails, amount);
    }

    @Inject(method = "addPoint", at = @At("HEAD"), cancellable = true, remap = false)
    private void ssoptimizer$capContrailPoints(final Object source,
                                               final Vector2f point,
                                               final Vector2f vel,
                                               final float brightness,
                                               final CallbackInfo ci) {
        if (CampaignFleetPerformanceHelper.isContrailPointCapReached(
                contrails.get(source), CampaignFleetPerformanceHelper.CONTRAIL_MAX_POINTS)) {
            ci.cancel();
        }
    }
}
