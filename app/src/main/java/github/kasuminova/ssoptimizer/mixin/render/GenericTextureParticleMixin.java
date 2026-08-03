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
 * 通用贴图粒子（GenericTextureParticle）批量化渲染的 Mixin 重写。
 * <p>
 * 注入目标：{@code com.fs.graphics.particle.GenericTextureParticle} 的
 * {@code preBatch()/render()/postBatch()} 三方法<br>
 * 注入动机：原版每粒子渲染执行 glBlendFunc + 矩阵 + begin/end + 4×(tex+vert)，
 * 且按 renderCount 重复；通过整体替换为 {@link ParticleBatchHelper} 的缓冲批次，
 * 仅在混合模式变化时调用一次 glBlendFunc。<br>
 * 注入效果：render 内完成 brightness/fullyFadedIn/fullBrightnessFraction 分支逻辑后
 * 缓冲旋转顶点（含 renderCount 展开），postBatch 一次 {@code glDrawArrays} 并关闭纹理。
 */
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

    /**
     * 建立 GL 状态并开始通用贴图粒子批次。
     *
     * @author GitHub Copilot
     * @reason 原 ASM 处理器整体替换 preBatch()V 方法体，迁移为等价的 @Overwrite。
     */
    @Overwrite(remap = false)
    public void preBatch() {
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        ssoptimizer$texture.bind();
        ParticleBatchHelper.beginGenericTextureBatch();
    }

    /**
     * 计算粒子亮度（含 fullyFadedIn/fullBrightnessFraction 分支）并缓冲旋转顶点。
     *
     * @author GitHub Copilot
     * @reason 原 ASM 处理器整体替换 render()V 方法体，brightness 分支逻辑由字节码原样搬入 Java。
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

    /**
     * 统一 flush 通用贴图粒子批次并关闭纹理。
     *
     * @author GitHub Copilot
     * @reason 原 ASM 处理器整体替换 postBatch()V 方法体，迁移为等价的 @Overwrite。
     */
    @Overwrite(remap = false)
    public void postBatch() {
        ParticleBatchHelper.flushGenericTextureBatch();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
    }
}
