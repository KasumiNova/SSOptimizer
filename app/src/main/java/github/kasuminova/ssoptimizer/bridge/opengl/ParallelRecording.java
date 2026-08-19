package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderSegment;

import java.util.ArrayList;
import java.util.List;

/**
 * 并行录制编排器的段操作门面（public）：把 {@link BridgeSupport} 的
 * 包内段原语开放给 common 层的编排器（{@code ParallelLayerRenderer}）使用。
 * 语义与不变量全部沿用被委托方：
 * <ul>
 *   <li>{@link #reserveSegments(int)}：仅主线程、worker 分发前调用；</li>
 *   <li>{@link #bindSegment(RenderSegment)}/{@link #unbindSegment()}：worker
 *       段任务首/尾（try/finally），绑定期间禁止阻塞式 GL 调用；</li>
 *   <li>{@link #openNextSerialSegment()}：并行区屏障后主线程开启后续串行段。</li>
 * </ul>
 */
public final class ParallelRecording {
    private ParallelRecording() {
    }

    /**
     * 预定 count 个并行段并解析为段对象数组（worker i 使用返回数组第 i 个）。
     *
     * @param count 段数（>= 1）
     * @return 段对象数组，帧内登记序连续
     */
    public static RenderSegment[] reserveSegments(int count) {
        int base = BridgeSupport.queue().currentFrame().reserveSegments(count);
        List<RenderSegment> resolved = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            resolved.add(BridgeSupport.queue().currentFrame().segment(base + i));
        }
        return resolved.toArray(new RenderSegment[0]);
    }

    /** 见 {@link BridgeSupport#bindSegment(RenderSegment)}。 */
    public static void bindSegment(RenderSegment segment) {
        BridgeSupport.bindSegment(segment);
    }

    /** 见 {@link BridgeSupport#unbindSegment()}。 */
    public static void unbindSegment() {
        BridgeSupport.unbindSegment();
    }

    /** 见 {@link BridgeSupport#openNextSerialSegment()}。 */
    public static RenderSegment openNextSerialSegment() {
        return BridgeSupport.openNextSerialSegment();
    }
}
