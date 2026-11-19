package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.engine.EngineBatchNative;
import github.kasuminova.ssoptimizer.common.render.engine.EngineInstanceCollector;
import github.kasuminova.ssoptimizer.common.render.engine.EngineInstanceCollector.CollectedBatch;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.ObjIntConsumer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;

public final class EngineNativeExecutor {
    public static final EngineNativeExecutor INSTANCE = new EngineNativeExecutor();

    private final StreamBuffer vertices = new StreamBuffer(GL_ARRAY_BUFFER, GL_ARRAY_BUFFER_BINDING, 512 * 1024);
    private final StreamBuffer indices = new StreamBuffer(GL_ELEMENT_ARRAY_BUFFER, GL_ELEMENT_ARRAY_BUFFER_BINDING, 128 * 1024);
    private Object contextToken;
    private ByteBuffer encoded;

    private EngineNativeExecutor() {
    }

    public void execute(CollectedBatch batch) {
        Object currentContext = BridgeSupport.mainGlContextToken();
        if (contextToken != currentContext) {
            vertices.forget();
            indices.forget();
            contextToken = currentContext;
        }
        if (BridgeSupport.isCompilingDisplayList()) {
            compileDraw(batch);
            return;
        }
        int size = EngineInstanceCollector.flattenedBytes(batch);
        if (encoded == null || encoded.capacity() < size) {
            encoded = ByteBuffer.allocateDirect(Math.max(size, encoded == null ? 4096 : encoded.capacity() * 2))
                    .order(ByteOrder.nativeOrder());
        }
        int commands = EngineInstanceCollector.flatten(batch, encoded);
        try {
            vertices.reserve(EngineInstanceCollector.expandedVertexBytes(batch));
            indices.reserve(EngineInstanceCollector.expandedIndexBytes(batch));
            long offsets = EngineBatchNative.nativeFlushBatch(encoded, commands,
                    vertices.id, vertices.capacity, vertices.offset, indices.id, indices.capacity, indices.offset);
            vertices.offset = (int) (offsets >>> 32);
            indices.offset = (int) offsets;
        } finally {
            GlDispatch.onExternalGlStateChange();
        }
    }

    private static void compileDraw(CollectedBatch batch) {
        ByteBuffer quad = ByteBuffer.allocate(6 * EngineInstanceCollector.VERTEX_BYTES).order(ByteOrder.nativeOrder());
        ByteBuffer order = ByteBuffer.allocate(12 * Short.BYTES).order(ByteOrder.nativeOrder());
        int unit = glGetInteger(org.lwjgl.opengl.GL13.GL_CLIENT_ACTIVE_TEXTURE);
        glPushAttrib(GL_ENABLE_BIT | GL_COLOR_BUFFER_BIT | GL_TEXTURE_BIT | GL_CURRENT_BIT);
        try {
            glEnable(GL_TEXTURE_2D);
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE);
            for (var group : batch.strips) {
                compileGroup(group.textureId(), group.instances(), EngineInstanceCollector::expandStripVertices,
                        EngineInstanceCollector::appendStripIndices, quad, order, unit);
            }
            for (var group : batch.cores) {
                compileGroup(group.textureId(), group.instances(), EngineInstanceCollector::expandCoreVertices,
                        EngineInstanceCollector::appendCoreIndices, quad, order, unit);
            }
            for (var group : batch.glows) {
                compileGroup(group.textureId(), group.instances(), EngineInstanceCollector::expandGlowVertices,
                        EngineInstanceCollector::appendGlowIndices, quad, order, unit);
            }
        } finally {
            glPopAttrib();
            GlDispatch.onExternalGlStateChange();
        }
    }

    private static <T> void compileGroup(int texture, List<T> instances, BiConsumer<T, ByteBuffer> expand,
                                         ObjIntConsumer<ByteBuffer> writeOrder, ByteBuffer quad, ByteBuffer order, int unit) {
        writeOrder.accept(order.clear(), 0);
        order.flip();
        glBindTexture(GL_TEXTURE_2D, texture);
        glBegin(GL_TRIANGLES);
        try {
            for (T instance : instances) {
                expand.accept(instance, quad.clear());
                for (int i = 0; i < order.limit(); i += Short.BYTES) {
                    int p = order.getShort(i) * EngineInstanceCollector.VERTEX_BYTES;
                    glColor4ub(quad.get(p + 16), quad.get(p + 17), quad.get(p + 18), quad.get(p + 19));
                    org.lwjgl.opengl.GL13.glMultiTexCoord2f(unit, quad.getFloat(p + 8), quad.getFloat(p + 12));
                    glVertex2f(quad.getFloat(p), quad.getFloat(p + 4));
                }
            }
        } finally {
            glEnd();
        }
    }

    private static final class StreamBuffer {
        private final int target;
        private final int binding;
        private final int initialSize;
        private int id;
        private int capacity;
        private int offset;

        private StreamBuffer(int target, int binding, int initialSize) {
            this.target = target;
            this.binding = binding;
            this.initialSize = initialSize;
        }

        private void reserve(int bytes) {
            if (id != 0 && capacity >= bytes) {
                return;
            }
            int previous = glGetInteger(binding);
            if (id == 0) {
                id = glGenBuffers();
            }
            int newSize = Math.max(bytes, Math.max(initialSize, capacity * 2));
            try {
                glBindBuffer(target, id);
                glBufferData(target, newSize, GL_STREAM_DRAW);
                capacity = newSize;
                offset = 0;
            } finally {
                glBindBuffer(target, previous);
            }
        }

        private void forget() {
            id = 0;
            capacity = 0;
            offset = 0;
        }
    }
}
