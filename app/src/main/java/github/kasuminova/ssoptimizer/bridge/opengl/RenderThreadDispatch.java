package github.kasuminova.ssoptimizer.bridge.opengl;

/**
 * 渲染线程同步派发入口：把一段含 GL 调用的逻辑整体迁移到渲染线程执行并阻塞等待完成。
 * <p>
 * 动机：渲染线程分离模式下 GL 上下文只存在于渲染线程，但个别功能（如保存进度
 * 原版界面回放）需要在「持有上下文的线程」上同步跑一段原版渲染代码。直接在
 * 主线程内联执行会打破游戏内静态渲染状态（如 GLListManager 的构建标记）的
 * 单线程假设——渲染线程上尚有在飞帧在同一块状态上交错操作。
 * <p>
 * 语义同 {@code BridgeSupport.blockingWaitResource}：非渲染线程调用时先把当前录制帧
 * （含未落帧顶点流）提交进渲染线程保持相对顺序，再经阻塞通道执行并等待；
 * 渲染线程上调用时直接执行（避免自死锁）。任务体内抛出的异常会向调用线程传播。
 * <p>
 * 本类是 bridge 包对功能层开放的派发门面（非 LWJGL 镜像类，不进入
 * RenderThreadRedirector 的镜像表）。走不计入 StallDetector 的通道：派发场景
 * （如保存进度回放）是有界非稳态过程，与加载期的成批一次性分配同类——期间每次
 * 回放都伴随一次帧同步，若计入熔断窗口，几十次回放即可误触「稳态逐帧回读」熔断；
 * 熔断针对的是加载结束后的逐帧 getter 回读打穿管线，不是此类有界场景。
 * 逐帧高频路径仍禁止走本通道（每次都是一次全管线 drain）。
 */
public final class RenderThreadDispatch {
    private RenderThreadDispatch() {
    }

    /**
     * 在渲染线程上同步执行任务并阻塞等待完成。
     *
     * @param task 要在渲染线程执行的逻辑（可含 bridge GL 调用，照常录制）
     */
    public static void runBlocking(final Runnable task) {
        BridgeSupport.blockingWaitResource(task);
    }
}
