package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.starfarer.combat.Bounds;
import github.kasuminova.ssoptimizer.common.render.tessellation.ShipMaskMeshCache;
import github.kasuminova.ssoptimizer.common.render.tessellation.ShipMaskTessellationToggle;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * 舰船形状蒙版三角化的 Mixin 重写。
 * <p>
 * 注入目标：{@code com.fs.starfarer.util.Tesselator#renderTriangles(Bounds, float, float, float)}<br>
 * 注入动机：原版每帧每艘船执行一次 gluNewTess + GLU 单调剖分（回调内立即模式画三角形），
 * 是 stencil 蒙版路径的热点；替换为「耳切三角化 + 按 Bounds 身份缓存 + 单次批量提交」。<br>
 * 注入效果：方法体整体替换为 {@link ShipMaskMeshCache#render} 委托；
 * 开关关闭（{@code -Dssoptimizer.render.shipmasktess.enable=false}）时直接走
 * {@link ShipMaskMeshCache#renderWithGlu} 复刻原版的 GLU 降级路径。
 * 其余 Tesselator 方法（碰撞切分等）不受影响。
 */
@Mixin(targets = GameClassNames.TESSELATOR_DOTTED)
public abstract class TesselatorMixin {

    /**
     * 舰船蒙版三角形渲染。
     *
     * @param bounds 船体轮廓（origSegments 单轮廓闭合简单多边形）
     * @param r      红分量
     * @param g      绿分量
     * @param b      蓝分量
     * @author kasuminova
     * @reason 逐帧 GLU 剖分替换为缓存的耳切三角化结果，消除 stencil 蒙版路径热点。
     */
    @Overwrite(remap = false)
    public static void renderTriangles(Bounds bounds, float r, float g, float b) {
        if (!ShipMaskTessellationToggle.isEnabled()) {
            ShipMaskMeshCache.renderWithGlu(bounds, r, g, b);
            return;
        }
        ShipMaskMeshCache.render(bounds, r, g, b);
    }
}
