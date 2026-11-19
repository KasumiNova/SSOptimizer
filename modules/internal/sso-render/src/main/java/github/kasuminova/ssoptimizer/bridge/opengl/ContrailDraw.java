package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.DiscardableGlCommand;
import org.lwjgl.opengl.ARBBufferObject;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

import java.nio.ByteBuffer;

public abstract class ContrailDraw implements DiscardableGlCommand {
    protected static final int VERTEX_BYTES = 4 * Float.BYTES + 4;
    protected static final int STRIP_LIMIT = 262_144;
    private PointerSnapshot normal;
    protected CurrentColor currentColor;

    // 同一次 render 的条带在执行线程依序写入末色，所有纹理绑定之后才更新 OpenGL current color。
    public static final class CurrentColor {
        private byte r, g, b, a;

        public void restore(boolean queued) {
            if (queued) {
                GlDispatch.submit(VertexBatchCommand.owned(sink -> sink.color4ub(r, g, b, a)));
            } else {
                GL11.glColor4ub(r, g, b, a);
            }
        }
    }

    protected abstract ByteBuffer expand();

    protected abstract void releaseParameters();

    protected final void submitRecorded() {
        try {
            normal = BridgeSupport.pointerState().retainNormal();
        } catch (RuntimeException | Error failure) {
            discard();
            throw failure;
        }
        GlDispatch.submit(this);
    }

    @Override
    public final void execute() {
        draw(true);
    }

    protected final void draw(boolean queued) {
        try {
            drawArrays(expand(), queued);
        } finally {
            try {
                if (queued) {
                    GlDispatch.onExternalGlStateChange();
                }
            } finally {
                discard();
            }
        }
    }

    @Override
    public final void discard() {
        try {
            if (normal != null) {
                normal.release();
            }
        } finally {
            normal = null;
            currentColor = null;
            releaseParameters();
        }
    }

    private void drawArrays(ByteBuffer vertices, boolean queued) {
        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        try {
            int binding = queued ? BridgeSupport.executedArrayBufferBinding() : 0;
            if (!queued) {
                if (GLContext.getCapabilities().OpenGL15) {
                    GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
                } else if (GLContext.getCapabilities().GL_ARB_vertex_buffer_object) {
                    ARBBufferObject.glBindBufferARB(GL15.GL_ARRAY_BUFFER, 0);
                }
            }
            if (normal != null) {
                int required = normal.data == null ? normal.vboId : 0;
                if (binding != required) {
                    GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, required);
                    binding = required;
                }
                normal.apply();
            }
            if (binding != 0) {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            }
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
            GL11.glVertexPointer(2, GL11.GL_FLOAT, VERTEX_BYTES, vertices);
            vertices.position(8);
            GL11.glTexCoordPointer(2, GL11.GL_FLOAT, VERTEX_BYTES, vertices);
            vertices.position(16);
            GL11.glColorPointer(4, true, VERTEX_BYTES, vertices);
            int count = vertices.limit() / VERTEX_BYTES;
            if (queued && BridgeSupport.isCompilingDisplayList()) {
                // 其他录制线程可以打开显示列表，必须按值写入顶点，供缓冲归还后回放。
                GL11.glBegin(GL11.GL_QUAD_STRIP);
                try {
                    for (int i = 0; i < count; i++) {
                        GL11.glArrayElement(i);
                    }
                } finally {
                    GL11.glEnd();
                }
            } else {
                GL11.glDrawArrays(GL11.GL_QUAD_STRIP, 0, count);
            }
            int color = vertices.limit() - 4;
            currentColor.r = vertices.get(color);
            currentColor.g = vertices.get(color + 1);
            currentColor.b = vertices.get(color + 2);
            currentColor.a = vertices.get(color + 3);
        } finally {
            GL11.glPopClientAttrib();
        }
    }
}
