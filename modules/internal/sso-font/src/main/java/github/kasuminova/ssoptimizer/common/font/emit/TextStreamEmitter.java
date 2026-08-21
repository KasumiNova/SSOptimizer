package github.kasuminova.ssoptimizer.common.font.emit;

import com.fs.graphics.TextureObject;
import github.kasuminova.ssoptimizer.bridge.opengl.GL11;
import github.kasuminova.ssoptimizer.common.font.layout.GlyphQuad;
import github.kasuminova.ssoptimizer.common.font.layout.TextPass;

import java.util.List;

/**
 * 文本 pass 序列的流式发射器：把 {@link TextPass} 编码进 bridge 顶点流，
 * 由渲染线程合并回放，替代原版的逐字形 immediate 调用与 display list 缓存。
 * <p>
 * GL 语义与原版 render() 逐项对应：启用 TEXTURE_2D（原版结束后保持启用，不关）、
 * 绑定字体图集、启用 BLEND + 自定义 blendFunc、逐 pass 一段 glBegin(GL_QUADS)/glEnd、
 * pass 内颜色变化用 glColor4ub（相邻同色去重）、结束后关 BLEND。
 * 与原版的两处刻意差异：不 push/pop 矩阵（pass 偏移与 drawX/drawY 已在布局期烘焙进顶点）、
 * 不录制 display list（新链路逐帧发射，批次合并由渲染线程 VertexArrayBatch 承担）。
 */
public final class TextStreamEmitter {

    private static final int GL_TEXTURE_2D = 3553;
    private static final int GL_BLEND = 3042;
    private static final int GL_QUADS = 7;

    private TextStreamEmitter() {
    }

    /**
     * 绑定字体图集并发射全部 pass。
     *
     * @param passes   布局结果（有序）
     * @param texture  字体页纹理（原版 render() 的 font.getTexture().bind() 语义，
     *                 经懒加载管线确保纹理解码并按去重规则绑定）
     * @param blendSrc 混合源因子（renderer 的 blendSrcFactor）
     * @param blendDst 混合目标因子（renderer 的 blendDstFactor）
     */
    public static void emit(
            final List<TextPass> passes,
            final TextureObject texture,
            final int blendSrc,
            final int blendDst) {
        if (texture == null) {
            throw new IllegalStateException("字体纹理缺失（原版此处同样 NPE）：loadFontTexture 未执行");
        }
        GL11.streamEnable(GL_TEXTURE_2D);
        texture.bind();
        emitPasses(passes, blendSrc, blendDst);
    }

    /**
     * 纹理已绑定后的 pass 发射段：blend 状态设置 → 逐 pass 一段流式 glBegin/glEnd → 关 BLEND。
     * 独立公开是为无 GL 环境下经 bridge 录制验证编码序列（单测用假 RenderQueue 截获）。
     */
    public static void emitPasses(
            final List<TextPass> passes,
            final int blendSrc,
            final int blendDst) {
        GL11.streamEnable(GL_BLEND);
        GL11.streamBlendFunc(blendSrc, blendDst);

        int currentColor = 0;
        boolean hasColor = false;
        for (final TextPass pass : passes) {
            final List<GlyphQuad> quads = pass.quads();
            if (quads.isEmpty()) {
                continue;
            }
            GL11.glBegin(GL_QUADS);
            for (final GlyphQuad q : quads) {
                if (!hasColor || q.color() != currentColor) {
                    final int c = q.color();
                    GL11.glColor4ub((byte) (c >>> 16), (byte) (c >>> 8), (byte) c, (byte) (c >>> 24));
                    currentColor = c;
                    hasColor = true;
                }
                GL11.glTexCoord2f(q.u1(), q.v1());
                GL11.glVertex2f(q.x1(), q.y1());
                GL11.glTexCoord2f(q.u2(), q.v2());
                GL11.glVertex2f(q.x2(), q.y2());
                GL11.glTexCoord2f(q.u3(), q.v3());
                GL11.glVertex2f(q.x3(), q.y3());
                GL11.glTexCoord2f(q.u4(), q.v4());
                GL11.glVertex2f(q.x4(), q.y4());
            }
            GL11.glEnd();
        }

        GL11.streamDisable(GL_BLEND);
    }
}
