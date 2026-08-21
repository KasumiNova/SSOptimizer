package github.kasuminova.ssoptimizer.bridge.opengl;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SimulatedGlState} 簿记语义完整逻辑验证。
 * <p>
 * 直接调用记录点与 getter 仿真：矩阵族（单位阵/平移/缩放/旋转/正交/push/pop/
 * load/mult 双精度变体）、标量分组（program/activeTexture/drawBuffer/viewport/
 * matrixMode/纹理绑定）、attrib 快照恢复与下溢空操作语义、adopt 采入再同步、
 * 上下文重建复位。
 */
class SimulatedGlStateTest {
    private static final float EPS = 1e-6f;

    private static FloatBuffer directFloatBuffer(final int capacity) {
        return ByteBuffer.allocateDirect(capacity * 4).asFloatBuffer();
    }

    private static float[] matrixOf(final SimulatedGlState state, final int pname) {
        final FloatBuffer out = directFloatBuffer(16);
        assertTrue(state.getFloat(pname, out), "矩阵 getter 仿真必须命中: " + pname);
        assertEquals(16, out.position(), "必须恰好写出 16 个值");
        final float[] values = new float[16];
        out.flip();
        out.get(values);
        return values;
    }

    private static void assertIdentity(final float[] m) {
        for (int i = 0; i < 16; i++) {
            final float expected = (i % 5) == 0 ? 1.0f : 0.0f;
            assertEquals(expected, m[i], EPS, "单位阵元素[" + i + "]");
        }
    }

    @Test
    void initialMatricesAreIdentity() {
        final SimulatedGlState state = new SimulatedGlState();
        assertIdentity(matrixOf(state, GL11.GL_MODELVIEW_MATRIX));
        assertIdentity(matrixOf(state, GL11.GL_PROJECTION_MATRIX));
        assertIdentity(matrixOf(state, GL11.GL_TEXTURE_MATRIX));
    }

    @Test
    void translateThenScaleComposesAsRightMultiply() {
        final SimulatedGlState state = new SimulatedGlState();
        state.onTranslate(1, 2, 3);
        state.onScale(2, 2, 2);
        // M = I * T * S：列 0/1/2 被缩放，末列为平移量
        final float[] m = matrixOf(state, GL11.GL_MODELVIEW_MATRIX);
        assertEquals(2.0f, m[0], EPS);
        assertEquals(2.0f, m[5], EPS);
        assertEquals(2.0f, m[10], EPS);
        assertEquals(1.0f, m[12], EPS);
        assertEquals(2.0f, m[13], EPS);
        assertEquals(3.0f, m[14], EPS);
        assertEquals(1.0f, m[15], EPS);
    }

    @Test
    void rotateNinetyDegreesAroundZ() {
        final SimulatedGlState state = new SimulatedGlState();
        state.onRotate(90, 0, 0, 1);
        final float[] m = matrixOf(state, GL11.GL_MODELVIEW_MATRIX);
        // 列主序：col0=(cos,sin,0,0)=(0,1,0,0)，col1=(-sin,cos,0,0)=(-1,0,0,0)
        assertEquals(0.0f, m[0], EPS);
        assertEquals(1.0f, m[1], EPS);
        assertEquals(-1.0f, m[4], EPS);
        assertEquals(0.0f, m[5], EPS);
    }

    @Test
    void orthoAppliesToSelectedMatrixModeOnly() {
        final SimulatedGlState state = new SimulatedGlState();
        state.onMatrixMode(GL11.GL_PROJECTION);
        state.onOrtho(0, 100, 0, 50, -1, 1);
        final float[] p = matrixOf(state, GL11.GL_PROJECTION_MATRIX);
        assertEquals(0.02f, p[0], EPS);
        assertEquals(0.04f, p[5], EPS);
        assertEquals(-1.0f, p[10], EPS);
        assertEquals(-1.0f, p[12], EPS);
        assertEquals(-1.0f, p[13], EPS);
        assertEquals(0.0f, p[14], EPS);
        assertEquals(1.0f, p[15], EPS);
        assertIdentity(matrixOf(state, GL11.GL_MODELVIEW_MATRIX));
    }

