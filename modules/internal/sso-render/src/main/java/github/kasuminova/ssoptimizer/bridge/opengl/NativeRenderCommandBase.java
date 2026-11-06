package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.NativeRenderCommand;

import java.nio.ByteBuffer;

/** 执行后无论成败均归还编码缓冲，并使 native 直调后的顶点合并器缓存失效。 */
public abstract class NativeRenderCommandBase implements NativeRenderCommand {
    private final ByteBuffer encoded;

    protected NativeRenderCommandBase(final ByteBuffer encoded) {
        this.encoded = encoded;
    }

    @Override
    public final void execute() {
        try {
            executeNative(encoded);
        } finally {
            GlDispatch.onExternalGlStateChange();
            GlDispatch.snapshotPool().release(encoded);
        }
    }

    /** 在渲染线程执行 native 批次；实现必须恢复其修改的 GL 状态。 */
    protected abstract void executeNative(ByteBuffer encoded);

    protected final ByteBuffer encodedBuffer() {
        return encoded;
    }
}
