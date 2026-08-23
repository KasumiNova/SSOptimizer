package github.kasuminova.ssoptimizer.bridge.opengl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * 主线程仿真状态的权威快照：aux 生产者线程活动纪元失效后，经一次阻塞屏障在
 * 渲染线程上批量读回 {@link SimulatedGlState} 跟踪的全部状态，一次性采入簿记。
 * <p>
 * 动机：aux 线程（BoxUtil 后台线程、字体预热 daemon）提交的命令与主线程命令在
 * 渲染线程上共享同一 GL 上下文交错执行，其状态改动不进主线程仿真簿记。逐 getter
 * 回退阻塞读会让 ASTD TexTrailRenderer 这类逐帧保存/恢复惯用法每帧产生多次全管线
 * drain（StallDetector 的设计本意就是拦截这种形态）；把全部跟踪状态收敛进一次屏障
 * 读回，把退化成本压到「aux 活跃期每帧至多一次往返」。
 * <p>
 * 不可观测的残留（记录序近似，接受并在此声明）：
 * <ul>
 *   <li>矩阵栈深不可经 getter 获得——只采栈顶，采入后簿记深度归 1
 *       （aux 活跃期主线程深层 push/pop 的簿记可能与真实栈漂移）；</li>
 *   <li>attrib/client attrib 栈内容不可经 getter 获得——簿记栈保持主线程视角
 *       原样（主线程自己的 push/pop 在其命令流内成对，簿记语义贴近调用方惯用法）；</li>
 *   <li>texture/program 名字集合与 texLevel0Params 无法枚举——aux 线程创建的名字
 *       不进主线程 isTexture/isProgram 簿记（ASTD 拖尾纹理为主线程自建，不受影响）。</li>
 * </ul>
 * {@link #capture()} 只在渲染线程执行（bridge 包是 RenderThreadRedirector 排除包，
 * 直接调真实 LWJGL）；实例为一次性传输载体，不复用。
 */
final class GlStateSnapshot {
    int currentProgram;
    int activeTexture;
    int drawBuffer;
    final int[] viewport = new int[4];
    int matrixMode;
    /** 每纹理单元 GL_TEXTURE_BINDING_2D（槽位序 = GL_TEXTURE0+i）。 */
    final int[] texture2dBinding = new int[SimulatedGlState.MAX_TEXTURE_UNITS];
    /** 三个跟踪矩阵栈的栈顶（列主序 16 值）。 */
    final float[] modelviewTop = new float[16];
    final float[] projectionTop = new float[16];
    final float[] textureMatrixTop = new float[16];
    /** 五个跟踪 enable 能力（槽位序同 {@link SimulatedGlState#TRACKED_CAPS}）。 */
    final boolean[] capEnabled = new boolean[SimulatedGlState.TRACKED_CAPS.length];
    int blendEquation;
    int alphaFunc;
    float alphaRef;
    int arrayBufferBinding;
    int elementArrayBufferBinding;
    /** GL_FRAMEBUFFER_BINDING（EXT/GL30/ARB 同值 0x8CA6），供 RecordingContext 跟踪复位。 */
    int framebufferBinding;

    /**
     * 在渲染线程上读回全部跟踪状态。采样自身的辅助切换（glActiveTexture/glMatrixMode）
     * 在读完后恢复为采样到的原值，净效果不改变任何 GL 状态。
     */
    static GlStateSnapshot capture() {
        final GlStateSnapshot s = new GlStateSnapshot();
        s.currentProgram = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM);
        s.activeTexture = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE);
        s.drawBuffer = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_DRAW_BUFFER);
        // LWJGL2 BufferChecks 对 glGetInteger 缓冲变体无条件要求 remaining ≥ 16
        // （理论最大返回 16 值，与 pname 无关）——VIEWPORT 实际只写前 4 个
        final IntBuffer intBuf = ByteBuffer.allocateDirect(16 * 4)
                .order(ByteOrder.nativeOrder()).asIntBuffer();
        org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_VIEWPORT, intBuf);
        intBuf.get(s.viewport);
        s.matrixMode = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_MATRIX_MODE);
        // 逐单元纹理绑定：glActiveTexture 切换本身改状态，读完恢复原单元
        for (int unit = 0; unit < SimulatedGlState.MAX_TEXTURE_UNITS; unit++) {
            org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0 + unit);
            s.texture2dBinding[unit] =
                    org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
        }
        org.lwjgl.opengl.GL13.glActiveTexture(s.activeTexture);
        // 矩阵栈顶（栈深不可经 getter 获得，采入侧深度归 1）
        readMatrixTop(org.lwjgl.opengl.GL11.GL_MODELVIEW, org.lwjgl.opengl.GL11.GL_MODELVIEW_MATRIX,
                s.modelviewTop);
        readMatrixTop(org.lwjgl.opengl.GL11.GL_PROJECTION, org.lwjgl.opengl.GL11.GL_PROJECTION_MATRIX,
                s.projectionTop);
        readMatrixTop(org.lwjgl.opengl.GL11.GL_TEXTURE, org.lwjgl.opengl.GL11.GL_TEXTURE_MATRIX,
                s.textureMatrixTop);
        org.lwjgl.opengl.GL11.glMatrixMode(s.matrixMode);
        for (int slot = 0; slot < SimulatedGlState.TRACKED_CAPS.length; slot++) {
            s.capEnabled[slot] = org.lwjgl.opengl.GL11.glIsEnabled(SimulatedGlState.TRACKED_CAPS[slot]);
        }
        s.blendEquation = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_EQUATION);
        s.alphaFunc = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_ALPHA_TEST_FUNC);
        s.alphaRef = org.lwjgl.opengl.GL11.glGetFloat(org.lwjgl.opengl.GL11.GL_ALPHA_TEST_REF);
        s.arrayBufferBinding = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER_BINDING);
        s.elementArrayBufferBinding =
                org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        s.framebufferBinding = org.lwjgl.opengl.GL11.glGetInteger(
                org.lwjgl.opengl.EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT);
        return s;
    }

    private static void readMatrixTop(final int mode, final int pname, final float[] out) {
        org.lwjgl.opengl.GL11.glMatrixMode(mode);
        final FloatBuffer buf = ByteBuffer.allocateDirect(16 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        org.lwjgl.opengl.GL11.glGetFloat(pname, buf);
        buf.get(out);
    }
}