    @Test
    void pushPopMatrixRestoresTop() {
        final SimulatedGlState state = new SimulatedGlState();
        state.onTranslate(5, 6, 7);
        state.onPushMatrix();
        state.onLoadIdentity();
        assertIdentity(matrixOf(state, GL11.GL_MODELVIEW_MATRIX));
        state.onPopMatrix();
        final float[] m = matrixOf(state, GL11.GL_MODELVIEW_MATRIX);
        assertEquals(5.0f, m[12], EPS);
        assertEquals(6.0f, m[13], EPS);
        assertEquals(7.0f, m[14], EPS);
    }

    @Test
    void loadAndMultMatrixFromDoubleBuffer() {
        final SimulatedGlState state = new SimulatedGlState();
        final DoubleBuffer load = ByteBuffer.allocateDirect(16 * 8).asDoubleBuffer();
        for (int i = 0; i < 16; i++) {
            load.put(i + 0.5);
        }
        load.flip();
        state.onLoadMatrix(load);
        final float[] m = matrixOf(state, GL11.GL_MODELVIEW_MATRIX);
        for (int i = 0; i < 16; i++) {
            assertEquals((float) (i + 0.5), m[i], EPS, "load 元素[" + i + "]");
        }
        // 以单位阵右乘：M * I = M
        final DoubleBuffer identity = ByteBuffer.allocateDirect(16 * 8).asDoubleBuffer();
        for (int i = 0; i < 16; i++) {
            identity.put((i % 5) == 0 ? 1.0 : 0.0);
        }
        identity.flip();
        state.onMultMatrix(identity);
        final float[] after = matrixOf(state, GL11.GL_MODELVIEW_MATRIX);
        for (int i = 0; i < 16; i++) {
            assertEquals(m[i], after[i], EPS, "mult 单位阵后元素[" + i + "]不变");
        }
    }

    @Test
    void scalarGroupsAreTracked() {
        final SimulatedGlState state = new SimulatedGlState();
        assertEquals(0, state.getInteger(GL20.GL_CURRENT_PROGRAM));
        state.onUseProgram(42);
        assertEquals(42, state.getInteger(GL20.GL_CURRENT_PROGRAM));

        assertEquals(GL13.GL_TEXTURE0, state.getInteger(GL13.GL_ACTIVE_TEXTURE));
        state.onActiveTexture(GL13.GL_TEXTURE0 + 3);
        assertEquals(GL13.GL_TEXTURE0 + 3, state.getInteger(GL13.GL_ACTIVE_TEXTURE));

        assertEquals(GL11.GL_BACK, state.getInteger(GL11.GL_DRAW_BUFFER));
        state.onDrawBuffer(GL11.GL_FRONT);
        assertEquals(GL11.GL_FRONT, state.getInteger(GL11.GL_DRAW_BUFFER));

        assertEquals(GL11.GL_MODELVIEW, state.getInteger(GL11.GL_MATRIX_MODE));
        state.onMatrixMode(GL11.GL_PROJECTION);
        assertEquals(GL11.GL_PROJECTION, state.getInteger(GL11.GL_MATRIX_MODE));
    }

    @Test
    void viewportIsInvalidUntilFirstRecord() {
        final SimulatedGlState state = new SimulatedGlState();
        assertNull(state.getInteger(GL11.GL_VIEWPORT), "首个 glViewport 前必须失效");
        assertFalse(state.getInteger(GL11.GL_VIEWPORT, IntBuffer.allocate(4)));
        state.onViewport(1, 2, 300, 200);
        assertEquals(1, state.getInteger(GL11.GL_VIEWPORT));
        final IntBuffer out = IntBuffer.allocate(4);
        assertTrue(state.getInteger(GL11.GL_VIEWPORT, out));
        out.flip();
        assertEquals(1, out.get());
        assertEquals(2, out.get());
        assertEquals(300, out.get());
        assertEquals(200, out.get());
    }

