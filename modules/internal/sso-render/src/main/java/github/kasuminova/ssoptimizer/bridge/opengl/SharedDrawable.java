package github.kasuminova.ssoptimizer.bridge.opengl;

import org.apache.log4j.Logger;
import org.lwjgl.LWJGLException;

/**
 * org.lwjgl.opengl.SharedDrawable 的 bridge 门面（解折叠：真实共享上下文）。
 * <p>
 * 动机：BoxUtil 1.5.5 的渲染/逻辑后台线程持有 SharedDrawable 并依赖原生 LWJGL
 * 的「各上下文独立状态机 + 共享对象表 + fence 跨上下文 GPU 序」模型与主线程
 * 并发渲染。早期的折叠实现（纯登记对象、模组线程 GL 全部录制进单渲染线程）
 * 让两个并发命令流在主上下文状态机上指令级交错、互相污染（实机症状：BoxUtil
 * 管线模组的拖尾采到 ShipWeaponAtlas 图集页平铺）。因此恢复真实语义：
 * <ul>
 *   <li>首次 {@link #makeCurrent()} 时创建真实
 *       {@code org.lwjgl.opengl.SharedDrawable}（父 = 真实 Display drawable）。
 *       创建动作经阻塞通道串行到渲染线程执行（与渲染线程的 GLX/X11 操作互斥，
 *       规避显示连接并发风险）；makeCurrent 本身在调用线程原生执行（GL 上下文
 *       当前性是线程私有的）；</li>
 *   <li>makeCurrent 成功的线程被标记为 aux 原生线程
 *       （{@link RecordingContext#auxNative}）：此后该线程的 bridge GL 调用全部
 *       原生直执（{@link BridgeSupport} 各 choke 点旁路），不再向渲染队列提交，
 *       与主线程录制流完全隔离；</li>
 *   <li>{@link #releaseContext()}/{@link #destroy()} 原生直通并复位标记。</li>
 * </ul>
 * 已知边界：显示模式切换/全屏切换重建主上下文后，共享上下文随之失效，行为对齐
 * 原生 LWJGL（模组需自行重建 SharedDrawable），不做自动重建。
 * <p>
 * 可测性：真实上下文的创建经 {@link #realContextFactory} 注入（与
 * {@code BridgeSupport.stateSnapshotSource} 桩同模式），单测替换为假实现。
 */
public final class SharedDrawable implements Drawable {
    private static final Logger LOGGER = Logger.getLogger(SharedDrawable.class);

    /**
     * 真实共享上下文句柄（可注入 seam）：默认实现包裹真实
     * {@code org.lwjgl.opengl.SharedDrawable}（{@link LwjglSharedContext}），
     * 单测注入假实现避免无 GL 环境触碰上下文。
     */
    interface RealSharedContext {
        /** 见 {@code org.lwjgl.opengl.Drawable#makeCurrent()}。 */
        void makeCurrent() throws LWJGLException;

        /** 见 {@code org.lwjgl.opengl.Drawable#releaseContext()}。 */
        void releaseContext() throws LWJGLException;

        /** 见 {@code org.lwjgl.opengl.Drawable#destroy()}。 */
        void destroy() throws LWJGLException;
    }

    /** 真实共享上下文工厂：在渲染线程（阻塞通道）上调用。 */
    interface RealSharedContextFactory {
        RealSharedContext create() throws LWJGLException;
    }

    /** 默认工厂：以真实 Display drawable 为父创建真实 SharedDrawable。 */
    static volatile RealSharedContextFactory realContextFactory =
            () -> new LwjglSharedContext(new org.lwjgl.opengl.SharedDrawable(
                    org.lwjgl.opengl.Display.getDrawable()));

    /** 真实 SharedDrawable 的包裹实现。 */
    private static final class LwjglSharedContext implements RealSharedContext {
        private final org.lwjgl.opengl.SharedDrawable delegate;

        private LwjglSharedContext(final org.lwjgl.opengl.SharedDrawable delegate) {
            this.delegate = delegate;
        }

        @Override
        public void makeCurrent() throws LWJGLException {
            delegate.makeCurrent();
        }

        @Override
        public void releaseContext() throws LWJGLException {
            delegate.releaseContext();
        }

        @Override
        public void destroy() throws LWJGLException {
            delegate.destroy();
        }
    }

    private final Drawable parent;
    private volatile RealSharedContext real;
    private volatile Thread currentThread;
    private volatile boolean destroyed;

    /**
     * @param parent 共享来源 drawable（真实语义为父上下文；bridge 侧仅登记，
     *               真实创建时固定取真实 Display drawable）
     * @throws LWJGLException 签名与 LWJGL 对齐；构造期不触碰 GL，实际不抛出
     */
    public SharedDrawable(Drawable parent) throws LWJGLException {
        this.parent = parent;
    }

    /** 测试用：替换真实上下文工厂（无 GL 上下文环境注入桩）。 */
    static void realContextFactoryForTesting(final RealSharedContextFactory factory) {
        realContextFactory = factory;
    }

    /**
     * @return 构造时登记的父 drawable（诊断用）
     */
    public Drawable parent() {
        return parent;
    }

    @Override
    public void makeCurrent() throws LWJGLException {
        if (destroyed) {
            throw new IllegalStateException("[SSOptimizer] SharedDrawable 已销毁，模组线程仍尝试 makeCurrent");
        }
        RealSharedContext ctx = real;
        if (ctx == null) {
            synchronized (this) {
                ctx = real;
                if (ctx == null) {
                    // 创建串行到渲染线程（此刻本线程尚未置 auxNative，阻塞通道走
                    // 正常队列路径）；makeCurrent 回本线程原生执行
                    final RealSharedContext created =
                            BridgeSupport.blockingGetLwjgl(realContextFactory::create);
                    created.makeCurrent();
                    real = created;
                    ctx = created;
                    LOGGER.info("[SSOptimizer] aux 线程 \"" + Thread.currentThread().getName()
                            + "\" 已持有真实共享 GL 上下文，其 GL 调用切换为原生直执（不再经渲染队列）");
                }
            }
        } else {
            ctx.makeCurrent();
        }
        currentThread = Thread.currentThread();
        BridgeSupport.recordingContext().auxNative = true;
    }

    @Override
    public void releaseContext() throws LWJGLException {
        final RealSharedContext ctx = real;
        if (ctx != null && currentThread == Thread.currentThread()) {
            ctx.releaseContext();
        }
        currentThread = null;
        BridgeSupport.recordingContext().auxNative = false;
    }

    @Override
    public boolean isCurrent() {
        return !destroyed && currentThread == Thread.currentThread();
    }

    @Override
    public void destroy() {
        destroyed = true;
        final RealSharedContext ctx = real;
        if (ctx != null) {
            real = null;
            try {
                ctx.destroy();
            } catch (LWJGLException e) {
                throw new IllegalStateException("[SSOptimizer] 销毁真实共享 GL 上下文失败", e);
            }
        }
        if (currentThread == Thread.currentThread()) {
            BridgeSupport.recordingContext().auxNative = false;
        }
        currentThread = null;
    }
}
