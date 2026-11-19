package github.kasuminova.ssoptimizer.common.font.emit;

import com.fs.graphics.TextureObject;
import github.kasuminova.ssoptimizer.api.font.FontDraw;
import github.kasuminova.ssoptimizer.bootstrap.ServiceRegistry;
import github.kasuminova.ssoptimizer.bridge.opengl.GL11;
import github.kasuminova.ssoptimizer.common.font.layout.GlyphQuad;
import github.kasuminova.ssoptimizer.common.font.layout.TextPass;

import java.util.List;

public final class TextStreamEmitter {

    private static final int GL_TEXTURE_2D = 3553;
    private static final int GL_BLEND = 3042;
    private static final int GL_QUADS = 7;

    private TextStreamEmitter() {
    }

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

    public static void emit(
            final List<TextPass> passes,
            final int blendSrc,
            final int blendDst) {
        if (!trySubmit(passes, true, blendSrc, blendDst)) {
            GL11.streamEnable(GL_TEXTURE_2D);
            record(passes, true, blendSrc, blendDst);
        }
    }

    public static void emitPasses(
            final List<TextPass> passes,
            final int blendSrc,
            final int blendDst) {
        if (!trySubmit(passes, false, blendSrc, blendDst)) {
            record(passes, false, blendSrc, blendDst);
        }
    }

    private static boolean trySubmit(final List<TextPass> passes, final boolean atlas,
                                     final int blendSrc, final int blendDst) {
        final FontDraw draw = ServiceRegistry.getOrNull(FontDraw.class);
        return draw != null && draw.trySubmit(passes, TextPass::quads, atlas, blendSrc, blendDst);
    }

    private static void record(final List<TextPass> passes, final boolean atlas,
                               final int blendSrc, final int blendDst) {
        GL11.streamEnable(GL_BLEND);
        GL11.streamBlendFunc(blendSrc, blendDst);

        int currentColor = 0;
        boolean hasColor = false;
        for (final TextPass pass : passes) {
            final List<GlyphQuad> quads = pass.quads();
            if (quads.isEmpty()) {
                continue;
            }
            int texture = quads.getFirst().textureId();
            if (atlas) {
                GL11.streamBindTexture(texture);
            }
            GL11.glBegin(GL_QUADS);
            for (final GlyphQuad q : quads) {
                if (atlas && texture != q.textureId()) {
                    GL11.glEnd();
                    texture = q.textureId();
                    GL11.streamBindTexture(texture);
                    GL11.glBegin(GL_QUADS);
                }
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
