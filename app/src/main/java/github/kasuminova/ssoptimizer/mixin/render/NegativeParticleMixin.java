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
 * 负片粒子（NegativeParticle）批量化渲染的 Mixin 重写。
 * <p>
 * 注入目标：{@code com.fs.graphics.particle.NegativeParticle} 的
 * {@code preBatch()/render()/postBatch()} 三方法<br>
 * 注入动机：原版每粒子渲染执行颜色 + 4×(tex+vert) 共 9 次 GL 调用（类内静态缓冲路径
 * 从未被启用，实际生效的是立即模式路径）；通过整体替换为 {@link ParticleBatchHelper}
 * 的缓冲批次，postBatch 一次 {@code glDrawArrays} 输出。<br>
 * 注入效果：render 缓冲轴对齐顶点（零 GL 调用，颜色语义与原版立即路径一致：
 * RGB 不缩放、alpha 乘亮度），混合方程 {@code GL_FUNC_REVERSE_SUBTRACT} 在
 * preBatch/postBatch 间保持，与原版一致。
 */
@Mixin(targets = GameClassNames.NEGATIVE_PARTICLE_DOTTED)
public abstract class NegativeParticleMixin {

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

    /**
     * 建立 GL 状态（反向相减混合方程 + 加法混合）并开始负片粒子批次。
     *
     * @author KasumiNova
     * @reason 整体替换原 preBatch()V：GL 状态建立原样保留，glBegin 由缓冲批次替代。
     */
    @Overwrite(remap = false)
    public void preBatch() {
        GL14.glBlendEquation(GL14.GL_FUNC_REVERSE_SUBTRACT);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        if (ssoptimizer$override != null) {
            ssoptimizer$override.bind();
        } else {
            ssoptimizer$texture.bind();
        }
        ParticleBatchHelper.beginNegativeBatch();
    }

    /**
     * 将单个负片粒子（轴对齐）缓冲进批次（零 GL 调用）。
     *
     * @author KasumiNova
     * @reason 整体替换原 render()V：颜色与顶点语义与原版立即路径逐行一致。
     */
    @Overwrite(remap = false)
    public void render() {
        com.fs.graphics.particle.BaseParticle base = (com.fs.graphics.particle.BaseParticle) (Object) this;
        ParticleBatchHelper.addNegativeParticle(
                ssoptimizer$color.getRed(), ssoptimizer$color.getGreen(), ssoptimizer$color.getBlue(),
                (int) (ssoptimizer$color.getAlpha() * base.getBrightness()),
                base.getX(), base.getY(), ssoptimizer$offsetX, ssoptimizer$offsetY, ssoptimizer$size);
    }

    /**
     * 统一 flush 负片粒子批次、关闭纹理并恢复混合方程。
     *
     * @author KasumiNova
     * @reason 整体替换原 postBatch()V：状态恢复原样保留（混合保持开启，与原版一致）。
     */
    @Overwrite(remap = false)
    public void postBatch() {
        ParticleBatchHelper.flushNegativeBatch();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL14.glBlendEquation(GL14.GL_FUNC_ADD);
    }
}