    @Test
    void textureBindingsArePerUnitAndClearedOnDelete() {
        final SimulatedGlState state = new SimulatedGlState();
        state.onBindTexture(GL11.GL_TEXTURE_2D, 11);
        assertEquals(11, state.getInteger(GL11.GL_TEXTURE_BINDING_2D));
        state.onActiveTexture(GL13.GL_TEXTURE0 + 1);
        assertEquals(0, state.getInteger(GL11.GL_TEXTURE_BINDING_2D), "另一单元互不影响");
        state.onBindTexture(GL11.GL_TEXTURE_2D, 22);
        state.onActiveTexture(GL13.GL_TEXTURE0);
        assertEquals(11, state.getInteger(GL11.GL_TEXTURE_BINDING_2D));
        state.onDeleteTexture(11);
        assertEquals(0, state.getInteger(GL11.GL_TEXTURE_BINDING_2D), "删除后绑定清零");
        state.onActiveTexture(GL13.GL_TEXTURE0 + 1);
        assertEquals(22, state.getInteger(GL11.GL_TEXTURE_BINDING_2D), "其余单元不受影响");
    }

    @Test
    void popAttribRestoresSnapshotByMaskBits() {
        final SimulatedGlState state = new SimulatedGlState();
        state.onViewport(0, 0, 100, 100);
        state.onPushAttrib(GL11.GL_VIEWPORT_BIT);
        state.onViewport(5, 6, 700, 500);
        state.onPopAttrib();
        // VIEWPORT_BIT：pop 恢复 push 时的快照
        assertEquals(0, state.getInteger(GL11.GL_VIEWPORT));
        final IntBuffer out = IntBuffer.allocate(4);
        assertTrue(state.getInteger(GL11.GL_VIEWPORT, out));
        out.flip();
        assertEquals(0, out.get());
        assertEquals(0, out.get());
        assertEquals(100, out.get());
        assertEquals(100, out.get());

        state.onMatrixMode(GL11.GL_PROJECTION);
        state.onPushAttrib(GL11.GL_TRANSFORM_BIT);
        state.onMatrixMode(GL11.GL_TEXTURE);
        state.onPopAttrib();
        assertEquals(GL11.GL_PROJECTION, state.getInteger(GL11.GL_MATRIX_MODE),
                "TRANSFORM_BIT：pop 恢复 push 时的 matrixMode");

        // 未按位保存的分组不受 pop 影响
        state.onViewport(1, 1, 50, 50);
        state.onPushAttrib(GL11.GL_TRANSFORM_BIT);
        state.onViewport(2, 2, 60, 60);
        state.onPopAttrib();
        assertEquals(2, state.getInteger(GL11.GL_VIEWPORT), "TRANSFORM_BIT 不触碰 viewport");
    }

    @Test
    void popAttribWithoutPairIsNoOp() {
        // GL 规范：栈下溢仅报 GL_STACK_UNDERFLOW，状态不变；簿记同样保持不变
        final SimulatedGlState state = new SimulatedGlState();
        state.onViewport(7, 8, 100, 100);
        state.onMatrixMode(GL11.GL_PROJECTION);
        state.onPopAttrib();
        assertEquals(7, state.getInteger(GL11.GL_VIEWPORT), "下溢 pop 为空操作，viewport 不变");
        assertEquals(GL11.GL_PROJECTION, state.getInteger(GL11.GL_MATRIX_MODE),
                "下溢 pop 为空操作，matrixMode 不变");
    }

    @Test
    void drawBuffersTracksFirstBuffer() {
        final SimulatedGlState state = new SimulatedGlState();
        state.onDrawBuffer(GL11.GL_BACK);
        // glDrawBuffers([FRONT, ...]) 的单值查询语义即 DRAW_BUFFER0=FRONT
        state.onDrawBuffers(GL11.GL_FRONT);
        assertEquals(GL11.GL_FRONT, state.getInteger(GL11.GL_DRAW_BUFFER));
        state.onDrawBuffer(GL11.GL_BACK);
        assertEquals(GL11.GL_BACK, state.getInteger(GL11.GL_DRAW_BUFFER));
    }

    @Test
    void untrackedPnamesReturnNull() {
        final SimulatedGlState state = new SimulatedGlState();
        assertNull(state.getInteger(GL11.GL_BLEND));
        assertFalse(state.getInteger(GL11.GL_BLEND, IntBuffer.allocate(4)));
        assertFalse(state.getFloat(GL11.GL_BLEND, directFloatBuffer(16)));
    }

