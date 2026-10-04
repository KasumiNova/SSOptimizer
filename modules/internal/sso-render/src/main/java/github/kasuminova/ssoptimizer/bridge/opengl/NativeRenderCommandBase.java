package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.NativeRenderCommand;

import java.nio.ByteBuffer;

/**
 * {@link NativeRenderCommand} 的基类：封装执行期横切逻辑——编码缓冲归还
 * （共享快照池）与执行后的顶点合并器状态失效，实现类只需提供 native 执行体。
 * <p>
 * 编码缓冲由录制侧经 {@link GlDispatch#snapshotPool()} 借出并随命令构造传入；
 * 执行（渲染线程）结束后无论成败都归还池并作废合并器去重缓存
 * （native glad 调用绕过合并器簿记，见 {@link GlDispatch#onExternalGlStateChange()}）。
 */
public abstract class NativeRenderCommandBase implements NativeRenderCommand {
    /** 录制侧编码的批次数据（执行完归还快照池，归还后不得再引用）。 */
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

    /**
     * native 执行体（渲染线程）：经单次 JNI（glad 直调）完成整批绘制。
     * 实现触碰的 GL 状态必须自行恢复（pushAttrib/pushClientAttrib 或等价），
     * 合并器缓存由基类统一失效，无需另行处理。
     *
     * @param encoded 录制侧编码的批次数据（position=0）
     */
    protected abstract void executeNative(ByteBuffer encoded);

    /** 录制侧编码缓冲（仅供实现类自检与测试；执行归还后不得再引用）。 */
    protected final ByteBuffer encodedBuffer() {
        return encoded;
    }
}
