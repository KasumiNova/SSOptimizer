package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.graphics.TextureObject;
import github.kasuminova.ssoptimizer.common.render.engine.ParticleBatchHelper;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/** 父类已批次化，烟雾粒子必须直接建立标准 alpha 混合，不能再执行原版的 glEnd/glBegin。 */
@Mixin(targets = GameClassNames.SMOKE_PARTICLE_DOTTED)
public abstract class SmokeParticleMixin {

    @Shadow(remap = false, aliases = "override")
    private TextureObject ssoptimizer$override;

    @Shadow(remap = false, aliases = "texture")
    private static TextureObject ssoptimizer$texture;

    @Overwrite(remap = false)
    public void preBatch() {
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        if (ssoptimizer$override != null) {
            ssoptimizer$override.bind();
        } else {
            ssoptimizer$texture.bind();
        }
        ParticleBatchHelper.beginSmoothBatch();
    }

    public void postBatch() {
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        if (ssoptimizer$override != null) {
            ssoptimizer$override.bind();
        } else {
            ssoptimizer$texture.bind();
        }
        ParticleBatchHelper.flushSmoothBatch();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
    }
}
