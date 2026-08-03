package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.graphics.TextureObject;
import github.kasuminova.ssoptimizer.common.render.engine.ParticleBatchHelper;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.awt.Color;

/**
 * 平滑粒子（SmoothParticle）批量化渲染的 Mixin 重写。
 * <p>
 * 注入目标：{@code com.fs.graphics.particle.SmoothParticle} 的
 * {@code preBatch()/render()/postBatch()} 三方法<br>
 * 注入动机：原版每粒子渲染产生 9 次 GL/JNI 调用（颜色 + 4×tex + 4×vert）；
 * 通过整体替换为 {@link ParticleBatchHelper} 的延迟缓冲批次，flush 时一次性输出。<br>
 * 注入效果：preBatch 建立 GL 状态并 beginBatch，render 在 Java 侧缓冲顶点（零 GL 调用），
 * postBatch 统一 flush 并复位纹理状态。
 */
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

    @Shadow(remap = false)
    public abstract float getBrightness();

    @Shadow(remap = false)
    public abstract float getBrightnessOverride();

    @Shadow(remap = false)
    public abstract float getBrightnessMult();

    @Shadow(remap = false)
    public abstract float getX();

    @Shadow(remap = false)
    public abstract float getY();

    /**
     * 建立 GL 状态并开始平滑粒子批次。
     *
     * @author GitHub Copilot
     * @reason 原 ASM 处理器整体替换 preBatch()V 方法体，迁移为等价的 @Overwrite。
     */
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

    /**
     * 将单个平滑粒子缓冲进批次（零 GL 调用）。
     *
     * @author GitHub Copilot
     * @reason 原 ASM 处理器整体替换 render()V 方法体，迁移为等价的 @Overwrite。
     */
    @Overwrite(remap = false)
    public void render() {
        if (getBrightnessOverride() == 0.0f || getBrightnessMult() == 0.0f) {
            return;
        }
        Color adjustedColor = ParticleBatchHelper.adjustBrightness(ssoptimizer$color, getBrightness());
        ParticleBatchHelper.addSmoothParticle(
                adjustedColor.getRed(), adjustedColor.getGreen(), adjustedColor.getBlue(), adjustedColor.getAlpha(),
                getX(), getY(), ssoptimizer$offsetX, ssoptimizer$offsetY, ssoptimizer$size);
    }

    /**
     * 复述 GL 状态后统一 flush 平滑粒子批次。
     *
     * @author GitHub Copilot
     * @reason 原 ASM 处理器整体替换 postBatch()V 方法体，迁移为等价的 @Overwrite。
     */
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
