package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.FrameFence;
import github.kasuminova.ssoptimizer.common.render.queue.FrameFenceImpl;

/**
 * 返回的 {@link GLSync} 已附着渲染线程创建的真实 sync，其 Java 会合点已预 signal。
 */
public final class ARBCLEvent extends ARBCLEventGen {
    private ARBCLEvent() {
    }

    public static GLSync glCreateSyncFromCLeventARB(org.lwjgl.opencl.CLContext context,
                                                    org.lwjgl.opencl.CLEvent event, int flags) {
        Object real = BridgeSupport.blockingGetResource(
                () -> org.lwjgl.opengl.ARBCLEvent.glCreateSyncFromCLeventARB(context, event, flags));
        FrameFence fence = new FrameFenceImpl();
        fence.signal();
        return new GLSync(fence, real);
    }
}
