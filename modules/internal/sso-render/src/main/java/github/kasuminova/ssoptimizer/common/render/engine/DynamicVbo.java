package github.kasuminova.ssoptimizer.common.render.engine;

import org.lwjgl.opengl.GL15;

import java.nio.ByteBuffer;

/** 环形 VBO 回绕时孤儿化旧存储，避免覆盖 GPU 尚未消费的数据。 */
public final class DynamicVbo {
    private static final org.apache.log4j.Logger LOGGER = org.apache.log4j.Logger.getLogger(DynamicVbo.class);

    private final int target;
    private final int usage;
    private int       bufferId;
    private int       capacityBytes;
    private int       writeOffset;

    public DynamicVbo(int target, int capacityBytes) {
        this(target, capacityBytes, GL15.GL_STREAM_DRAW);
    }

    public DynamicVbo(int target, int capacityBytes, int usage) {
        this.target = target;
        this.usage = usage;
        this.capacityBytes = capacityBytes;
        this.bufferId = GL15.glGenBuffers();
        GL15.glBindBuffer(target, bufferId);
        GL15.glBufferData(target, capacityBytes, usage);
        GL15.glBindBuffer(target, 0);
    }

    public int write(ByteBuffer data) {
        int length = data.remaining();
        if (length > capacityBytes) {
            grow(length);
        }
        GL15.glBindBuffer(target, bufferId);
        if (writeOffset + length > capacityBytes) {
            GL15.glBufferData(target, capacityBytes, usage);
            writeOffset = 0;
        }
        GL15.glBufferSubData(target, writeOffset, data);
        int result = writeOffset;
        writeOffset += length;
        return result;
    }

    public void bind() {
        GL15.glBindBuffer(target, bufferId);
    }

    public int getBufferId() {
        return bufferId;
    }

    public int getCapacityBytes() {
        return capacityBytes;
    }

    /** @return 是否扩容并重置写入偏移；调用方须同步重置 native 偏移。 */
    public boolean ensureCapacity(int requiredBytes) {
        if (requiredBytes <= capacityBytes) {
            return false;
        }
        grow(requiredBytes);
        return true;
    }

    public void unbind() {
        GL15.glBindBuffer(target, 0);
    }

    public void dispose() {
        if (bufferId != 0) {
            GL15.glDeleteBuffers(bufferId);
            bufferId = 0;
        }
    }

    private void grow(int requiredBytes) {
        int newCapacity = Math.max(capacityBytes * 2, requiredBytes);
        LOGGER.info(String.format("[SSOptimizer] 引擎合批 VBO 扩容：%d -> %d 字节", capacityBytes, newCapacity));
        GL15.glBindBuffer(target, bufferId);
        GL15.glBufferData(target, newCapacity, usage);
        GL15.glBindBuffer(target, 0);
        capacityBytes = newCapacity;
        writeOffset = 0;
    }
}
