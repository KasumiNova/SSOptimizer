package github.kasuminova.ssoptimizer.bridge.opengl;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
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
 * matrixMode/纹理绑定）、attrib 失效语义与上下文重建复位。
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
    void popAttribWithoutPairInvalidates() {
        final SimulatedGlState state = new SimulatedGlState();
        state.onViewport(0, 0, 100, 100);
        state.onMatrixMode(GL11.GL_PROJECTION);
        state.onPopAttrib();
        assertNull(state.getInteger(GL11.GL_VIEWPORT), "无配对 pop 失效化 viewport");
        assertNull(state.getInteger(GL11.GL_MATRIX_MODE), "无配对 pop 失效化 matrixMode");
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
        // attrib 栈一并复位：再次 pop 走「配对不可知」失效化，不得抛异常
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
}
