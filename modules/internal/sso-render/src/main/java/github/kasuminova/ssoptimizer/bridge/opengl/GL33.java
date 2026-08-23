package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

/**
 * org.lwjgl.opengl.GL33 的 bridge 镜像（sampler 对象 / 实例化除数 / frag data
 * 位置 / 时间戳查询 / packed 顶点族）。
 * <p>
 * 动机：Particle Engine（Ship Mastery System 的粒子引擎依赖）的
 * {@code ParticleAllocator} 用 {@code glVertexAttribDivisor} 做实例化粒子渲染，
 * GL33 曾整体不在镜像表内，调用直奔真实 GL 即
 * {@code No OpenGL context found in the current thread}。本类一次盖全 LWJGL2
 * GL33 的全部公开方法面，避免后续模组命中其余入口再走
 * 「未镜像 WARN → 真实 GL → 崩溃」的老路。
 * <p>
 * 语义同 {@link GL11}：状态命令按提交序入队；buffer 参数在录制时刻快照
 * （防调用方随后改写）；资源分配/查询走阻塞通道（调用方阻塞期间其 buffer
 * 不被触碰，渲染线程直接写入）。
 */
public final class GL33 {
    private GL33() {
    }

    /**
     * 安装命令消费者，语义同 {@link GL11#install(RenderQueue)}。
     *
     * @param renderQueue 渲染队列实例
     */
    public static void install(RenderQueue renderQueue) {
        BridgeSupport.install(renderQueue);
    }

    /** 测试用：卸载已安装的队列，避免用例间静态状态串扰。 */
    static void uninstall() {
        BridgeSupport.uninstall();
    }

    // ------------------------------------------------------------------
    // 实例化绘制
    // ------------------------------------------------------------------

