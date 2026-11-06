package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

public final class GL33 extends GL33Gen {
    private GL33() {
    }

    public static void install(RenderQueue renderQueue) {
        BridgeSupport.install(renderQueue);
    }

    static void uninstall() {
        BridgeSupport.uninstall();
    }


    public static void glVertexAttribDivisor(int index, int divisor) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL33.glVertexAttribDivisor(index, divisor));
    }


    /** 名称 buffer 录制时刻快照（LWJGL 要求 NUL 结尾 ASCII，原样保留字节）。 */
    public static void glBindFragDataLocationIndexed(int program, int colorNumber, int index, ByteBuffer name) {
        BridgeSupport.enqueueSnapshot(name, snapshot ->
                org.lwjgl.opengl.GL33.glBindFragDataLocationIndexed(program, colorNumber, index, snapshot));
    }

    public static void glBindFragDataLocationIndexed(int program, int colorNumber, int index, CharSequence name) {
        String snapshot = name.toString();
        BridgeSupport.enqueue(() ->
                org.lwjgl.opengl.GL33.glBindFragDataLocationIndexed(program, colorNumber, index, snapshot));
    }

    public static int glGetFragDataIndex(int program, ByteBuffer name) {
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.GL33.glGetFragDataIndex(program, name));
    }

    public static int glGetFragDataIndex(int program, CharSequence name) {
        String snapshot = name.toString();
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.GL33.glGetFragDataIndex(program, snapshot));
    }


    public static void glGenSamplers(IntBuffer samplers) {
        BridgeSupport.blockingWaitResource(() -> org.lwjgl.opengl.GL33.glGenSamplers(samplers));
    }

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

    public static void glGetSamplerParameter(int sampler, int pname, IntBuffer params) {
        // LWJGL2 对本族固定要求 remaining ≥ 4（BufferChecks），小缓冲经填充辅助
        // 暂存执行后拷回（见 GetBufferFill）；glGetQueryObject 族下限为 1，无需填充
        BridgeSupport.blockingWaitResource(() -> GetBufferFill.fillInts(params, 4,
                buf -> org.lwjgl.opengl.GL33.glGetSamplerParameter(sampler, pname, buf)));
    }

    public static int glGetSamplerParameteri(int sampler, int pname) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL33.glGetSamplerParameteri(sampler, pname));
    }

    public static void glGetSamplerParameter(int sampler, int pname, FloatBuffer params) {
        BridgeSupport.blockingWaitResource(() -> GetBufferFill.fillFloats(params, 4,
                buf -> org.lwjgl.opengl.GL33.glGetSamplerParameter(sampler, pname, buf)));
    }

    public static float glGetSamplerParameterf(int sampler, int pname) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL33.glGetSamplerParameterf(sampler, pname));
    }

    public static void glGetSamplerParameterI(int sampler, int pname, IntBuffer params) {
        BridgeSupport.blockingWaitResource(() -> GetBufferFill.fillInts(params, 4,
                buf -> org.lwjgl.opengl.GL33.glGetSamplerParameterI(sampler, pname, buf)));
    }

    public static int glGetSamplerParameterIi(int sampler, int pname) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL33.glGetSamplerParameterIi(sampler, pname));
    }

    public static void glGetSamplerParameterIu(int sampler, int pname, IntBuffer params) {
        BridgeSupport.blockingWaitResource(() -> GetBufferFill.fillInts(params, 4,
                buf -> org.lwjgl.opengl.GL33.glGetSamplerParameterIu(sampler, pname, buf)));
    }

    public static int glGetSamplerParameterIui(int sampler, int pname) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL33.glGetSamplerParameterIui(sampler, pname));
    }


    public static void glQueryCounter(int id, int target) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL33.glQueryCounter(id, target));
    }

    public static void glGetQueryObject(int id, int pname, LongBuffer params) {
        BridgeSupport.blockingWaitResource(() -> org.lwjgl.opengl.GL33.glGetQueryObject(id, pname, params));
    }

    public static long glGetQueryObject(int id, int pname) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL33.glGetQueryObject(id, pname));
    }

    public static long glGetQueryObjecti64(int id, int pname) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL33.glGetQueryObjecti64(id, pname));
    }

    public static void glGetQueryObjectu(int id, int pname, LongBuffer params) {
        BridgeSupport.blockingWaitResource(() -> org.lwjgl.opengl.GL33.glGetQueryObjectu(id, pname, params));
    }

    public static long glGetQueryObjectu(int id, int pname) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL33.glGetQueryObjectu(id, pname));
    }

    public static long glGetQueryObjectui64(int id, int pname) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL33.glGetQueryObjectui64(id, pname));
    }


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
