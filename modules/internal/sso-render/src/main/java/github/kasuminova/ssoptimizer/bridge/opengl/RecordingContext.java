package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderFrame;
import org.jctools.queues.SpscArrayQueue;

/** 每个生产者线程独占使用，主线程可在帧内缓存实例。 */
final class RecordingContext {
    final Thread owner = Thread.currentThread();
    final ClientPointerState pointerState = new ClientPointerState();
    final int[] framebufferBinding = new int[1];
    /** 声明必须先于 vertexStream；渲染线程单生产者、当前录制线程单消费者。 */
    final SpscArrayQueue<byte[]> returnedVertexBuffers =
            new SpscArrayQueue<>(VertexStreamBufferPool.RETURN_INBOX_CAPACITY);
    final VertexStream vertexStream = new VertexStream(returnedVertexBuffers);
    final StateDedup stateDedup = new StateDedup();
    final SimulatedGlState simulatedState = new SimulatedGlState();
    /** 与当前 aux 提交纪元不一致时，必须屏障再同步仿真状态。 */
    long auxStateEpoch;
    long auxResyncSeq = -1;
    RenderFrame dedupFrame;
    boolean vertexStreamStartsOpen;
    /** 置位后当前线程的 bridge GL 调用直接原生执行，不再进入渲染队列。 */
    boolean auxNative;
}
