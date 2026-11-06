package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.graphics.TextureObject;
import github.kasuminova.ssoptimizer.common.render.engine.ParticleBatchHelper;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.awt.Color;

@Mixin(targets = GameClassNames.GENERIC_TEXTURE_PARTICLE_DOTTED)
public abstract class GenericTextureParticleMixin {

    @Shadow(remap = false, aliases = "texture")
    private TextureObject ssoptimizer$texture;

    @Shadow(remap = false, aliases = "color")
    private Color ssoptimizer$color;

    @Shadow(remap = false, aliases = "src")
    private int ssoptimizer$src;

    @Shadow(remap = false, aliases = "dst")
    private int ssoptimizer$dst;

    @Shadow(remap = false, aliases = "offsetX")
    private float ssoptimizer$offsetX;

    @Shadow(remap = false, aliases = "offsetY")
    private float ssoptimizer$offsetY;

    @Shadow(remap = false, aliases = "width")
    private float ssoptimizer$width;

    @Shadow(remap = false, aliases = "height")
    private float ssoptimizer$height;

    @Shadow(remap = false, aliases = "tw")
    private float ssoptimizer$tw;

    @Shadow(remap = false, aliases = "th")
    private float ssoptimizer$th;

    @Shadow(remap = false, aliases = "renderCount")
    private int ssoptimizer$renderCount;

    @Shadow(remap = false)
    boolean fullyFadedIn;

    @Shadow(remap = false, aliases = "fullBrightnessFraction")
    private float ssoptimizer$fullBrightnessFraction;

    @Overwrite(remap = false)
    public void preBatch() {
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        ssoptimizer$texture.bind();
        ParticleBatchHelper.beginGenericTextureBatch();
    }

    @Overwrite(remap = false)
    public void render() {
        com.fs.graphics.particle.BaseParticle base = (com.fs.graphics.particle.BaseParticle) (Object) this;
        float brightness = base.getBrightness();
        if (brightness >= 1.0f) {
            fullyFadedIn = true;
        }
        if (fullyFadedIn && ssoptimizer$fullBrightnessFraction > 0.0f) {
            brightness = base.getAge() / base.getMaxAge();
            if (brightness > ssoptimizer$fullBrightnessFraction) {
                brightness = 1.0f - (brightness - ssoptimizer$fullBrightnessFraction)
                        / (1.0f - ssoptimizer$fullBrightnessFraction);
            } else {
                brightness = 1.0f;
            }
        }
        ParticleBatchHelper.addGenericTextureParticle(
                ssoptimizer$color.getRed(), ssoptimizer$color.getGreen(), ssoptimizer$color.getBlue(),
                (int) (ssoptimizer$color.getAlpha() * brightness),
                ssoptimizer$src, ssoptimizer$dst,
                base.getX(), base.getY(), base.getAngle(),
                ssoptimizer$offsetX, ssoptimizer$offsetY,
                ssoptimizer$width, ssoptimizer$height,
                ssoptimizer$tw, ssoptimizer$th,
                ssoptimizer$renderCount);
    }

    @Overwrite(remap = false)
    public void postBatch() {
        ParticleBatchHelper.flushGenericTextureBatch();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
    }
}
