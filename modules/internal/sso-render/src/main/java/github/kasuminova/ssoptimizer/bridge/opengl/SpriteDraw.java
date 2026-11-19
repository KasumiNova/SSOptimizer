package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.engine.SpriteRenderHelper;
import github.kasuminova.ssoptimizer.common.render.queue.GlCommand;
import github.kasuminova.ssoptimizer.common.render.queue.MergedBatchCommand;
import github.kasuminova.ssoptimizer.common.render.queue.ProbeSiteCommand;
import github.kasuminova.ssoptimizer.common.render.queue.RenderQueueImpl;
import org.lwjgl.opengl.GL12;

import java.util.Arrays;

import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_QUADS;
import static org.lwjgl.opengl.GL11.GL_REPEAT;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T;

public record SpriteDraw(
        int texture, boolean bindTexture, boolean clamp, int rgba, int blendSrc, int blendDest,
        float x, float y, float width, float height, float centerX, float centerY, float angle,
        float u, float v, float texWidth, float texHeight,
        Region region) implements GlCommand {

    private static final float DEG_TO_RAD = 0.017453292519943295769f;
    private static final boolean COMMAND_PROBE = "command".equalsIgnoreCase(
            System.getProperty(RenderQueueImpl.GL_ERROR_PROBE_PROPERTY, "off").trim());
    private static final Batch BATCH = new Batch();

    public record Region(float x, float y, float width, float height, float insetU, float insetV, boolean remapped) {}

    public void submitSingle() {
        submit(COMMAND_PROBE ? new ProbeSiteCommand(this) : this);
    }

    public void submitScoped(long scope) {
        if (clamp || COMMAND_PROBE) {
            submitSingle();
        } else {
            submit(new Scoped(this, scope));
        }
    }

    private void submit(GlCommand command) {
        GlDispatch.submit(command);
        SimulatedGlState state = BridgeSupport.simulatedState();
        if (bindTexture) {
            state.onBindTexture(GL_TEXTURE_2D, texture);
        }
        state.onEnable(GL_TEXTURE_2D);
        state.onDisable(GL_BLEND);
    }

    public void renderOutsideMainQueue() {
        if (BridgeSupport.installedQueue() == null || GlDispatch.isRenderThread()) {
            execute();
            return;
        }
        if (bindTexture) {
            GL11.streamBindTexture(texture);
        }
        if (clamp) {
            recordWrap(GL12.GL_CLAMP_TO_EDGE);
        }
        try {
            GL11.glColor4ub(red(), green(), blue(), alpha());
            GL11.streamEnable(GL_TEXTURE_2D);
            GL11.streamEnable(GL_BLEND);
            GL11.streamBlendFunc(blendSrc, blendDest);
            emit(GL11::streamSpriteQuad);
            GL11.streamDisable(GL_BLEND);
        } finally {
            if (clamp) {
                recordWrap(GL_REPEAT);
            }
        }
    }

    @Override
    public void execute() {
        bind();
        if (clamp) {
            wrap(GL12.GL_CLAMP_TO_EDGE);
        }
        try {
            decode(this::drawSingle);
        } finally {
            if (clamp) {
                wrap(GL_REPEAT);
            }
            if (GlDispatch.isRenderThread()) {
                VertexBatchCommand.onExternalGlStateChange();
            }
        }
    }

    private void drawSingle(float x, float y, float width, float height, float centerX, float centerY,
                            float u, float v, float texWidth, float texHeight) {
        if (!BridgeSupport.isCompilingDisplayList()
                && SpriteRenderHelper.renderNativeSpriteIfReady(x, y, width, height, centerX, centerY, angle,
                red(), green(), blue(), alpha(), blendSrc, blendDest, u, v, texWidth, texHeight)) {
            return;
        }
        color();
        begin();
        try {
            emitQuad(SpriteDraw::vertices, x, y, width, height, centerX, centerY, u, v, texWidth, texHeight);
        } finally {
            end();
        }
    }

    private void decode(RectSink sink) {
        if (region == null) {
            sink.rect(x, y, width, height, centerX, centerY, u, v, texWidth, texHeight);
            return;
        }
        float u0 = region.x * texWidth + region.insetU;
        float v0 = region.y * texHeight + region.insetV;
        float u1 = (region.x + region.width) * texWidth - region.insetU;
        float v1 = (region.y + region.height) * texHeight - region.insetV;
        if (region.remapped) {
            u0 += u;
            u1 += u;
            v0 += v;
            v1 += v;
        }
        boolean hasCenter = centerX != -1.0f && centerY != -1.0f;
        float pivotX = hasCenter ? centerX : width * 0.5f;
        float pivotY = hasCenter ? centerY : height * 0.5f;
        float subWidth = region.width * width;
        float subHeight = region.height * height;
        sink.rect(x + (width - subWidth) * 0.5f, y + (height - subHeight) * 0.5f, subWidth, subHeight,
                pivotX - region.x * width, pivotY - region.y * height, u0, v0, u1 - u0, v1 - v0);
    }

    void emit(QuadSink sink) {
        decode((x, y, width, height, centerX, centerY, u, v, texWidth, texHeight) ->
                emitQuad(sink, x, y, width, height, centerX, centerY, u, v, texWidth, texHeight));
    }

    private void emitQuad(QuadSink sink, float x, float y, float width, float height, float centerX, float centerY,
                          float u, float v, float texWidth, float texHeight) {
        boolean hasCenter = centerX != -1.0f && centerY != -1.0f;
        float cx = hasCenter ? centerX : width * 0.5f;
        float cy = hasCenter ? centerY : height * 0.5f;
        float originX = x + width * 0.5f;
        float originY = y + height * 0.5f;
        if (angle == 0.0f) {
            float x0 = originX - cx;
            float y0 = originY - cy;
            float x1 = x0 + width;
            float y1 = y0 + height;
            sink.quad(x0, y0, x0, y1, x1, y1, x1, y0, u, v, texWidth, texHeight);
            return;
        }
        float left = -cx;
        float right = width - cx;
        float bottom = -cy;
        float top = height - cy;
        float radians = angle * DEG_TO_RAD;
        float sin = (float) Math.sin(radians);
        float cos = (float) Math.cos(radians);
        sink.quad(originX + left * cos - bottom * sin, originY + left * sin + bottom * cos,
                originX + left * cos - top * sin, originY + left * sin + top * cos,
                originX + right * cos - top * sin, originY + right * sin + top * cos,
                originX + right * cos - bottom * sin, originY + right * sin + bottom * cos,
                u, v, texWidth, texHeight);
    }

    private void bind() {
        if (bindTexture) {
            org.lwjgl.opengl.GL11.glBindTexture(GL_TEXTURE_2D, texture);
        }
    }

    private void color() {
        org.lwjgl.opengl.GL11.glColor4ub(red(), green(), blue(), alpha());
    }

    private byte red() { return (byte) (rgba >>> 24); }
    private byte green() { return (byte) (rgba >>> 16); }
    private byte blue() { return (byte) (rgba >>> 8); }
    private byte alpha() { return (byte) rgba; }

    private void begin() {
        org.lwjgl.opengl.GL11.glEnable(GL_TEXTURE_2D);
        org.lwjgl.opengl.GL11.glEnable(GL_BLEND);
        org.lwjgl.opengl.GL11.glBlendFunc(blendSrc, blendDest);
        org.lwjgl.opengl.GL11.glBegin(GL_QUADS);
    }

    private static void end() {
        org.lwjgl.opengl.GL11.glEnd();
        org.lwjgl.opengl.GL11.glDisable(GL_BLEND);
    }

    private static void wrap(int mode) {
        org.lwjgl.opengl.GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, mode);
        org.lwjgl.opengl.GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, mode);
    }

    private static void recordWrap(int mode) {
        GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, mode);
        GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, mode);
    }

    private static void vertices(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3,
                                 float u, float v, float texWidth, float texHeight) {
        float u1 = u + texWidth;
        float v1 = v + texHeight;
        vertex(x0, y0, u, v);
        vertex(x1, y1, u, v1);
        vertex(x2, y2, u1, v1);
        vertex(x3, y3, u1, v);
    }

    private static void vertex(float x, float y, float u, float v) {
        org.lwjgl.opengl.GL11.glTexCoord2f(u, v);
        org.lwjgl.opengl.GL11.glVertex2f(x, y);
    }

    private boolean compatible(SpriteDraw other) {
        return bindTexture == other.bindTexture && (!bindTexture || texture == other.texture)
                && blendSrc == other.blendSrc && blendDest == other.blendDest;
    }

    @FunctionalInterface
    private interface RectSink {
        void rect(float x, float y, float width, float height, float centerX, float centerY,
                  float u, float v, float texWidth, float texHeight);
    }

    @FunctionalInterface
    interface QuadSink {
        void quad(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3,
                  float u, float v, float texWidth, float texHeight);
    }

    private record Scoped(SpriteDraw sprite, long scope) implements MergedBatchCommand {
        @Override
        public void executeMerged(boolean runHead, boolean runTail) {
            if (runHead) {
                BATCH.clear();
            }
            BATCH.add(sprite, scope);
            if (runTail) {
                BATCH.flush();
            }
        }
    }

    private static final class Batch {
        private final SpriteDraw[] sprites = new SpriteDraw[128];
        private int size;
        private long scope;

        void add(SpriteDraw sprite, long currentScope) {
            if (size == sprites.length || size != 0 && (scope != currentScope || !sprites[0].compatible(sprite))) {
                flush();
            }
            scope = currentScope;
            sprites[size++] = sprite;
        }

        void flush() {
            SpriteDraw first = sprites[0];
            try {
                if (size == 1) {
                    first.execute();
                    return;
                }
                first.bind();
                first.begin();
                try {
                    for (int i = 0; i < size; i++) {
                        sprites[i].color();
                        sprites[i].emit(SpriteDraw::vertices);
                    }
                } finally {
                    end();
                    VertexBatchCommand.onExternalGlStateChange();
                }
            } finally {
                clear();
            }
        }

        void clear() {
            Arrays.fill(sprites, 0, size, null);
            size = 0;
        }
    }
}
