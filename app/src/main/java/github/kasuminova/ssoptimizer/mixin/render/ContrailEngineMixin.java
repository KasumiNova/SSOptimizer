package github.kasuminova.ssoptimizer.mixin.render;

import github.kasuminova.ssoptimizer.common.render.engine.ContrailBatchHelper;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;

/**
 * 引擎尾焰轨迹（ContrailEngine）渲染的 Mixin 重写。
 * <p>
 * 注入目标：{@code com.fs.starfarer.combat.entities.ContrailEngine#render(F)}<br>
 * 注入动机：原版的逐段 immediate-mode 发射会产生大量 GL 调用；
 * 通过整体替换渲染入口，保留按组纹理/混合语义，把段发射折叠为
 * {@link ContrailBatchHelper} 的批量 {@code glDrawArrays(GL_QUAD_STRIP)}。<br>
 * 注入效果：{@code render(float)} 方法体替换为「glEnable ×2 + renderContrails(this.groups, alphaScale)」。
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
}