    @Test
    void contextRecreatedResetsToDefaults() {
        final SimulatedGlState state = new SimulatedGlState();
        state.onUseProgram(9);
        state.onActiveTexture(GL13.GL_TEXTURE0 + 2);
        state.onBindTexture(GL11.GL_TEXTURE_2D, 33);
        state.onViewport(1, 1, 50, 50);
        state.onMatrixMode(GL11.GL_PROJECTION);
        state.onTranslate(1, 2, 3);
        state.onPushAttrib(GL11.GL_VIEWPORT_BIT);

        state.onContextRecreated();

        assertEquals(0, state.getInteger(GL20.GL_CURRENT_PROGRAM));
        assertEquals(GL13.GL_TEXTURE0, state.getInteger(GL13.GL_ACTIVE_TEXTURE));
        assertEquals(0, state.getInteger(GL11.GL_TEXTURE_BINDING_2D));
        assertNull(state.getInteger(GL11.GL_VIEWPORT));
        assertEquals(GL11.GL_MODELVIEW, state.getInteger(GL11.GL_MATRIX_MODE));
        assertIdentity(matrixOf(state, GL11.GL_PROJECTION_MATRIX));
        // attrib 栈一并复位：再次 pop 为下溢空操作，不得抛异常
        state.onPopAttrib();
        assertNull(state.getInteger(GL11.GL_VIEWPORT));
    }

    @Test
    void colorMatrixModeOperationsAreIgnoredButModeIsTracked() {
        final SimulatedGlState state = new SimulatedGlState();
        state.onMatrixMode(GL11.GL_COLOR);
        state.onLoadIdentity();
        state.onTranslate(9, 9, 9);
        assertEquals(GL11.GL_COLOR, state.getInteger(GL11.GL_MATRIX_MODE));
        state.onMatrixMode(GL11.GL_MODELVIEW);
        assertIdentity(matrixOf(state, GL11.GL_MODELVIEW_MATRIX));
    }

    @Test
    void matrixStackOverflowKeepsTopUnchanged() {
        final SimulatedGlState state = new SimulatedGlState();
        state.onTranslate(1, 0, 0);
        for (int i = 0; i < SimulatedGlState.MAX_MATRIX_STACK + 8; i++) {
            state.onPushMatrix();
        }
        final float[] m = matrixOf(state, GL11.GL_MODELVIEW_MATRIX);
        assertEquals(1.0f, m[12], EPS, "溢出后栈顶保持（真实 GL 报 STACK_OVERFLOW 且栈不变）");
    }

    // ------------------------------------------------------------------
    // enable 位 / blendEquation / alphaFunc/ref / VBO 绑定（SpriteBatch 守卫覆盖面）
    // ------------------------------------------------------------------

    @Test
    void enableCapsDefaultDisabledAndTracked() {
        final SimulatedGlState state = new SimulatedGlState();
        assertEquals(Boolean.FALSE, state.getBoolean(GL11.GL_TEXTURE_2D));
        assertEquals(Boolean.FALSE, state.getBoolean(GL11.GL_BLEND));
        assertEquals(Boolean.FALSE, state.getBoolean(GL11.GL_ALPHA_TEST));
        assertEquals(Boolean.FALSE, state.getBoolean(GL11.GL_STENCIL_TEST));
        assertEquals(Boolean.FALSE, state.getBoolean(GL11.GL_SCISSOR_TEST));
        assertNull(state.getBoolean(GL11.GL_DEPTH_TEST), "未跟踪能力必须回退阻塞通道");

        state.onEnable(GL11.GL_BLEND);
        state.onEnable(GL11.GL_STENCIL_TEST);
        assertEquals(Boolean.TRUE, state.getBoolean(GL11.GL_BLEND));
        assertEquals(Boolean.TRUE, state.getBoolean(GL11.GL_STENCIL_TEST));
        state.onDisable(GL11.GL_BLEND);
        assertEquals(Boolean.FALSE, state.getBoolean(GL11.GL_BLEND));
        assertEquals(Boolean.TRUE, state.getBoolean(GL11.GL_STENCIL_TEST), "其余能力互不影响");
    }

