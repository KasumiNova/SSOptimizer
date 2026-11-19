package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.api.font.FontDraw.GlyphVertices;

import java.util.List;
import java.util.function.Function;

import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_QUADS;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;

final class FontTextDraw {
    private final GlyphVertices[][] passes;
    private final boolean atlas;
    private final int blendSrc;
    private final int blendDst;

    private FontTextDraw(final GlyphVertices[][] passes, final boolean atlas,
                         final int blendSrc, final int blendDst) {
        this.passes = passes;
        this.atlas = atlas;
        this.blendSrc = blendSrc;
        this.blendDst = blendDst;
    }

    static <P> FontTextDraw capture(final List<P> passes,
                                    final Function<? super P, ? extends List<? extends GlyphVertices>> glyphs,
                                    final boolean atlas, final int blendSrc, final int blendDst) {
        GlyphVertices[][] snapshot = null;
        int index = 0;
        for (final P pass : passes) {
            final List<? extends GlyphVertices> quads = glyphs.apply(pass);
            if (!quads.isEmpty()) {
                if (snapshot == null) {
                    snapshot = new GlyphVertices[passes.size()][];
                }
                snapshot[index++] = quads.toArray(new GlyphVertices[quads.size()]);
            }
        }
        return snapshot == null ? null : new FontTextDraw(snapshot, atlas, blendSrc, blendDst);
    }

    void updateRecordingState(final SimulatedGlState state) {
        if (atlas) {
            state.onEnable(GL_TEXTURE_2D);
            for (final GlyphVertices[] quads : passes) {
                if (quads == null) {
                    break;
                }
                int texture = quads[0].textureId();
                state.onBindTexture(GL_TEXTURE_2D, texture);
                for (int i = 1; i < quads.length; i++) {
                    final int next = quads[i].textureId();
                    if (texture != next) {
                        state.onBindTexture(GL_TEXTURE_2D, next);
                        texture = next;
                    }
                }
            }
        }
        state.onDisable(GL_BLEND);
    }

    void replay(final VertexSink sink) {
        if (atlas) {
            sink.enable(GL_TEXTURE_2D);
        }
        sink.enable(GL_BLEND);
        sink.blendFunc(blendSrc, blendDst);
        boolean hasColor = false;
        int color = 0;
        for (final GlyphVertices[] quads : passes) {
            if (quads == null) {
                break;
            }
            int texture = quads[0].textureId();
            if (atlas) {
                sink.bindTexture(texture);
            }
            sink.begin(GL_QUADS);
            for (final GlyphVertices q : quads) {
                if (atlas && texture != q.textureId()) {
                    sink.end();
                    texture = q.textureId();
                    sink.bindTexture(texture);
                    sink.begin(GL_QUADS);
                }
                if (!hasColor || color != q.color()) {
                    color = q.color();
                    sink.color4ub((byte) (color >>> 16), (byte) (color >>> 8), (byte) color, (byte) (color >>> 24));
                    hasColor = true;
                }
                sink.texCoord2f(q.u1(), q.v1());
                sink.vertex2f(q.x1(), q.y1());
                sink.texCoord2f(q.u2(), q.v2());
                sink.vertex2f(q.x2(), q.y2());
                sink.texCoord2f(q.u3(), q.v3());
                sink.vertex2f(q.x3(), q.y3());
                sink.texCoord2f(q.u4(), q.v4());
                sink.vertex2f(q.x4(), q.y4());
            }
            sink.end();
        }
        sink.disable(GL_BLEND);
    }
}
