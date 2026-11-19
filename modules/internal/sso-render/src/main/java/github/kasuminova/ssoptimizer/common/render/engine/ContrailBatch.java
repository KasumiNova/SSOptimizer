package github.kasuminova.ssoptimizer.common.render.engine;

import github.kasuminova.ssoptimizer.bridge.opengl.ContrailDraw;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;

final class ContrailBatch extends ContrailDraw {
    private static final int SEGMENT_BYTES = 8 * Float.BYTES;
    private static final int TAIL_FLAG = Integer.MIN_VALUE;
    private static final int MAX_IDLE_BYTES = 8 << 20;
    private static final ArrayDeque<ContrailBatch> IDLE = new ArrayDeque<>();
    private static int idleBytes;
    private static final ThreadLocal<ByteBuffer> VERTICES = ThreadLocal.withInitial(() ->
            ByteBuffer.allocateDirect(4096).order(ByteOrder.nativeOrder()));

    // 组头保存段数（最高位表示有尾点）、ARGB 和时长；普通段的八个 float 后可跟一个六 float 的尾点。
    private ByteBuffer raw = ByteBuffer.allocate(1024).order(ByteOrder.nativeOrder());
    private int vertexCount;
    private int header;
    private int segments;
    private float alphaScale;

    private ContrailBatch() {
    }

    static ContrailBatch acquire(float alphaScale, CurrentColor currentColor) {
        ContrailBatch batch;
        synchronized (IDLE) {
            batch = IDLE.pollFirst();
            if (batch != null) {
                idleBytes -= batch.raw.capacity();
            }
        }
        if (batch == null) {
            batch = new ContrailBatch();
        }
        batch.alphaScale = alphaScale;
        batch.currentColor = currentColor;
        return batch;
    }

    boolean full() {
        return vertexCount == STRIP_LIMIT;
    }

    boolean empty() {
        return vertexCount == 0;
    }

    void beginGroup(int argb, float duration, int remaining, boolean hasTail) {
        if (vertexCount != 0) {
            vertexCount += 2;
        }
        header = raw.position();
        segments = 0;
        int space = Math.min(remaining, (STRIP_LIMIT - vertexCount) / 2);
        int required = Math.addExact(header, 12 + space * SEGMENT_BYTES + (hasTail ? 24 : 0));
        if (required > raw.capacity()) {
            ByteBuffer grown = ByteBuffer.allocate(grownCapacity(raw.capacity(), required)).order(raw.order());
            raw.flip();
            raw = grown.put(raw);
        }
        raw.putInt(0).putInt(argb).putFloat(duration);
    }

    void segment(float x, float y, float nx, float ny, float width, float u, float progress, float alphaMult) {
        raw.putFloat(x).putFloat(y).putFloat(nx).putFloat(ny).putFloat(width).putFloat(u)
                .putFloat(progress).putFloat(alphaMult);
        raw.putInt(header, ++segments);
        vertexCount += 2;
    }

    void tail(float x, float y, float nx, float ny, float width, float u) {
        raw.putFloat(x).putFloat(y).putFloat(nx).putFloat(ny).putFloat(width).putFloat(u);
        raw.putInt(header, segments | TAIL_FLAG);
        vertexCount += 2;
    }

    void enqueue() {
        submitRecorded();
    }

    void drawDirect() {
        draw(false);
    }

    @Override
    protected void releaseParameters() {
        raw.clear();
        vertexCount = 0;
        synchronized (IDLE) {
            if (raw.capacity() <= MAX_IDLE_BYTES - idleBytes) {
                IDLE.addFirst(this);
                idleBytes += raw.capacity();
            }
        }
    }

    @Override
    protected ByteBuffer expand() {
        int bytes = Math.multiplyExact(vertexCount, VERTEX_BYTES);
        ByteBuffer out = VERTICES.get();
        if (bytes > out.capacity()) {
            out = ByteBuffer.allocateDirect(grownCapacity(out.capacity(), bytes)).order(ByteOrder.nativeOrder());
            VERTICES.set(out);
        }
        out.clear();
        int offset = 0;
        while (offset < raw.position()) {
            int header = raw.getInt(offset);
            int count = header & ~TAIL_FLAG;
            boolean hasTail = (header & TAIL_FLAG) != 0;
            int argb = raw.getInt(offset + 4);
            float duration = raw.getFloat(offset + 8);
            float fadeWindow = duration <= 0f ? 0.5f : Math.min(0.5f, 0.05f / duration);
            offset += 12;
            if (out.position() != 0) {
                int last = out.position() - VERTEX_BYTES;
                for (int i = 0; i < 2; i++) {
                    out.putLong(out.getLong(last)).putLong(out.getLong(last + 8)).putInt(out.getInt(last + 16));
                }
            }
            for (int i = 0; i < count; i++, offset += SEGMENT_BYTES) {
                float progress = raw.getFloat(offset + 24);
                float brightness = progress < fadeWindow ? progress * 10f : (1f - progress) / (1f - fadeWindow);
                brightness *= alphaScale;
                int alpha = Math.clamp((int) ((argb >>> 24) * raw.getFloat(offset + 28) * brightness), 0, 255);
                putPair(out, offset, 0.5f, argb, alpha);
            }
            if (hasTail) {
                putPair(out, offset, 0.25f, argb, 0);
                offset += 24;
            }
        }
        return out.flip();
    }

    private void putPair(ByteBuffer out, int segment, float widthScale, int argb, int alpha) {
        float x = raw.getFloat(segment);
        float y = raw.getFloat(segment + 4);
        float width = raw.getFloat(segment + 16) * widthScale;
        float dx = raw.getFloat(segment + 8) * width;
        float dy = raw.getFloat(segment + 12) * width;
        float u = raw.getFloat(segment + 20);
        int rgba = (argb << 8) | alpha;
        int packed = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? Integer.reverseBytes(rgba) : rgba;
        out.putFloat(x - dx).putFloat(y - dy).putFloat(u).putFloat(0.01f).putInt(packed);
        out.putFloat(x + dx).putFloat(y + dy).putFloat(u).putFloat(0.99f).putInt(packed);
    }

    private static int grownCapacity(int oldCapacity, int required) {
        return (int) Math.max(required, Math.min(Integer.MAX_VALUE, (long) oldCapacity * 2));
    }
}
