package github.kasuminova.ssoptimizer.bridge.opengl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 渲染线程 glad 就绪回调（{@link BridgeSupport#onRenderThreadGlContextReady()}）
 * 的装配守卫验证。无 GL context 的单测环境 glad 不可能就绪
 * （gladLoadGL 经 glGetString(GL_VERSION) 探测版本，无 context 返回 NULL），
 * sync ops 必须保持现状：既不安装 native 实现，也不覆盖测试注入的假实现。
 */
class NativeSyncOpsInstallTest {

    @AfterEach
    void resetSyncOps() {
        BridgeSupport.syncOpsForTesting(RealSyncOpsImpl.INSTANCE);
    }

    @Test
    void keepsDefaultSyncOpsWhenQueueGlUnavailable() {
        BridgeSupport.syncOpsForTesting(RealSyncOpsImpl.INSTANCE);

        BridgeSupport.onRenderThreadGlContextReady();

        assertSame(RealSyncOpsImpl.INSTANCE, BridgeSupport.syncOps());
    }

    @Test
    void doesNotOverwriteInjectedSyncOpsWhenQueueGlUnavailable() {
        final RealSyncOps fake = new RealSyncOps() {
            @Override
            public Object fenceSync(final int condition, final int flags) {
                return null;
            }

            @Override
            public void waitSync(final Object sync, final int flags, final long timeout) {
            }

            @Override
            public int clientWaitSync(final Object sync, final int flags, final long timeout) {
                return 0;
            }

            @Override
            public void deleteSync(final Object sync) {
            }

            @Override
            public void memoryBarrier(final int barriers) {
            }
        };
        BridgeSupport.syncOpsForTesting(fake);

        BridgeSupport.onRenderThreadGlContextReady();

        assertSame(fake, BridgeSupport.syncOps());
    }
}
