package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.graphics.TextureObject;
import github.kasuminova.ssoptimizer.common.render.engine.ParticleBatchHelper;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.awt.Color;

@Mixin(targets = GameClassNames.NEBULA_PARTICLE_DOTTED)
public abstract class NebulaParticleMixin {

    @Shadow(remap = false, aliases = "texture")
    private TextureObject ssoptimizer$texture;

    @Shadow(remap = false, aliases = "color")
    private Color ssoptimizer$color;

    @Shadow(remap = false, aliases = "offsetX")
    private float ssoptimizer$offsetX;

    @Shadow(remap = false, aliases = "offsetY")
    private float ssoptimizer$offsetY;

    @Shadow(remap = false, aliases = "width")
    private float ssoptimizer$width;

    @Shadow(remap = false, aliases = "height")
    private float ssoptimizer$height;

    @Shadow(remap = false, aliases = "tileX")
    private int ssoptimizer$tileX;

    @Shadow(remap = false, aliases = "tileY")
    private int ssoptimizer$tileY;

    @Shadow(remap = false, aliases = "tileCols")
    private int ssoptimizer$tileCols;

    @Shadow(remap = false, aliases = "src")
    private int ssoptimizer$src;

    @Shadow(remap = false, aliases = "dst")
    private int ssoptimizer$dst;

    @Shadow(remap = false, aliases = "negative")
    private boolean ssoptimizer$negative;

    @Shadow(remap = false)
    boolean fullyFadedIn;

    @Shadow(remap = false, aliases = "fullBrightnessFraction")
    private float ssoptimizer$fullBrightnessFraction;

    @Overwrite(remap = false)
    public void preBatch() {
        if (ssoptimizer$negative) {
            GL14.glBlendEquation(GL14.GL_FUNC_REVERSE_SUBTRACT);
        }
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        ssoptimizer$texture.bind();
        ParticleBatchHelper.beginNebulaBatch();
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
            if (brightness <= ssoptimizer$fullBrightnessFraction) {
                brightness = 1.0f;
            } else {
                brightness = 1.0f - (brightness - ssoptimizer$fullBrightnessFraction)
                        / (1.0f - ssoptimizer$fullBrightnessFraction);
            }
        }

        final float tileFrac = ssoptimizer$tileCols == 2 ? 0.5f : 0.25f;
        final float u0 = ssoptimizer$tileX * tileFrac;
        final float v0 = ssoptimizer$tileY * tileFrac;

        ParticleBatchHelper.addNebulaParticle(
                ssoptimizer$color.getRed(), ssoptimizer$color.getGreen(), ssoptimizer$color.getBlue(),
                (int) (ssoptimizer$color.getAlpha() * brightness),
                ssoptimizer$src, ssoptimizer$dst,
                base.getX(), base.getY(), base.getAngle(),
                ssoptimizer$offsetX, ssoptimizer$offsetY,
                ssoptimizer$width, ssoptimizer$height,
                u0, v0, u0 + tileFrac, v0 + tileFrac);
    }

    @Overwrite(remap = false)
    public void postBatch() {
        ParticleBatchHelper.flushNebulaBatch();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        if (ssoptimizer$negative) {
            GL14.glBlendEquation(GL14.GL_FUNC_ADD);
        }
    }
}
