package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.graphics.TextureObject;
import github.kasuminova.ssoptimizer.common.render.engine.ParticleBatchHelper;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 烟雾粒子（SmokeParticle）批量化渲染的 Mixin 重写（正确性修复）。
 * <p>
 * 注入目标：{@code com.fs.graphics.particle.SmokeParticle} 的 {@code preBatch()/postBatch()} 两方法<br>
 * 注入动机：{@code SmokeParticle extends SmoothParticle}，原版 preBatch 在 super.preBatch()
 * 的立即模式批次中 glEnd → 切换混合模式为 (GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA) → glBegin。
 * {@link SmoothParticleMixin} 将父类批次化后，preBatch 里残留的 glEnd/glBegin 变为
 * 无配对的非法调用，且父类 postBatch 在 flush 前会把混合模式重申为加法 (GL_SRC_ALPHA, GL_ONE)，
 * 导致烟雾粒子被错误地以加法混合渲染。<br>
 * 注入效果：preBatch 直接建立与原版 SmokeParticle 等价的 GL 状态（无 glEnd/glBegin），
 * postBatch 在 flush 前重申标准 alpha 混合，render 沿用父类 Mixin 的缓冲实现。
 */
@Mixin(targets = GameClassNames.SMOKE_PARTICLE_DOTTED)
public abstract class SmokeParticleMixin {

    @Shadow(remap = false, aliases = "override")
    private TextureObject ssoptimizer$override;

    @Shadow(remap = false, aliases = "texture")
    private static TextureObject ssoptimizer$texture;

    /**
     * 建立与原版 SmokeParticle 等价的 GL 状态并开始平滑粒子批次。
     *
     * @author KasumiNova
     * @reason 原版 preBatch 的 glEnd/glBlendFunc/glBegin 在父类批次化后已失效，
     * 整体替换为直接建立 (GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA) 混合状态。
     */
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

    /**
     * 重申标准 alpha 混合后统一 flush 批次并关闭纹理。
     *
     * @author KasumiNova
     * @reason 父类 Mixin 的 postBatch 在 flush 前重申的是加法混合，SmokeParticle 必须
     * 以自己的混合模式 flush，否则渲染结果错误。
     */
    @Overwrite(remap = false)
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
