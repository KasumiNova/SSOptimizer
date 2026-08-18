package github.kasuminova.ssoptimizer.mixin.render;

import github.kasuminova.ssoptimizer.common.render.engine.ContrailAdvanceHelper;
import github.kasuminova.ssoptimizer.common.render.engine.ContrailBatchHelper;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;

/**
 * 引擎尾焰轨迹（ContrailEngine）的 Mixin 重写。
 * <p>
 * 注入目标：{@code com.fs.starfarer.combat.entities.ContrailEngine}，覆盖
 * {@code render(F)} 与 {@code advance(F)} 两个入口。<br>
 * 注入动机：原版的逐段 immediate-mode 发射会产生大量 GL 调用；通过整体替换
 * 渲染入口，保留按组纹理/混合语义，把段发射折叠为 {@link ContrailBatchHelper}
 * 的批量 {@code glDrawArrays(GL_QUAD_STRIP)}。advance 为 v49 profile 热点
 * （2,404 样本，4.9% 主线程，LinkedList 迭代 + 逐段更新数学），替换为数组容器
 * 上的等价实现（见 {@link ContrailAdvanceHelper}）。<br>
 * 注入效果：{@code render(float)} 方法体替换为「glEnable ×2 +
 * renderContrails(this.groups, alphaScale)」；{@code advance(float)} 委托
 * {@link ContrailAdvanceHelper#advance(Map, float)}。
 */
@Mixin(targets = GameClassNames.CONTRAIL_ENGINE_DOTTED)
public abstract class ContrailEngineMixin {

    @SuppressWarnings("rawtypes")
    @Shadow(remap = false, aliases = "groups")
    private Map ssoptimizer$groups;

    /**
     * 整段替换尾迹渲染入口：开启纹理与混合后交给批量 helper 按组 flush。
     *
     * @param alphaScale 全局透明度缩放
     * @author GitHub Copilot
     * @reason 原 ASM 处理器整体替换 render(F)V 方法体，迁移为等价的 @Overwrite。
     */
    @Overwrite(remap = false)
    public void render(float alphaScale) {
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        ContrailBatchHelper.renderContrails(ssoptimizer$groups, alphaScale);
    }

    /**
     * 整段替换尾迹推进入口：委托 {@link ContrailAdvanceHelper} 在数组后备容器上
     * 完成逐组段更新、过期头段移除与 ended 组移除（与原版逐段公式位级一致，
     * 语义等价论证见 {@link ContrailAdvanceHelper} 的 javadoc）。
     *
     * @param amount 帧推进量
     * @author GitHub Copilot
     * @reason 原方法体的 LinkedList 迭代与逐段冗余计算（死计算 f6/f7/f10、
     *         组字段逐段重读、完全老化段的除法）是 v49 profile 热点；容器已由
     *         {@link ContrailGroupMixin} 数组化，整体替换为等价优化实现。
     */
    @Overwrite(remap = false)
    public void advance(float amount) {
        ContrailAdvanceHelper.advance(ssoptimizer$groups, amount);
    }
}
