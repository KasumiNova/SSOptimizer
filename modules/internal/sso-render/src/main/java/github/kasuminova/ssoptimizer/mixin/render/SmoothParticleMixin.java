package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.graphics.TextureObject;
import github.kasuminova.ssoptimizer.common.render.engine.ParticleBatchHelper;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.awt.Color;

@Mixin(targets = GameClassNames.SMOOTH_PARTICLE_DOTTED)
public abstract class SmoothParticleMixin {

    @Shadow(remap = false, aliases = "color")
    private Color ssoptimizer$color;

    @Shadow(remap = false, aliases = "size")
    private float ssoptimizer$size;

    @Shadow(remap = false, aliases = "offsetX")
    private float ssoptimizer$offsetX;

    @Shadow(remap = false, aliases = "offsetY")
    private float ssoptimizer$offsetY;

    @Shadow(remap = false, aliases = "override")
    private TextureObject ssoptimizer$override;

    @Shadow(remap = false, aliases = "texture")
    private static TextureObject ssoptimizer$texture;

    @Overwrite(remap = false)
    public void preBatch() {
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        if (ssoptimizer$override != null) {
            ssoptimizer$override.bind();
        } else {
            ssoptimizer$texture.bind();
        }
        ParticleBatchHelper.beginSmoothBatch();
    }

    @Overwrite(remap = false)
    public void render() {
        com.fs.graphics.particle.BaseParticle base = (com.fs.graphics.particle.BaseParticle) (Object) this;
        if (base.getBrightnessOverride() == 0.0f || base.getBrightnessMult() == 0.0f) {
            return;
        }
        ParticleBatchHelper.addSmoothParticle(
                ParticleBatchHelper.brightnessRgba(ssoptimizer$color, base.getBrightness()),
                base.getX(), base.getY(), ssoptimizer$offsetX, ssoptimizer$offsetY, ssoptimizer$size);
    }

    @Overwrite(remap = false)
    public void postBatch() {
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        if (ssoptimizer$override != null) {
            ssoptimizer$override.bind();
        } else {
            ssoptimizer$texture.bind();
        }
        ParticleBatchHelper.flushSmoothBatch();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
    }
}
