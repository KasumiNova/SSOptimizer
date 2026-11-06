package github.kasuminova.ssoptimizer.common.font.emit;

import com.fs.graphics.TextureObject;
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
        GL11.streamEnable(GL_TEXTURE_2D);
        GL11.streamEnable(GL_BLEND);
        GL11.streamBlendFunc(blendSrc, blendDst);

        int currentColor = 0;
        boolean hasColor = false;
        for (final TextPass pass : passes) {
            final List<GlyphQuad> quads = pass.quads();
            if (quads.isEmpty()) {
                continue;
            }
            int currentTexture = -1;
            boolean segmentOpen = false;
            for (final GlyphQuad q : quads) {
                if (!segmentOpen || q.textureId() != currentTexture) {
                    if (segmentOpen) {
                        GL11.glEnd();
                    }
                    // streamBindTexture 编码为段间指令（begin/end 段内绑定非法）
                    GL11.streamBindTexture(q.textureId());
                    GL11.glBegin(GL_QUADS);
                    currentTexture = q.textureId();
                    segmentOpen = true;
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
            if (segmentOpen) {
                GL11.glEnd();
            }
        }

        GL11.streamDisable(GL_BLEND);
    }

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
