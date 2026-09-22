package github.kasuminova.ssoptimizer.api.render;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * 持久映射缓冲的「渲染流序写入」通道。
 * <p>
 * 动机：渲染线程分离（RT）模式下，渲染线程执行第 N 帧绘制命令的同时主线程/aux 线程
 * 已在推进第 N+1 帧逻辑。BoxUtil 静态尾迹池这类「持久映射 VBO + CPU 直写映射内存」的
 * 模组子系统，其写入不经过任何 GL 调用，录制层无法感知——逻辑线程的节点写入会与
 * 渲染线程/GPU 正在执行的上一帧绘制并发，读到写了一半的节点数据（表现为 trail 偶发
 * 撕裂成大块不规则三角形）。经本通道提交的写入以命令形式排入渲染流，保证：
 * <ul>
 *   <li>流序：在上一帧全部命令（含全部绘制）之后、本帧后续绘制之前执行；</li>
 *   <li>GPU 序：执行前先对上一帧帧尾 fence 做 clientWaitSync（GPU 侧上一帧的
 *       VBO 读已结束才落笔）；</li>
 *   <li>可见性：写入后插入 {@code GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT} 内存屏障，
 *       使本帧后续绘制读到新数据。</li>
 * </ul>
 * <p>
 * 生命周期安全：目标映射必须经 bridge 的真实映射回退路径创建（自动登记）。池扩容/
 * 销毁导致的 unmap 会使登记令牌失效，在途写入命令执行时跳过（数据由调用方的下一
 * 计算周期重写并自愈），绝不触达已释放的映射内存。
 * <p>
 * 未注册语义：本服务仅在 RT 模式装配时注册；未注册 = 渲染管线未分离，不存在
 * 跨帧并发读写窗口，调用方应回退为直接写映射内存（原版语义）。
 */
public interface MappedWriteBridge {

    /**
     * 将 {@code source} 的当前内容快照（[position, limit)）排入渲染命令流，
     * 执行时按字节写入 {@code mappedTarget} 的开头区间（offset 0 起，
     * 长度为快照字节数）。调用返回后 {@code source} 即可被复用/覆写。
     * <p>
     * 执行时机：所在帧的命令流中按提交序执行；若上一帧因悬挂尚未执行完（fence
     * 等待续跑），本命令会把所在帧一并悬挂重排，保证「上一帧完整执行完才落笔」。
     *
     * @param mappedTarget bridge 真实映射回退路径创建的映射缓冲本体（池的
     *                     {@code getMappingBuffer()} 返回值）
     * @param source       源数据视图（[position, limit) 为待写入内容）
     */
    void submitOrderedWrite(ByteBuffer mappedTarget, IntBuffer source);
}