    @Test
    void pushAttribEnableBitRestoresAllTrackedCaps() {
        final SimulatedGlState state = new SimulatedGlState();
        // ENABLE_BIT 双归属：五个跟踪能力的 enable 位全部入 enable 组
        state.onPushAttrib(GL11.GL_ENABLE_BIT);
        state.onEnable(GL11.GL_TEXTURE_2D);
        state.onEnable(GL11.GL_BLEND);
        state.onEnable(GL11.GL_ALPHA_TEST);
        state.onEnable(GL11.GL_STENCIL_TEST);
        state.onEnable(GL11.GL_SCISSOR_TEST);
        state.onPopAttrib();
        assertEquals(Boolean.FALSE, state.getBoolean(GL11.GL_TEXTURE_2D));
        assertEquals(Boolean.FALSE, state.getBoolean(GL11.GL_BLEND));
        assertEquals(Boolean.FALSE, state.getBoolean(GL11.GL_ALPHA_TEST));
        assertEquals(Boolean.FALSE, state.getBoolean(GL11.GL_STENCIL_TEST));
        assertEquals(Boolean.FALSE, state.getBoolean(GL11.GL_SCISSOR_TEST));
    }

    @Test
    void pushAttribFunctionalGroupsRestoreOnlyOwnCaps() {
        final SimulatedGlState state = new SimulatedGlState();
        state.onEnable(GL11.GL_TEXTURE_2D);
        state.onPushAttrib(GL11.GL_TEXTURE_BIT);
        state.onDisable(GL11.GL_TEXTURE_2D);
        state.onEnable(GL11.GL_BLEND);
        state.onPopAttrib();
        assertEquals(Boolean.TRUE, state.getBoolean(GL11.GL_TEXTURE_2D),
                "TEXTURE_BIT 恢复 TEXTURE_2D enable");
        assertEquals(Boolean.TRUE, state.getBoolean(GL11.GL_BLEND),
                "TEXTURE_BIT 不触碰 BLEND enable");

        state.onPushAttrib(GL11.GL_STENCIL_BUFFER_BIT);
        state.onEnable(GL11.GL_STENCIL_TEST);
        state.onEnable(GL11.GL_SCISSOR_TEST);
        state.onPopAttrib();
        assertEquals(Boolean.FALSE, state.getBoolean(GL11.GL_STENCIL_TEST),
                "STENCIL_BUFFER_BIT 恢复 STENCIL_TEST enable");
        assertEquals(Boolean.TRUE, state.getBoolean(GL11.GL_SCISSOR_TEST),
                "STENCIL_BUFFER_BIT 不触碰 SCISSOR_TEST enable");
    }

    @Test
    void blendEquationAndAlphaStateTrackedAndRestored() {
        final SimulatedGlState state = new SimulatedGlState();
        assertEquals(GL14.GL_FUNC_ADD, state.getInteger(GL14.GL_BLEND_EQUATION));
        assertEquals(GL11.GL_ALWAYS, state.getInteger(GL11.GL_ALPHA_TEST_FUNC));

        state.onBlendEquation(GL14.GL_FUNC_REVERSE_SUBTRACT);
        state.onAlphaFunc(GL11.GL_GEQUAL, 0.5f);
        assertEquals(GL14.GL_FUNC_REVERSE_SUBTRACT, state.getInteger(GL14.GL_BLEND_EQUATION));
        assertEquals(GL11.GL_GEQUAL, state.getInteger(GL11.GL_ALPHA_TEST_FUNC));
        final FloatBuffer ref = directFloatBuffer(16);
        assertTrue(state.getFloat(GL11.GL_ALPHA_TEST_REF, ref));
        assertEquals(0.5f, ref.get(0), EPS);

        // COLOR_BUFFER_BIT 快照恢复两个标量
        state.onPushAttrib(GL11.GL_COLOR_BUFFER_BIT);
        state.onBlendEquation(GL14.GL_FUNC_ADD);
        state.onAlphaFunc(GL11.GL_NEVER, 0.1f);
        state.onPopAttrib();
        assertEquals(GL14.GL_FUNC_REVERSE_SUBTRACT, state.getInteger(GL14.GL_BLEND_EQUATION));
        assertEquals(GL11.GL_GEQUAL, state.getInteger(GL11.GL_ALPHA_TEST_FUNC));
        final FloatBuffer refAfter = directFloatBuffer(16);
        assertTrue(state.getFloat(GL11.GL_ALPHA_TEST_REF, refAfter));
        assertEquals(0.5f, refAfter.get(0), EPS);

        // ENABLE_BIT 不含这两个标量：pop 后不恢复
        state.onPushAttrib(GL11.GL_ENABLE_BIT);
        state.onBlendEquation(GL14.GL_MIN);
        state.onPopAttrib();
        assertEquals(GL14.GL_MIN, state.getInteger(GL14.GL_BLEND_EQUATION),
                "ENABLE_BIT 不覆盖 blendEquation");
    }

