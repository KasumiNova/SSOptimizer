package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.engine.ParticleParameters;
import github.kasuminova.ssoptimizer.common.render.engine.ParticleParameters.Vertices;
import github.kasuminova.ssoptimizer.common.render.queue.DiscardableGlCommand;
import github.kasuminova.ssoptimizer.common.render.runtime.NativeRuntime;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.EXTDirectStateAccess;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

public final class ParticleBatchGl implements DiscardableGlCommand {
    private static final CommandPool<ParticleBatchGl> POOL = new CommandPool<>(ParticleBatchGl::new);
    private ParticleParameters params;
    private PointerSnapshot normal;

    private ParticleBatchGl() {
    }

    public static void submit(ParticleParameters params) {
        ParticleBatchGl command = POOL.acquire();
        command.params = params;
        command.normal = params.smooth() ? null : BridgeSupport.pointerState().retainNormal();
        GlDispatch.submit(command);
    }

    @Override
    public void execute() {
        try {
            Vertices vertices = params.expand();
            boolean smooth = params.smooth();
            boolean compiling = BridgeSupport.isCompilingDisplayList();
            if (smooth && (compiling || !canIsolateArrays())) {
                immediate(vertices);
            } else {
                drawArrays(vertices, smooth, compiling);
            }
        } finally {
            try {
                GlDispatch.onExternalGlStateChange();
            } finally {
                discard();
            }
        }
    }

    @Override
    public void discard() {
        if (normal != null) {
            normal.release();
            normal = null;
        }
        params.release();
        params = null;
        POOL.release(this);
    }

    private static boolean canIsolateArrays() {
        ContextCapabilities caps = GLContext.getCapabilities();
        if (!caps.GL_EXT_direct_state_access) {
            return false;
        }
        // 已绑定具名 VAO 时，重置客户端属性会切换并修改调用方未压栈的默认 VAO。
        return !(caps.OpenGL30 || caps.GL_ARB_vertex_array_object || caps.GL_APPLE_vertex_array_object)
                || GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING) == 0;
    }

    private void drawArrays(Vertices vertices, boolean smooth, boolean compiling) {
        ByteBuffer colors = vertices.colors();
        int count = vertices.positions().remaining() / 2;
        int binding = BridgeSupport.executedArrayBufferBinding();
        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        try {
            if (smooth) {
                // immediate 顶点只更新纹理单元零，不读取其他客户端数组。
                EXTDirectStateAccess.glClientAttribDefaultEXT(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
                GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
            }
            int current = binding;
            if (normal != null) {
                int required = normal.data == null ? normal.vboId : 0;
                if (current != required) {
                    GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, required);
                    current = required;
                }
                normal.apply();
            }
            if (current != 0) {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            }
            GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            GL11.glColorPointer(4, true, 0, colors);
            GL11.glVertexPointer(2, 0, vertices.positions());
            GL11.glTexCoordPointer(2, 0, vertices.texCoords());
            if (compiling) {
                // 其他生产者也能开显示列表；按值录入，列表不引用下一批复用的缓冲区。
                GL11.glBegin(GL11.GL_QUADS);
                try {
                    for (int i = 0; i < count; i++) {
                        GL11.glArrayElement(i);
                    }
                } finally {
                    GL11.glEnd();
                }
            } else {
                GL11.glDrawArrays(GL11.GL_QUADS, 0, count);
            }
        } finally {
            GL11.glPopClientAttrib();
        }
        int last = colors.limit() - 4;
        GL11.glColor4ub(colors.get(last), colors.get(last + 1), colors.get(last + 2), colors.get(last + 3));
        if (smooth) {
            GL11.glTexCoord2f(1f, 0f);
        }
    }

    private static void immediate(Vertices vertices) {
        ByteBuffer colors = vertices.colors();
        FloatBuffer positions = vertices.positions(), uv = vertices.texCoords();
        int count = positions.remaining() / 2;
        if (NativeRuntime.isQueueGlReady()) {
            vertices.drawNativeImmediate();
            return;
        }
        GL11.glBegin(GL11.GL_QUADS);
        try {
            for (int i = 0; i < count; i++) {
                if ((i & 3) == 0) {
                    int c = i * 4;
                    GL11.glColor4ub(colors.get(c), colors.get(c + 1), colors.get(c + 2), colors.get(c + 3));
                }
                GL11.glTexCoord2f(uv.get(i * 2), uv.get(i * 2 + 1));
                GL11.glVertex2f(positions.get(i * 2), positions.get(i * 2 + 1));
            }
        } finally {
            GL11.glEnd();
        }
    }
}
