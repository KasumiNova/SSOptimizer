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
 * 详细烟雾粒子（DetailedSmokeParticle）批量化渲染的 Mixin 重写。
 * <p>
 * 注入目标：{@code com.fs.starfarer.renderers.fx.DetailedSmokeParticle} 的
 * {@code preBatch()/render()/postBatch()} 三方法<br>
 * 注入动机：原版每粒子渲染产生 13 次 GL 调用（颜色、矩阵、begin/end、4×tex、4×vert）；
 * 通过整体替换为 {@link ParticleBatchHelper} 的 CPU 侧旋转缓冲批次，flush 时一次 {@code glDrawArrays}。<br>
 * 注入效果：preBatch 建立 GL 状态并 beginSmokeBatch，render 缓冲旋转顶点，postBatch 统一 flush 并关闭纹理。
 */
@Mixin(targets = GameClassNames.DETAILED_SMOKE_PARTICLE_DOTTED)
public abstract class DetailedSmokeParticleMixin {

    @Shadow(remap = false, aliases = "color")
    private Color ssoptimizer$color;

    @Shadow(remap = false, aliases = "size")
    private float ssoptimizer$size;

    @Shadow(remap = false, aliases = "offsetX")
    private float ssoptimizer$offsetX;

    @Shadow(remap = false, aliases = "offsetY")
    private float ssoptimizer$offsetY;

    @Shadow(remap = false, aliases = "texture")
    private static TextureObject ssoptimizer$texture;

    @Shadow(remap = false)
    public abstract float getBrightness();

    @Shadow(remap = false)
    public abstract float getX();

    @Shadow(remap = false)
    public abstract float getY();

    @Shadow(remap = false)
    public abstract float getAngle();

    /**
     * 建立 GL 状态并开始烟雾粒子批次。
     *
     * @author GitHub Copilot
     * @reason 原 ASM 处理器整体替换 preBatch()V 方法体，迁移为等价的 @Overwrite。
     */
    @Overwrite(remap = false)
    public void preBatch() {
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        ssoptimizer$texture.bind();
        ParticleBatchHelper.beginSmokeBatch();
    }

    /**
     * 将单个烟雾粒子（CPU 侧旋转）缓冲进批次。
     *
     * @author GitHub Copilot
     * @reason 原 ASM 处理器整体替换 render()V 方法体，迁移为等价的 @Overwrite。
     */
    @Overwrite(remap = false)
    public void render() {
        ParticleBatchHelper.addSmokeParticle(
                ssoptimizer$color.getRed(), ssoptimizer$color.getGreen(), ssoptimizer$color.getBlue(),
                (int) (ssoptimizer$color.getAlpha() * getBrightness()),
                getX(), getY(), getAngle(),
                ssoptimizer$offsetX, ssoptimizer$offsetY, ssoptimizer$size);
    }

    /**
     * 统一 flush 烟雾粒子批次并关闭纹理。
     *
     * @author GitHub Copilot
     * @reason 原 ASM 处理器整体替换 postBatch()V 方法体，迁移为等价的 @Overwrite。
     */
    @Overwrite(remap = false)
    public void postBatch() {
        ParticleBatchHelper.flushSmokeBatch();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
    }
}
