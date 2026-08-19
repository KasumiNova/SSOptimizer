package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.AuxRunFence;
import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * {@link AuxRunFence} 的真实实现：在渲染线程用真实 GL 调用快照/恢复游戏侧状态。
 * <p>
 * 快照分两路，互补覆盖：
 * <ul>
 *   <li>{@code glPushAttrib(GL_ALL_ATTRIB_BITS)} +
 *   {@code glPushClientAttrib(GL_CLIENT_ALL_ATTRIB_BITS)}：属性栈覆盖的服务端
 *   固定管线状态（enable 位、混合、视口/裁剪、各纹理单元的纹理绑定等）与客户端
 *   顶点数组状态（数组 enable 位、pointer 选择）。属性栈深度规范保证 ≥16，
 *   单层配对无溢出风险；</li>
 *   <li>显式快照属性栈不覆盖的部分：PROJECTION/MODELVIEW/TEXTURE 矩阵内容
 *   （<b>不用 glPushMatrix</b>——PROJECTION/TEXTURE 栈规范只保证 2 层，游戏自身
 *   常压栈，溢出风险不可控，改为 glGetFloat 存静态复用 DirectBuffer）、矩阵模式、
 *   当前着色器程序、FBO 绑定、活动纹理单元（服务端与客户端各一份）、
 *   ARRAY/ELEMENT_ARRAY 缓冲绑定。</li>
 * </ul>
 * TEXTURE 矩阵必须显式快照：游戏文本/2D 渲染从不触碰 TEXTURE 矩阵（恒假定
 * 单位阵），aux 线程（如 BoxUtil 拖尾的纹理滚动）一次改写不恢复即造成
 * 「随机 onset、持续整局」的 UV 采样腐坏。按单元 0/1 快照（游戏固定管线
 * 实际使用的单元面）。
 * <p>
 * 快照缓冲为静态复用的 DirectBuffer：enter/exit 只在渲染线程被调用且严格单层
 * 配对（见接口契约），无并发与重入，稳态零分配。
 * <p>
 * enter 末尾会把为读取 TEXTURE 矩阵而切换的活动纹理单元立即还原，aux 游程内
 * 见到的状态与进入时完全一致。exit 恢复顺序与快照逆序对应：先 TEXTURE 矩阵
 * （需先切矩阵模式），再缓冲/FBO/着色器，然后 PROJECTION/MODELVIEW 矩阵与
 * 矩阵模式，最后双属性栈 pop 收尾（纹理绑定等交叠项以属性栈为准）。
 */
public final class GlStateFence implements AuxRunFence {
    /** PROJECTION 矩阵快照（16 float，nativeOrder 是 glGetFloat 的硬契约）。 */
    private static final FloatBuffer PROJECTION_SNAPSHOT =
            ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    /** MODELVIEW 矩阵快照。 */
    private static final FloatBuffer MODELVIEW_SNAPSHOT =
            ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    /** TEXTURE 矩阵快照（纹理单元 0）。 */
    private static final FloatBuffer TEXTURE0_MATRIX_SNAPSHOT =
            ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    /** TEXTURE 矩阵快照（纹理单元 1）。 */
    private static final FloatBuffer TEXTURE1_MATRIX_SNAPSHOT =
            ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

    /** GL_CLIENT_ALL_ATTRIB_BITS（LWJGL2 未暴露该常量，值为规范固定的 0x0000FFFF）。 */
    private static final int GL_CLIENT_ALL_ATTRIB_BITS = 0x0000FFFF;

    /** 以下为 enter 时填充、exit 时消费的显式快照标量（单线程单层契约，普通字段即可）。 */
    private int matrixMode;
    private int currentProgram;
    private int framebufferBinding;
    private int activeTexture;
    private int clientActiveTexture;
    private int arrayBufferBinding;
    private int elementArrayBufferBinding;

    @Override
    public void enter() {
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushClientAttrib(GL_CLIENT_ALL_ATTRIB_BITS);

        activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        clientActiveTexture = GL11.glGetInteger(GL13.GL_CLIENT_ACTIVE_TEXTURE);

        TEXTURE1_MATRIX_SNAPSHOT.clear();
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glGetFloat(GL11.GL_TEXTURE_MATRIX, TEXTURE1_MATRIX_SNAPSHOT);
        TEXTURE0_MATRIX_SNAPSHOT.clear();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glGetFloat(GL11.GL_TEXTURE_MATRIX, TEXTURE0_MATRIX_SNAPSHOT);
        // 读取造成的活动单元切换立即还原：aux 游程内见到的状态与进入时一致
        GL13.glActiveTexture(activeTexture);

        PROJECTION_SNAPSHOT.clear();
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, PROJECTION_SNAPSHOT);
        MODELVIEW_SNAPSHOT.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MODELVIEW_SNAPSHOT);

        matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        currentProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        framebufferBinding = GL11.glGetInteger(EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT);
        arrayBufferBinding = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        elementArrayBufferBinding = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
    }

    @Override
    public void exit() {
        // TEXTURE 矩阵按单元恢复（glLoadMatrix 作用于当前矩阵模式的栈）
        TEXTURE1_MATRIX_SNAPSHOT.rewind();
        TEXTURE0_MATRIX_SNAPSHOT.rewind();
        GL11.glMatrixMode(GL11.GL_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glLoadMatrix(TEXTURE1_MATRIX_SNAPSHOT);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glLoadMatrix(TEXTURE0_MATRIX_SNAPSHOT);
        GL13.glActiveTexture(activeTexture);
        GL13.glClientActiveTexture(clientActiveTexture);

        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, elementArrayBufferBinding);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBufferBinding);
        EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, framebufferBinding);
        GL20.glUseProgram(currentProgram);

        PROJECTION_SNAPSHOT.rewind();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadMatrix(PROJECTION_SNAPSHOT);
        MODELVIEW_SNAPSHOT.rewind();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadMatrix(MODELVIEW_SNAPSHOT);
        GL11.glMatrixMode(matrixMode);

        GL11.glPopClientAttrib();
        GL11.glPopAttrib();
    }
}
