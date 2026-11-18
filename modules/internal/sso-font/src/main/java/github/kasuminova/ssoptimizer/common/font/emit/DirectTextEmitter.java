package github.kasuminova.ssoptimizer.common.font.emit;

import github.kasuminova.ssoptimizer.common.font.layout.GlyphQuad;
import github.kasuminova.ssoptimizer.common.font.layout.TextPass;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.ARBTextureRectangle;

import java.util.List;

public final class DirectTextEmitter {
    private static ContextCapabilities textureUnitContext;
    private static int textureUnitCount;

    private DirectTextEmitter() {
    }

    private static synchronized int textureUnits(final ContextCapabilities context) {
        if (textureUnitContext != context) {
            textureUnitCount = GL11.glGetInteger(GL13.GL_MAX_TEXTURE_UNITS);
            textureUnitContext = context;
        }
        return textureUnitCount;
    }

    public static void emit(final List<TextPass> passes, final int blendSource, final int blendDestination) {
        final int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT | GL11.GL_TEXTURE_BIT);
        boolean segmentOpen = false;
        try {
            final ContextCapabilities context = GLContext.getCapabilities();
            final int textureUnits = textureUnits(context);
            for (int unit = 0; unit < textureUnits; unit++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
                GL11.glDisable(GL11.GL_TEXTURE_1D);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glDisable(GL12.GL_TEXTURE_3D);
                GL11.glDisable(GL13.GL_TEXTURE_CUBE_MAP);
                if (context.OpenGL31 || context.GL_ARB_texture_rectangle
                        || context.GL_EXT_texture_rectangle || context.GL_NV_texture_rectangle) {
                    GL11.glDisable(ARBTextureRectangle.GL_TEXTURE_RECTANGLE_ARB);
                }
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glDisable(GL11.GL_TEXTURE_GEN_S);
            GL11.glDisable(GL11.GL_TEXTURE_GEN_T);
            GL11.glDisable(GL11.GL_TEXTURE_GEN_R);
            GL11.glDisable(GL11.GL_TEXTURE_GEN_Q);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(blendSource, blendDestination);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
            int texture = -1;
            for (TextPass pass : passes) {
                for (GlyphQuad quad : pass.quads()) {
                    if (quad.textureId() <= 0) {
                        throw new IllegalStateException("Direct font rendering requires an uploaded atlas texture");
                    }
                    if (!segmentOpen || texture != quad.textureId()) {
                        if (segmentOpen) {
                            GL11.glEnd();
                            segmentOpen = false;
                        }
                        GL11.glBindTexture(GL11.GL_TEXTURE_2D, quad.textureId());
                        GL11.glBegin(GL11.GL_QUADS);
                        segmentOpen = true;
                        texture = quad.textureId();
                    }
                    final int color = quad.color();
                    GL11.glColor4ub((byte) (color >>> 16), (byte) (color >>> 8), (byte) color, (byte) (color >>> 24));
                    GL11.glTexCoord2f(quad.u1(), quad.v1());
                    GL11.glVertex2f(quad.x1(), quad.y1());
                    GL11.glTexCoord2f(quad.u2(), quad.v2());
                    GL11.glVertex2f(quad.x2(), quad.y2());
                    GL11.glTexCoord2f(quad.u3(), quad.v3());
                    GL11.glVertex2f(quad.x3(), quad.y3());
                    GL11.glTexCoord2f(quad.u4(), quad.v4());
                    GL11.glVertex2f(quad.x4(), quad.y4());
                }
            }
        } finally {
            if (segmentOpen) {
                GL11.glEnd();
            }
            GL11.glPopAttrib();
            GL13.glActiveTexture(activeTexture);
        }
    }
}