    @Test
    void bufferBindingsTrackedAndClientAttribRestoresArrayBinding() {
        final SimulatedGlState state = new SimulatedGlState();
        assertEquals(0, state.getInteger(GL15.GL_ARRAY_BUFFER_BINDING));
        assertEquals(0, state.getInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING));

        state.onBindBuffer(GL15.GL_ARRAY_BUFFER, 7);
        state.onBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 9);
        assertEquals(7, state.getInteger(GL15.GL_ARRAY_BUFFER_BINDING));
        assertEquals(9, state.getInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING));

        state.onPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        state.onBindBuffer(GL15.GL_ARRAY_BUFFER, 42);
        state.onBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 43);
        state.onPopClientAttrib();
        assertEquals(7, state.getInteger(GL15.GL_ARRAY_BUFFER_BINDING),
                "CLIENT_VERTEX_ARRAY_BIT 恢复 ARRAY_BUFFER 绑定");
        assertEquals(43, state.getInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING),
                "ELEMENT_ARRAY_BUFFER 是 server 状态，client attrib 不恢复");
    }

    @Test
    void unpairedPopIsNoOpForNewGroups() {
        // 与 server 栈同理：下溢 pop 为空操作，所有分组簿记保持不变
        final SimulatedGlState state = new SimulatedGlState();
        state.onEnable(GL11.GL_BLEND);
        state.onBlendEquation(GL14.GL_FUNC_REVERSE_SUBTRACT);
        state.onAlphaFunc(GL11.GL_GEQUAL, 0.5f);
        state.onPopAttrib();
        assertEquals(Boolean.TRUE, state.getBoolean(GL11.GL_BLEND), "下溢 pop 为空操作，enable 位不变");
        assertEquals(GL14.GL_FUNC_REVERSE_SUBTRACT, state.getInteger(GL14.GL_BLEND_EQUATION),
                "下溢 pop 为空操作，blendEquation 不变");
        assertEquals(GL11.GL_GEQUAL, state.getInteger(GL11.GL_ALPHA_TEST_FUNC),
                "下溢 pop 为空操作，alpha 状态不变");
        final FloatBuffer ref = directFloatBuffer(16);
        assertTrue(state.getFloat(GL11.GL_ALPHA_TEST_REF, ref));
        assertEquals(0.5f, ref.get(0), EPS);

        state.onBindBuffer(GL15.GL_ARRAY_BUFFER, 7);
        state.onPopClientAttrib();
        assertEquals(7, state.getInteger(GL15.GL_ARRAY_BUFFER_BINDING),
                "下溢 client pop 为空操作，ARRAY_BUFFER 绑定不变");
        assertEquals(0, state.getInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING),
                "ELEMENT_ARRAY_BUFFER 不受 client attrib 影响");
    }

    @Test
    void contextRecreatedResetsNewGroups() {
        final SimulatedGlState state = new SimulatedGlState();
        state.onEnable(GL11.GL_BLEND);
        state.onEnable(GL11.GL_SCISSOR_TEST);
        state.onBlendEquation(GL14.GL_FUNC_REVERSE_SUBTRACT);
        state.onAlphaFunc(GL11.GL_GEQUAL, 0.5f);
        state.onBindBuffer(GL15.GL_ARRAY_BUFFER, 7);
        state.onBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 9);
        state.onPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);

        state.onContextRecreated();

        assertEquals(Boolean.FALSE, state.getBoolean(GL11.GL_BLEND));
        assertEquals(Boolean.FALSE, state.getBoolean(GL11.GL_SCISSOR_TEST));
        assertEquals(GL14.GL_FUNC_ADD, state.getInteger(GL14.GL_BLEND_EQUATION));
        assertEquals(GL11.GL_ALWAYS, state.getInteger(GL11.GL_ALPHA_TEST_FUNC));
        assertEquals(0, state.getInteger(GL15.GL_ARRAY_BUFFER_BINDING));
        assertEquals(0, state.getInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING));
        // client attrib 栈一并复位：再次 pop 为下溢空操作，绑定保持有效
        state.onPopClientAttrib();
        assertEquals(0, state.getInteger(GL15.GL_ARRAY_BUFFER_BINDING));
    }

    @Test
    void adoptionResyncsAfterInvalidation() {
        final SimulatedGlState state = new SimulatedGlState();
        // viewport 在首个 glViewport 录制前无效，是天然的失效初始态
        assertNull(state.getInteger(GL11.GL_VIEWPORT));

        // 阻塞通道读回的权威值采入簿记：getter 恢复仿真命中
        state.adoptViewport(1, 2, 300, 200);
        final IntBuffer vp = IntBuffer.allocate(4);
        assertTrue(state.getInteger(GL11.GL_VIEWPORT, vp), "VIEWPORT 采入后 buffer 形式恢复命中");
        vp.flip();
        assertEquals(1, vp.get());
        assertEquals(2, vp.get());
        assertEquals(300, vp.get());
        assertEquals(200, vp.get());

        // 采入后继续参与 attrib 快照恢复（簿记已重新生效）
        state.onPushAttrib(GL11.GL_VIEWPORT_BIT);
        state.onViewport(5, 6, 640, 480);
        state.onPopAttrib();
        final IntBuffer vp2 = IntBuffer.allocate(4);
        assertTrue(state.getInteger(GL11.GL_VIEWPORT, vp2), "采入后的值被 push 快照、pop 恢复");
        vp2.flip();
        assertEquals(1, vp2.get());
        assertEquals(300, vp2.get(2));

        // adoptBoolean/adoptInteger 直接写入并生效
        state.adoptBoolean(GL11.GL_BLEND, true);
        assertEquals(Boolean.TRUE, state.getBoolean(GL11.GL_BLEND));
        state.adoptInteger(GL14.GL_BLEND_EQUATION, GL14.GL_FUNC_REVERSE_SUBTRACT);
        assertEquals(GL14.GL_FUNC_REVERSE_SUBTRACT, state.getInteger(GL14.GL_BLEND_EQUATION));

        // 未跟踪能力的 adopt 是 no-op
        state.adoptBoolean(GL11.GL_DEPTH_TEST, true);
        assertNull(state.getBoolean(GL11.GL_DEPTH_TEST));
    }

    @Test
    void alphaFuncAndRefValidityAreIndependent() {
        // adopt 按 func/ref 粒度独立写入：采入 func 不触碰 ref
        final SimulatedGlState state = new SimulatedGlState();
        state.onAlphaFunc(GL11.GL_GEQUAL, 0.5f);

        state.adoptInteger(GL11.GL_ALPHA_TEST_FUNC, GL11.GL_NEVER);
        assertEquals(GL11.GL_NEVER, state.getInteger(GL11.GL_ALPHA_TEST_FUNC), "func 单独采入生效");
        final FloatBuffer ref = directFloatBuffer(16);
        assertTrue(state.getFloat(GL11.GL_ALPHA_TEST_REF, ref));
        assertEquals(0.5f, ref.get(0), EPS, "ref 不受 func 采入影响");
        state.adoptAlphaRef(0.25f);
        final FloatBuffer ref2 = directFloatBuffer(16);
        assertTrue(state.getFloat(GL11.GL_ALPHA_TEST_REF, ref2));
        assertEquals(0.25f, ref2.get(0), EPS);
    }

    // ------------------------------------------------------------------
    // is* 名字簿记（glIsProgram/glIsTexture 仿真数据源）
    // ------------------------------------------------------------------

    @Test
    void programNameLifecycle() {
        final SimulatedGlState state = new SimulatedGlState();
        assertFalse(state.isProgram(7), "未创建的名字不是 program");

        state.onCreateProgram(7);
        assertTrue(state.isProgram(7));

        // 非当前 program 删除立即失效
        state.onDeleteProgram(7);
        assertFalse(state.isProgram(7));
    }

    @Test
    void deleteWhileInUseDefersProgramInvalidation() {
        final SimulatedGlState state = new SimulatedGlState();
        state.onCreateProgram(7);
        state.onCreateProgram(9);
        state.onUseProgram(7);

        // 使用中删除：GL 规范延迟销毁，isProgram 仍为 true
        state.onDeleteProgram(7);
        assertTrue(state.isProgram(7), "使用中删除的 program 在解绑前仍有效");

        // 切换到其他 program 后销毁；无关 program 的切换不影响仍当前的删除标记
        state.onUseProgram(9);
        assertFalse(state.isProgram(7), "解绑后删除生效");
        assertTrue(state.isProgram(9));
    }

    @Test
    void textureNameLifecycle() {
        final SimulatedGlState state = new SimulatedGlState();
        assertFalse(state.isTexture(3));

        // gen 即入册
        state.onGenTexture(3);
        assertTrue(state.isTexture(3));
        state.onDeleteTexture(3);
        assertFalse(state.isTexture(3));

        // GL1.x bind-创建语义：bind 未 gen 的名字也入册；名字 0 不是纹理
        state.onBindTexture(GL11.GL_TEXTURE_2D, 11);
        assertTrue(state.isTexture(11));
        state.onBindTexture(GL11.GL_TEXTURE_2D, 0);
        assertFalse(state.isTexture(0), "名字 0 是无纹理占位，不入册");

        // 绑定簿记与名字簿记独立：删除后绑定槽清零
        assertEquals(0, state.getInteger(GL11.GL_TEXTURE_BINDING_2D));
    }

    // ------------------------------------------------------------------
    // 纹理 level-0 参数与 caps 常量缓存
    // ------------------------------------------------------------------

    @Test
    void texLevel0ParamsTrackedAtUploadAndClearedAtDelete() {
        final SimulatedGlState state = new SimulatedGlState();
        state.onBindTexture(GL11.GL_TEXTURE_2D, 5);
        state.onTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, 64, 32);

        assertEquals(64, state.getTexLevelParam(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH));
        assertEquals(32, state.getTexLevelParam(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT));
        assertEquals(GL11.GL_RGBA, state.getTexLevelParam(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT));
        assertEquals(0, state.getTexLevelParam(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_BORDER));

        // 非零 level 与不支持的 pname 不跟踪
        assertNull(state.getTexLevelParam(GL11.GL_TEXTURE_2D, 1, GL11.GL_TEXTURE_WIDTH));
        // 未上传参数的纹理回退
        state.onBindTexture(GL11.GL_TEXTURE_2D, 6);
        assertNull(state.getTexLevelParam(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH));
        // 删除后簿记清除
        state.onBindTexture(GL11.GL_TEXTURE_2D, 5);
        state.onDeleteTexture(5);
        assertNull(state.getTexLevelParam(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH));
    }

    @Test
    void capsCacheAdoptAndContextRecreation() {
        final SimulatedGlState state = new SimulatedGlState();
        assertTrue(SimulatedGlState.isCapConstant(GL11.GL_MAX_TEXTURE_SIZE));
        assertFalse(SimulatedGlState.isCapConstant(GL11.GL_VIEWPORT), "动态状态不是 caps 常量");

        assertNull(state.cachedCap(GL11.GL_MAX_TEXTURE_SIZE));
        state.adoptCap(GL11.GL_MAX_TEXTURE_SIZE, 16384);
        assertEquals(16384, state.cachedCap(GL11.GL_MAX_TEXTURE_SIZE));

        // 上下文重建：名字簿记/纹理参数/caps 全部归零
        state.onCreateProgram(7);
        state.onBindTexture(GL11.GL_TEXTURE_2D, 5);
        state.onTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, 8, 8);
        state.onContextRecreated();
        assertNull(state.cachedCap(GL11.GL_MAX_TEXTURE_SIZE));
        assertFalse(state.isProgram(7));
        assertFalse(state.isTexture(5));
        assertNull(state.getTexLevelParam(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH));
    }
}
