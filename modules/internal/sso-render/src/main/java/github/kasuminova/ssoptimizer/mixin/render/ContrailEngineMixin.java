package github.kasuminova.ssoptimizer.mixin.render;

import github.kasuminova.ssoptimizer.common.render.engine.ContrailAdvanceHelper;
import github.kasuminova.ssoptimizer.common.render.engine.ContrailBatchHelper;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;

@Mixin(targets = GameClassNames.CONTRAIL_ENGINE_DOTTED)
public abstract class ContrailEngineMixin {

    @SuppressWarnings("rawtypes")
    @Shadow(remap = false, aliases = "groups")
    private Map ssoptimizer$groups;

    @Overwrite(remap = false)
    public void render(float alphaScale) {
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        ContrailBatchHelper.renderContrails(ssoptimizer$groups, alphaScale);
    }

    @Overwrite(remap = false)
    public void advance(float amount) {
        ContrailAdvanceHelper.advance(ssoptimizer$groups, amount);
    }
}
