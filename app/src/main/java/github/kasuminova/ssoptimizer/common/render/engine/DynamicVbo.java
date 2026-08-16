package github.kasuminova.ssoptimizer.common.render.engine;

import org.lwjgl.opengl.GL15;

import java.nio.ByteBuffer;

/**
 * GL_STREAM_DRAW 环形动态 VBO。
 * <p>
 * 渲染线程每帧多次小批量写入（每艘舰一次 flush 若干组）。写入指针单调前进，
 * 回绕时通过 {@code glBufferData(size, null)} 孤儿化旧存储，避免 CPU-GPU 同步；
 * 单次写入超过容量时整体扩容（记日志，正常战斗规模不应触发）。
 */
public final class DynamicVbo {
    private static final org.apache.log4j.Logger LOGGER = org.apache.log4j.Logger.getLogger(DynamicVbo.class);

    private final int target;
    private final int usage;
    private int       bufferId;
    private int       capacityBytes;
    private int       writeOffset;

    /**
     * 创建并分配环形 VBO。
     *
     * @param target        GL 绑定目标（GL_ARRAY_BUFFER / GL_ELEMENT_ARRAY_BUFFER）
     * @param capacityBytes 初始容量（字节）
     */
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

    /**
     * 将数据写入环形缓冲区。
     *
     * @param data 待写入数据（position..limit 为有效区间）
     * @return 本次写入的起始字节偏移（供 glVertexPointer / glDrawElements 偏移使用）
     */
    public int write(ByteBuffer data) {
        int length = data.remaining();
        if (length > capacityBytes) {
            grow(length);
        }
        GL15.glBindBuffer(target, bufferId);
        if (writeOffset + length > capacityBytes) {
            // 回绕：孤儿化旧存储，避免覆盖尚未被 GPU 消费的数据
            GL15.glBufferData(target, capacityBytes, usage);
            writeOffset = 0;
        }
        GL15.glBufferSubData(target, writeOffset, data);
        int result = writeOffset;
        writeOffset += length;
        return result;
    }

    /** 绑定本 VBO（绘制前调用）。 */
    public void bind() {
        GL15.glBindBuffer(target, bufferId);
    }

    /** 解绑本 VBO 目标。 */
    public void unbind() {
        GL15.glBindBuffer(target, 0);
    }

    /** 释放 GL 缓冲对象。 */
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
