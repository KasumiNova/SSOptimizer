package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.graphics.TextureObject;
import com.fs.graphics.particle.BaseParticle;
import com.fs.graphics.particle.SmokeParticle;
import github.kasuminova.ssoptimizer.common.render.engine.ParticleBatchHelper;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

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
        ssoptimizer$configureBatch();
        ParticleBatchHelper.beginSmoothBatch();
    }

    @Overwrite(remap = false)
    public void render() {
        BaseParticle base = (BaseParticle) (Object) this;
        if (base.getBrightnessOverride() == 0.0f || base.getBrightnessMult() == 0.0f) {
            return;
        }
        ParticleBatchHelper.addSmoothParticle(
                ParticleBatchHelper.brightnessRgba(ssoptimizer$color, base.getBrightness()),
                base.getX(), base.getY(), ssoptimizer$offsetX, ssoptimizer$offsetY, ssoptimizer$size);
    }

    @Overwrite(remap = false)
    public void postBatch() {
        ssoptimizer$configureBatch();
        ParticleBatchHelper.flushSmoothBatch();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
    }

    @Unique
    private void ssoptimizer$configureBatch() {
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        int destinationBlend = (Object) this instanceof SmokeParticle ? GL11.GL_ONE_MINUS_SRC_ALPHA : GL11.GL_ONE;
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, destinationBlend);
        TextureObject texture = ssoptimizer$override != null ? ssoptimizer$override : ssoptimizer$texture;
        texture.bind();
    }
}
