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

/**
 * 星云粒子（NebulaParticle）批量化渲染的 Mixin 重写。
 * <p>
 * 注入目标：{@code com.fs.graphics.particle.NebulaParticle} 的
 * {@code preBatch()/render()/postBatch()} 三方法<br>
 * 注入动机：原版每粒子渲染执行 glBlendFunc + pushMatrix + rotate + begin/end + 4×(tex+vert)
 * 约 8 次 GL 调用；通过整体替换为 {@link ParticleBatchHelper} 的缓冲批次（CPU 侧旋转 +
 * 自定义图集 UV），仅在混合模式变化时调用一次 glBlendFunc。<br>
 * 注入效果：render 内完成亮度分支逻辑后缓冲旋转顶点（含 tileX/tileY 图集 UV 展开），
 * postBatch 一次 {@code glDrawArrays} 并关闭纹理。negative 组的
 * {@code glBlendEquation(GL_FUNC_REVERSE_SUBTRACT)} 语义保持组级（由首粒子决定，与原版一致）。
 */
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

    /**
     * 建立 GL 状态并开始星云粒子批次（negative 组切换为反向相减混合方程）。
     *
     * @author KasumiNova
     * @reason 整体替换原 preBatch()V：GL 状态建立原样保留，追加 beginNebulaBatch。
     */
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

    /**
     * 计算粒子亮度（含 fullyFadedIn/fullBrightnessFraction 分支）并缓冲图集 UV 旋转顶点。
     *
     * @author KasumiNova
     * @reason 整体替换原 render()V：亮度分支与图集 UV 计算原样搬入，矩阵旋转改为 CPU 侧展开。
     */
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

    /**
     * 统一 flush 星云粒子批次、关闭纹理并恢复混合方程。
     *
     * @author KasumiNova
     * @reason 整体替换原 postBatch()V：状态恢复原样保留，flush 替代逐粒子绘制。
     */
    @Overwrite(remap = false)
    public void postBatch() {
        ParticleBatchHelper.flushNebulaBatch();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        if (ssoptimizer$negative) {
            GL14.glBlendEquation(GL14.GL_FUNC_ADD);
        }
    }
}