    /** 实例化属性除数（Particle Engine 的粒子实例化渲染入口）。 */
    public static void glVertexAttribDivisor(int index, int divisor) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL33.glVertexAttribDivisor(index, divisor));
    }

    // ------------------------------------------------------------------
    // frag data 位置（双源混合）
    // ------------------------------------------------------------------

    /** 名称 buffer 录制时刻快照（LWJGL 要求 NUL 结尾 ASCII，原样保留字节）。 */
    public static void glBindFragDataLocationIndexed(int program, int colorNumber, int index, ByteBuffer name) {
        BridgeSupport.enqueueSnapshot(name, snapshot ->
                org.lwjgl.opengl.GL33.glBindFragDataLocationIndexed(program, colorNumber, index, snapshot));
    }

    /** 名称录制时刻固化为 String，防调用方随后改写 CharSequence。 */
    public static void glBindFragDataLocationIndexed(int program, int colorNumber, int index, CharSequence name) {
        String snapshot = name.toString();
        BridgeSupport.enqueue(() ->
                org.lwjgl.opengl.GL33.glBindFragDataLocationIndexed(program, colorNumber, index, snapshot));
    }

    /** 名称查询：阻塞通道取回；调用方阻塞期间 buffer 不被触碰。 */
    public static int glGetFragDataIndex(int program, ByteBuffer name) {
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.GL33.glGetFragDataIndex(program, name));
    }

    /** 名称查询：阻塞通道取回。名称在录制时刻定稿。 */
    public static int glGetFragDataIndex(int program, CharSequence name) {
        String snapshot = name.toString();
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.GL33.glGetFragDataIndex(program, snapshot));
    }

    // ------------------------------------------------------------------
    // sampler 对象
    // ------------------------------------------------------------------

    /** 渲染线程直接写入调用方 buffer；调用方阻塞期间 buffer 不被触碰。 */
    public static void glGenSamplers(IntBuffer samplers) {
        BridgeSupport.blockingWaitResource(() -> org.lwjgl.opengl.GL33.glGenSamplers(samplers));
    }

    /** 资源分配：阻塞通道取回真实 sampler id。 */
    public static int glGenSamplers() {
        return BridgeSupport.blockingGetResource(org.lwjgl.opengl.GL33::glGenSamplers);
    }

    public static void glDeleteSamplers(IntBuffer samplers) {
        BridgeSupport.enqueueSnapshot(samplers, snapshot ->
                org.lwjgl.opengl.GL33.glDeleteSamplers(snapshot.asIntBuffer()));
    }

    public static void glDeleteSamplers(int sampler) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL33.glDeleteSamplers(sampler));
    }

    /** 名称判定：阻塞通道取回。 */
    public static boolean glIsSampler(int sampler) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL33.glIsSampler(sampler));
    }

    public static void glBindSampler(int unit, int sampler) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL33.glBindSampler(unit, sampler));
    }

    public static void glSamplerParameteri(int sampler, int pname, int param) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL33.glSamplerParameteri(sampler, pname, param));
    }

    public static void glSamplerParameterf(int sampler, int pname, float param) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL33.glSamplerParameterf(sampler, pname, param));
    }

    public static void glSamplerParameter(int sampler, int pname, IntBuffer params) {
        BridgeSupport.enqueueSnapshot(params, snapshot ->
                org.lwjgl.opengl.GL33.glSamplerParameter(sampler, pname, snapshot.asIntBuffer()));
    }

    public static void glSamplerParameter(int sampler, int pname, FloatBuffer params) {
        BridgeSupport.enqueueSnapshot(params, snapshot ->
                org.lwjgl.opengl.GL33.glSamplerParameter(sampler, pname, snapshot.asFloatBuffer()));
    }

    public static void glSamplerParameterI(int sampler, int pname, IntBuffer params) {
        BridgeSupport.enqueueSnapshot(params, snapshot ->
                org.lwjgl.opengl.GL33.glSamplerParameterI(sampler, pname, snapshot.asIntBuffer()));
    }

    public static void glSamplerParameterIu(int sampler, int pname, IntBuffer params) {
        BridgeSupport.enqueueSnapshot(params, snapshot ->
                org.lwjgl.opengl.GL33.glSamplerParameterIu(sampler, pname, snapshot.asIntBuffer()));
    }

    /** 查询回写：渲染线程直接写入调用方 buffer；调用方阻塞期间不被触碰。 */
    public static void glGetSamplerParameter(int sampler, int pname, IntBuffer params) {
        // LWJGL2 对本族固定要求 remaining ≥ 4（BufferChecks），小缓冲经填充辅助
        // 暂存执行后拷回（见 GetBufferFill）；glGetQueryObject 族下限为 1，无需填充
        BridgeSupport.blockingWaitResource(() -> GetBufferFill.fillInts(params, 4,
                buf -> org.lwjgl.opengl.GL33.glGetSamplerParameter(sampler, pname, buf)));
    }

    public static int glGetSamplerParameteri(int sampler, int pname) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL33.glGetSamplerParameteri(sampler, pname));
    }

    /** 查询回写：渲染线程直接写入调用方 buffer；调用方阻塞期间不被触碰。 */
    public static void glGetSamplerParameter(int sampler, int pname, FloatBuffer params) {
        // 固定下限 4 检查同 IntBuffer 变体，见 GetBufferFill
        BridgeSupport.blockingWaitResource(() -> GetBufferFill.fillFloats(params, 4,
                buf -> org.lwjgl.opengl.GL33.glGetSamplerParameter(sampler, pname, buf)));
    }

    public static float glGetSamplerParameterf(int sampler, int pname) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL33.glGetSamplerParameterf(sampler, pname));
    }

    /** 查询回写：渲染线程直接写入调用方 buffer；调用方阻塞期间不被触碰。 */
    public static void glGetSamplerParameterI(int sampler, int pname, IntBuffer params) {
        // 固定下限 4 检查同上，见 GetBufferFill
        BridgeSupport.blockingWaitResource(() -> GetBufferFill.fillInts(params, 4,
                buf -> org.lwjgl.opengl.GL33.glGetSamplerParameterI(sampler, pname, buf)));
    }

    public static int glGetSamplerParameterIi(int sampler, int pname) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL33.glGetSamplerParameterIi(sampler, pname));
    }

    /** 查询回写：渲染线程直接写入调用方 buffer；调用方阻塞期间不被触碰。 */
    public static void glGetSamplerParameterIu(int sampler, int pname, IntBuffer params) {
        // 固定下限 4 检查同上，见 GetBufferFill
        BridgeSupport.blockingWaitResource(() -> GetBufferFill.fillInts(params, 4,
                buf -> org.lwjgl.opengl.GL33.glGetSamplerParameterIu(sampler, pname, buf)));
    }

    public static int glGetSamplerParameterIui(int sampler, int pname) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL33.glGetSamplerParameterIui(sampler, pname));
    }

    // ------------------------------------------------------------------
    // 查询对象（时间戳/计数器）
    // ------------------------------------------------------------------

    public static void glQueryCounter(int id, int target) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL33.glQueryCounter(id, target));
    }

    /** 查询回写：渲染线程直接写入调用方 buffer；调用方阻塞期间不被触碰。 */
    public static void glGetQueryObject(int id, int pname, LongBuffer params) {
        BridgeSupport.blockingWaitResource(() -> org.lwjgl.opengl.GL33.glGetQueryObject(id, pname, params));
    }

    public static long glGetQueryObject(int id, int pname) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL33.glGetQueryObject(id, pname));
    }

    public static long glGetQueryObjecti64(int id, int pname) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL33.glGetQueryObjecti64(id, pname));
    }

    /** 查询回写：渲染线程直接写入调用方 buffer；调用方阻塞期间不被触碰。 */
    public static void glGetQueryObjectu(int id, int pname, LongBuffer params) {
        BridgeSupport.blockingWaitResource(() -> org.lwjgl.opengl.GL33.glGetQueryObjectu(id, pname, params));
    }

    public static long glGetQueryObjectu(int id, int pname) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL33.glGetQueryObjectu(id, pname));
    }

    public static long glGetQueryObjectui64(int id, int pname) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL33.glGetQueryObjectui64(id, pname));
    }

    // ------------------------------------------------------------------
    // packed 顶点族（INT_2_10_10_10_REV 等压缩格式；值语义按提交序入队，
    // buffer 指针形态录制时刻快照）
    // ------------------------------------------------------------------

    public static void glVertexP2ui(int type, int coords) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL33.glVertexP2ui(type, coords));
    }

    public static void glVertexP3ui(int type, int coords) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL33.glVertexP3ui(type, coords));
    }

    public static void glVertexP4ui(int type, int coords) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL33.glVertexP4ui(type, coords));
    }

    public static void glVertexP2u(int type, IntBuffer coords) {
        BridgeSupport.enqueueSnapshot(coords, snapshot ->
                org.lwjgl.opengl.GL33.glVertexP2u(type, snapshot.asIntBuffer()));
    }

    public static void glVertexP3u(int type, IntBuffer coords) {
        BridgeSupport.enqueueSnapshot(coords, snapshot ->
                org.lwjgl.opengl.GL33.glVertexP3u(type, snapshot.asIntBuffer()));
    }

    public static void glVertexP4u(int type, IntBuffer coords) {
        BridgeSupport.enqueueSnapshot(coords, snapshot ->
                org.lwjgl.opengl.GL33.glVertexP4u(type, snapshot.asIntBuffer()));
    }

    public static void glTexCoordP1ui(int type, int coords) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL33.glTexCoordP1ui(type, coords));
    }

    public static void glTexCoordP2ui(int type, int coords) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL33.glTexCoordP2ui(type, coords));
    }

    public static void glTexCoordP3ui(int type, int coords) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL33.glTexCoordP3ui(type, coords));
    }

    public static void glTexCoordP4ui(int type, int coords) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL33.glTexCoordP4ui(type, coords));
    }

    public static void glTexCoordP1u(int type, IntBuffer coords) {
        BridgeSupport.enqueueSnapshot(coords, snapshot ->
                org.lwjgl.opengl.GL33.glTexCoordP1u(type, snapshot.asIntBuffer()));
    }

    public static void glTexCoordP2u(int type, IntBuffer coords) {
        BridgeSupport.enqueueSnapshot(coords, snapshot ->
                org.lwjgl.opengl.GL33.glTexCoordP2u(type, snapshot.asIntBuffer()));
    }

    public static void glTexCoordP3u(int type, IntBuffer coords) {
        BridgeSupport.enqueueSnapshot(coords, snapshot ->
                org.lwjgl.opengl.GL33.glTexCoordP3u(type, snapshot.asIntBuffer()));
    }

    public static void glTexCoordP4u(int type, IntBuffer coords) {
        BridgeSupport.enqueueSnapshot(coords, snapshot ->
                org.lwjgl.opengl.GL33.glTexCoordP4u(type, snapshot.asIntBuffer()));
    }

    public static void glMultiTexCoordP1ui(int texture, int type, int coords) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL33.glMultiTexCoordP1ui(texture, type, coords));
    }

    public static void glMultiTexCoordP2ui(int texture, int type, int coords) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL33.glMultiTexCoordP2ui(texture, type, coords));
    }

    public static void glMultiTexCoordP3ui(int texture, int type, int coords) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL33.glMultiTexCoordP3ui(texture, type, coords));
    }

    public static void glMultiTexCoordP4ui(int texture, int type, int coords) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL33.glMultiTexCoordP4ui(texture, type, coords));
    }

    public static void glMultiTexCoordP1u(int texture, int type, IntBuffer coords) {
        BridgeSupport.enqueueSnapshot(coords, snapshot ->
                org.lwjgl.opengl.GL33.glMultiTexCoordP1u(texture, type, snapshot.asIntBuffer()));
    }

    public static void glMultiTexCoordP2u(int texture, int type, IntBuffer coords) {
        BridgeSupport.enqueueSnapshot(coords, snapshot ->
                org.lwjgl.opengl.GL33.glMultiTexCoordP2u(texture, type, snapshot.asIntBuffer()));
    }

    public static void glMultiTexCoordP3u(int texture, int type, IntBuffer coords) {
        BridgeSupport.enqueueSnapshot(coords, snapshot ->
                org.lwjgl.opengl.GL33.glMultiTexCoordP3u(texture, type, snapshot.asIntBuffer()));
    }

    public static void glMultiTexCoordP4u(int texture, int type, IntBuffer coords) {
        BridgeSupport.enqueueSnapshot(coords, snapshot ->
                org.lwjgl.opengl.GL33.glMultiTexCoordP4u(texture, type, snapshot.asIntBuffer()));
    }

    public static void glNormalP3ui(int type, int coords) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL33.glNormalP3ui(type, coords));
    }

    public static void glNormalP3u(int type, IntBuffer coords) {
        BridgeSupport.enqueueSnapshot(coords, snapshot ->
                org.lwjgl.opengl.GL33.glNormalP3u(type, snapshot.asIntBuffer()));
    }

    public static void glColorP3ui(int type, int color) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL33.glColorP3ui(type, color));
    }

    public static void glColorP4ui(int type, int color) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL33.glColorP4ui(type, color));
    }

    public static void glColorP3u(int type, IntBuffer color) {
        BridgeSupport.enqueueSnapshot(color, snapshot ->
                org.lwjgl.opengl.GL33.glColorP3u(type, snapshot.asIntBuffer()));
    }

    public static void glColorP4u(int type, IntBuffer color) {
        BridgeSupport.enqueueSnapshot(color, snapshot ->
                org.lwjgl.opengl.GL33.glColorP4u(type, snapshot.asIntBuffer()));
    }

    public static void glSecondaryColorP3ui(int type, int color) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL33.glSecondaryColorP3ui(type, color));
    }

    public static void glSecondaryColorP3u(int type, IntBuffer color) {
        BridgeSupport.enqueueSnapshot(color, snapshot ->
                org.lwjgl.opengl.GL33.glSecondaryColorP3u(type, snapshot.asIntBuffer()));
    }

    public static void glVertexAttribP1ui(int index, int type, boolean normalized, int value) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL33.glVertexAttribP1ui(index, type, normalized, value));
    }

    public static void glVertexAttribP2ui(int index, int type, boolean normalized, int value) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL33.glVertexAttribP2ui(index, type, normalized, value));
    }

    public static void glVertexAttribP3ui(int index, int type, boolean normalized, int value) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL33.glVertexAttribP3ui(index, type, normalized, value));
    }

    public static void glVertexAttribP4ui(int index, int type, boolean normalized, int value) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL33.glVertexAttribP4ui(index, type, normalized, value));
    }

    public static void glVertexAttribP1u(int index, int type, boolean normalized, IntBuffer value) {
        BridgeSupport.enqueueSnapshot(value, snapshot ->
                org.lwjgl.opengl.GL33.glVertexAttribP1u(index, type, normalized, snapshot.asIntBuffer()));
    }

    public static void glVertexAttribP2u(int index, int type, boolean normalized, IntBuffer value) {
        BridgeSupport.enqueueSnapshot(value, snapshot ->
                org.lwjgl.opengl.GL33.glVertexAttribP2u(index, type, normalized, snapshot.asIntBuffer()));
    }

    public static void glVertexAttribP3u(int index, int type, boolean normalized, IntBuffer value) {
        BridgeSupport.enqueueSnapshot(value, snapshot ->
                org.lwjgl.opengl.GL33.glVertexAttribP3u(index, type, normalized, snapshot.asIntBuffer()));
    }

    public static void glVertexAttribP4u(int index, int type, boolean normalized, IntBuffer value) {
        BridgeSupport.enqueueSnapshot(value, snapshot ->
                org.lwjgl.opengl.GL33.glVertexAttribP4u(index, type, normalized, snapshot.asIntBuffer()));
    }
}
