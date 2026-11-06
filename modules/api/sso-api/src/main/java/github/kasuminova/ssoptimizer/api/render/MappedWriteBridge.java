package github.kasuminova.ssoptimizer.api.render;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * 将持久映射缓冲的 CPU 写入排入渲染命令流，避免逻辑线程在 GPU 仍读取上一帧时改写同一缓冲。
 * <p>
 * 主线程与逻辑辅助线程可并发提交，实际写入仅在渲染线程执行。通道保证写入相对绘制命令的顺序，
 * 在上一帧 GPU 读取结束后落笔，并建立后续绘制所需的内存可见性。目标映射失效或 GPU 同步失败时，
 * 本次写入会被跳过并记录日志。
 * <p>
 * 未注册 = 渲染线程分离未启用，调用方应直接写入真实映射缓冲。
 */
public interface MappedWriteBridge {

    /**
     * 异步提交 {@code source} 在 {@code [position, limit)} 内的快照，并将快照字节写入
     * {@code mappedTarget} 的起始位置。
     * <p>
     * 方法不等待写入完成，返回后 {@code source} 可立即复用或覆写。
     *
     * @param mappedTarget 经渲染桥真实映射路径登记的缓冲，容量必须足以容纳源快照
     * @param source       源数据视图，调用时仅读取其当前剩余元素
     */
    void submitOrderedWrite(ByteBuffer mappedTarget, IntBuffer source);
}
