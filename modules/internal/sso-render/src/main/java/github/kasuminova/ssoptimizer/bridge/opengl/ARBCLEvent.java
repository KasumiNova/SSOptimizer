package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.FrameFence;
import github.kasuminova.ssoptimizer.common.render.queue.FrameFenceImpl;

/**
 * org.lwjgl.opengl.ARBCLEvent 的 bridge 镜像（OpenCL event → GL sync 桥接）。
 * <p>
 * 唯一入口 {@link #glCreateSyncFromCLeventARB} 的签名含对象身份类型 GLSync
 * （返回值），生成器按规则跳过，此处手写补齐：真实 sync 经资源申请阻塞通道在
 * 渲染线程创建（不计 StallDetector），取回后即附着进 bridge 句柄——句柄返回时
 * 真实 sync 已存在，Java 会合点预 signal（与 GL32 aux 原生线程产出形态同理，
 * 见 {@link GL32#glFenceSync}），随后消费方（glWaitSync/glClientWaitSync/
 * glDeleteSync）读 {@link GLSync#realSync()} 即可。
 */
public final class ARBCLEvent extends ARBCLEventGen {
    private ARBCLEvent() {
    }

    /**
     * 从 OpenCL event 创建 GL sync：渲染线程真实创建后包装为 bridge 句柄返回。
     *
     * @param context OpenCL 上下文（直传，阻塞期间引用安全）
     * @param event   OpenCL event（同上）
     * @param flags   保留参数，LWJGL2 语义下必须为 0
     * @return 包装真实 sync 的 bridge 句柄（真实 sync 已附着，会合点预 signal）
     */
    public static GLSync glCreateSyncFromCLeventARB(org.lwjgl.opencl.CLContext context,
                                                    org.lwjgl.opencl.CLEvent event, int flags) {
        Object real = BridgeSupport.blockingGetResource(
                () -> org.lwjgl.opengl.ARBCLEvent.glCreateSyncFromCLeventARB(context, event, flags));
        FrameFence fence = new FrameFenceImpl();
        fence.signal();
        return new GLSync(fence, real);
    }
}
