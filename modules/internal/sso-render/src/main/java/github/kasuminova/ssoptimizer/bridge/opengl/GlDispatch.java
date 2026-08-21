package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.GlCommand;
import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

import java.util.concurrent.Callable;

/**
 * 跨模块 GL 工作提交门面：sso-render 向其他功能模块（当前为 sso-font 的动态字形图集）
 * 开放的唯一自定义 GL 命令入口。
 * <p>
 * 动机：bridge 的支撑设施（{@link BridgeSupport}）全部包私有，模块外无法把自定义 GL
 * 工作（纹理创建、脏矩形上传、纹理删除）排进渲染线程命令流；图集纹理又必须遵守
 * 「GL 调用只在渲染线程执行」的管线铁律。本门面把三类需求收敛为四个静态方法：
 * 顺序命令、阻塞资源申请、线程判定、上下文重建通知。
 * <p>
 * 线程语义：{@link #submit(GlCommand)} 可在任意生产者线程调用（队列提交通道线程安全）；
 * {@link #allocate(Callable)} 在非渲染线程调用时先提交当前录制帧再阻塞等待结果
 * （顺序语义同 {@code BridgeSupport.blockingGetResource}：返回时此前录制的全部命令
 * 已执行完），渲染线程上调用则直接执行；
 * 监听器回调的调用线程不定（当前为主线程的 Display 创建/切换路径），实现方必须自行
 * 保证线程安全。
 */
public final class GlDispatch {

    private GlDispatch() {
    }

    /**
     * 顺序非阻塞入队：命令追加到当前录制帧，渲染线程按提交顺序执行。
     * 调用方需保证命令体只使用构造时捕获的数据快照（{@link GlCommand} 生命周期约束）。
     * <p>
     * 顺序语义与 {@code BridgeSupport.enqueue} 一致：入队前先把当前线程未落帧的
     * immediate 顶点流打包落帧（空流无操作）——否则本命令会越过调用线程尚未
     * 落帧的流式状态，破坏「流段命令与非流式命令的帧内顺序即录制顺序」的约定。
     * 渲染线程上调用时其录制上下文顶点流恒空，行为同样与 enqueue 一致。
     *
     * @param command 待执行命令
     */
    public static void submit(final GlCommand command) {
        BridgeSupport.flushVertexStream();
        BridgeSupport.queue().submit(command);
    }

    /**
     * 阻塞资源申请通道（glGenTextures 等一次性资源分配）：在渲染线程执行
     * {@code resourceTask} 并阻塞等待结果，不计入 StallDetector（初始化期/按需的
     * 有界分配是合法形态）。
     *
     * @param resourceTask 渲染线程上执行的资源申请逻辑
     * @param <T>          返回值类型
     * @return 资源申请结果
     */
    public static <T> T allocate(final Callable<T> resourceTask) {
        return BridgeSupport.blockingGetResource(resourceTask);
    }

    /**
     * 当前线程是否渲染线程。队列未安装（bridge 未接管 GL，如单测环境）时返回 false。
     */
    public static boolean isRenderThread() {
        final RenderQueue queue = BridgeSupport.installedQueue();
        return queue != null && queue.isRenderThread();
    }

    /**
     * 当前线程是否主录制线程（游戏主循环线程，即帧边界推进线程）。
     * <p>
     * 典型用途：{@link #allocate(Callable)} 在非渲染线程调用时会先
     * {@code swapFrames()} 切割当前录制帧——只有主录制线程发起才是安全的；
     * 其他生产者线程（如字体 CJK 预热 daemon）据此跳过阻塞式资源申请，
     * 只写数据并标脏，由主录制线程补齐。
     */
    public static boolean isMainRecordingThread() {
        return BridgeSupport.isMainRecordingThread();
    }

    /**
     * 注册 GL 上下文重建监听器：Display 创建/显示模式切换/全屏切换成功后回调。
     * 典型用途：持有裸 GL 纹理的资源（字体图集页）把纹理 id 归零并标脏，
     * 下次上传时在新上下文中重建。
     * <p>
     * 回调线程不定（不得假设为渲染线程），实现必须线程安全；单个监听器抛异常
     * 不中断其余监听器的通知。
     *
     * @param listener 上下文重建回调
     */
    public static void registerContextRecreatedListener(final Runnable listener) {
        BridgeSupport.registerContextRecreatedListener(listener);
    }
}
