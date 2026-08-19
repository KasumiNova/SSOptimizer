package github.kasuminova.ssoptimizer.bridge.opengl;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * 录制侧 GL 状态仿真：把 getter 回读类调用（glGetInteger/glGetFloat）从
 * 「阻塞通道全管线 drain」降级为「录制线程本地簿记查询」。
 * <p>
 * 动机：ASTD TexTrailRenderer 等模组路径每帧做保存/恢复惯用法
 * （glGetInteger(CURRENT_PROGRAM/TEXTURE_BINDING_2D/ACTIVE_TEXTURE/DRAW_BUFFER/VIEWPORT)
 * + glGetFloat(PROJECTION/MODELVIEW_MATRIX)），每次回读在分离模式下都是一次
 * 全管线 drain，会触发 StallDetector 熔断。这些 pname 对应的状态全部经 bridge
 * 命令流镜像（glUseProgram/glBindTexture/glActiveTexture/glDrawBuffer/glViewport/
 * 矩阵族），录制侧簿记在命令流顺序下与真实状态逐指令一致。
 * <p>
 * 语义安全：glPushAttrib/glPopAttrib 对 VIEWPORT/TRANSFORM 位做按位快照恢复，
 * glDrawBuffers 按规范簿记首元素（GL_DRAW_BUFFER 单值查询即 DRAW_BUFFER0），
 * 仅真正不可簿记的突变（attrib 配对不可知/栈溢出、显示模式切换重建上下文）
 * 会把对应分组标记为失效，getter 回退真实阻塞通道——语义正确性优先于零阻塞。
 * 矩阵栈初始为单位阵（与全新 GL 上下文一致），视口在首个 glViewport 录制前无效。
 * <p>
 * 实例非线程安全，线程隔离由 {@link RecordingContext} 访问约定保证；
 * 仅主录制线程的 getter 走仿真（见桥接调用点），aux 线程一律回退阻塞——
 * aux 线程对状态的突变不会反映到主线程簿记（与 FRAMEBUFFER 绑定跟踪同级的
 * 记录序近似，见 BridgeSupport 类 javadoc）。
 */
final class SimulatedGlState {
    /** 跟踪的纹理单元数（GL_TEXTURE0+i，i&lt;32；越界绑定不跟踪，getter 回退）。 */
    static final int MAX_TEXTURE_UNITS = 32;
    /** 矩阵栈深度上限（真实实现 MODELVIEW ≥32，此处统一 64，溢出保持栈顶不变）。 */
    static final int MAX_MATRIX_STACK  = 64;
    /** attrib mask 栈深度上限（溢出后 attrib 跟踪失效，pop 一律失效化处理）。 */
    static final int MAX_ATTRIB_STACK  = 64;

    private static final int MODE_MODELVIEW  = 0;
    private static final int MODE_PROJECTION = 1;
    private static final int MODE_TEXTURE    = 2;
    private static final int MODE_COUNT      = 3;

    /** GL_CURRENT_PROGRAM 簿记（glUseProgram 镜像），0=固定管线。 */
    private int currentProgram;
    /** GL_ACTIVE_TEXTURE 簿记（glActiveTexture 镜像），初值 GL_TEXTURE0。 */
    private int activeTexture = GL13.GL_TEXTURE0;
    /** GL_DRAW_BUFFER 簿记（glDrawBuffer 镜像），双缓冲默认 GL_BACK。 */
    private int drawBuffer = GL11.GL_BACK;
    private boolean drawBufferValid = true;
    /** GL_VIEWPORT 簿记（glViewport 镜像），首个 glViewport 前无效。 */
    private final int[] viewport = new int[4];
    private boolean viewportValid;
    /** 每纹理单元 GL_TEXTURE_BINDING_2D 簿记（glBindTexture/glDeleteTextures 镜像）。 */
    private final int[] texture2dBinding = new int[MAX_TEXTURE_UNITS];
    /** GL_MATRIX_MODE 簿记（glMatrixMode 镜像），初值 MODELVIEW。 */
    private int matrixMode = GL11.GL_MODELVIEW;
    private boolean matrixModeValid = true;

    /** 三个矩阵栈（MODELVIEW/PROJECTION/TEXTURE），每栈 MAX_MATRIX_STACK*16，列主序。 */
    private final float[][] matrixStacks = new float[MODE_COUNT][MAX_MATRIX_STACK * 16];
    private final int[]     matrixDepths = new int[MODE_COUNT];
    private final boolean[] matrixValid  = {true, true, true};
    /** glPushAttrib 的 mask 栈与按位快照：pop 时按位恢复簿记值。 */
    private final int[] attribMasks = new int[MAX_ATTRIB_STACK];
    /** 每槽 VIEWPORT_BIT 快照（mask 含位且 push 时 viewport 有效才记录）。 */
    private final int[] attribViewportSnaps = new int[MAX_ATTRIB_STACK * 4];
    private final boolean[] attribViewportSnapValid = new boolean[MAX_ATTRIB_STACK];
    /** 每槽 TRANSFORM_BIT 快照（matrixMode 单值）。 */
    private final int[] attribMatrixModeSnaps = new int[MAX_ATTRIB_STACK];
    private final boolean[] attribMatrixModeSnapValid = new boolean[MAX_ATTRIB_STACK];
    private int     attribDepth;
    private boolean attribTrackingValid = true;

    SimulatedGlState() {
        for (int mode = 0; mode < MODE_COUNT; mode++) {
            matrixDepths[mode] = 1;
            identity(matrixStacks[mode], 0);
        }
    }

    // ------------------------------------------------------------------
    // 标量记录点（桥接各 setter 调用）
    // ------------------------------------------------------------------

    void onUseProgram(final int program) {
        currentProgram = program;
    }

    void onActiveTexture(final int texture) {
        activeTexture = texture;
    }

    void onDrawBuffer(final int mode) {
        drawBuffer = mode;
        drawBufferValid = true;
    }

    /**
     * glDrawBuffers 簿记：GL_DRAW_BUFFER 单值查询的规范语义即 DRAW_BUFFER0，
     * 记录 buffers 首元素（与模组保存/恢复惯用法逐指令一致）。
     */
    void onDrawBuffers(final int firstBuffer) {
        drawBuffer = firstBuffer;
        drawBufferValid = true;
    }

    void onViewport(final int x, final int y, final int width, final int height) {
        viewport[0] = x;
        viewport[1] = y;
        viewport[2] = width;
        viewport[3] = height;
        viewportValid = true;
    }

    void onBindTexture(final int target, final int texture) {
        if (target != GL11.GL_TEXTURE_2D) {
            return;
        }
        final int unit = activeTexture - GL13.GL_TEXTURE0;
        if (unit >= 0 && unit < MAX_TEXTURE_UNITS) {
            texture2dBinding[unit] = texture;
        }
    }

    void onDeleteTexture(final int texture) {
        for (int unit = 0; unit < MAX_TEXTURE_UNITS; unit++) {
            if (texture2dBinding[unit] == texture) {
                texture2dBinding[unit] = 0;
            }
        }
    }

    void onMatrixMode(final int mode) {
        matrixMode = mode;
        matrixModeValid = true;
    }

    // ------------------------------------------------------------------
    // 矩阵记录点
    // ------------------------------------------------------------------

    void onLoadIdentity() {
        final int mode = currentMatrixMode();
        if (mode < 0) {
            return;
        }
        identity(matrixStacks[mode], topOffset(mode));
    }

    void onLoadMatrix(final FloatBuffer matrix) {
        final int mode = currentMatrixMode();
        if (mode < 0 || matrix.remaining() < 16) {
            return;
        }
        final float[] stack = matrixStacks[mode];
        final int top = topOffset(mode);
        final int position = matrix.position();
        for (int i = 0; i < 16; i++) {
            stack[top + i] = matrix.get(position + i);
        }
    }

    void onMultMatrix(final FloatBuffer matrix) {
        final int mode = currentMatrixMode();
        if (mode < 0 || matrix.remaining() < 16) {
            return;
        }
        final float[] operand = new float[16];
        final int position = matrix.position();
        for (int i = 0; i < 16; i++) {
            operand[i] = matrix.get(position + i);
        }
        final float[] stack = matrixStacks[mode];
        multiplyInPlace(stack, topOffset(mode), operand);
    }

    void onLoadMatrix(final DoubleBuffer matrix) {
        final int mode = currentMatrixMode();
        if (mode < 0 || matrix.remaining() < 16) {
            return;
        }
        final float[] stack = matrixStacks[mode];
        final int top = topOffset(mode);
        final int position = matrix.position();
        for (int i = 0; i < 16; i++) {
            stack[top + i] = (float) matrix.get(position + i);
        }
    }

    void onMultMatrix(final DoubleBuffer matrix) {
        final int mode = currentMatrixMode();
        if (mode < 0 || matrix.remaining() < 16) {
            return;
        }
        final float[] operand = new float[16];
        final int position = matrix.position();
        for (int i = 0; i < 16; i++) {
            operand[i] = (float) matrix.get(position + i);
        }
        final float[] stack = matrixStacks[mode];
        multiplyInPlace(stack, topOffset(mode), operand);
    }

    void onTranslate(final double x, final double y, final double z) {
        final int mode = currentMatrixMode();
        if (mode < 0) {
            return;
        }
        final float[] stack = matrixStacks[mode];
        final int top = topOffset(mode);
        // M = M * T：新末列 = x*col0 + y*col1 + z*col2 + col3，其余列不变
        for (int row = 0; row < 4; row++) {
            stack[top + 12 + row] = (float) (stack[top + row] * x
                    + stack[top + 4 + row] * y
                    + stack[top + 8 + row] * z
                    + stack[top + 12 + row]);
        }
    }

    void onScale(final double x, final double y, final double z) {
        final int mode = currentMatrixMode();
        if (mode < 0) {
            return;
        }
        final float[] stack = matrixStacks[mode];
        final int top = topOffset(mode);
        for (int row = 0; row < 4; row++) {
            stack[top + row] *= (float) x;
            stack[top + 4 + row] *= (float) y;
            stack[top + 8 + row] *= (float) z;
        }
    }

    void onRotate(final double angleDegrees, final double x, final double y, final double z) {
        final int mode = currentMatrixMode();
        if (mode < 0) {
            return;
        }
        final double length = Math.sqrt(x * x + y * y + z * z);
        if (length == 0.0) {
            return;
        }
        final double nx = x / length;
        final double ny = y / length;
        final double nz = z / length;
        final double radians = Math.toRadians(angleDegrees);
        final double c = Math.cos(radians);
        final double s = Math.sin(radians);
        final double t = 1.0 - c;
        // 列主序旋转矩阵（GL glRotate 约定）
        final float[] rotation = new float[16];
        rotation[0] = (float) (t * nx * nx + c);
        rotation[1] = (float) (t * nx * ny + s * nz);
        rotation[2] = (float) (t * nx * nz - s * ny);
        rotation[4] = (float) (t * nx * ny - s * nz);
        rotation[5] = (float) (t * ny * ny + c);
        rotation[6] = (float) (t * ny * nz + s * nx);
        rotation[8] = (float) (t * nx * nz + s * ny);
        rotation[9] = (float) (t * ny * nz - s * nx);
        rotation[10] = (float) (t * nz * nz + c);
        rotation[15] = 1.0f;
        final float[] stack = matrixStacks[mode];
        multiplyInPlace(stack, topOffset(mode), rotation);
    }

    void onOrtho(final double left, final double right, final double bottom, final double top,
                 final double zNear, final double zFar) {
        final int mode = currentMatrixMode();
        if (mode < 0) {
            return;
        }
        final float[] ortho = new float[16];
        ortho[0] = (float) (2.0 / (right - left));
        ortho[5] = (float) (2.0 / (top - bottom));
        ortho[10] = (float) (-2.0 / (zFar - zNear));
        ortho[12] = (float) (-(right + left) / (right - left));
        ortho[13] = (float) (-(top + bottom) / (top - bottom));
        ortho[14] = (float) (-(zFar + zNear) / (zFar - zNear));
        ortho[15] = 1.0f;
        final float[] stack = matrixStacks[mode];
        multiplyInPlace(stack, topOffset(mode), ortho);
    }

    void onPushMatrix() {
        final int mode = currentMatrixMode();
        if (mode < 0) {
            return;
        }
        final int depth = matrixDepths[mode];
        if (depth >= MAX_MATRIX_STACK) {
            // 真实 GL 报 GL_STACK_OVERFLOW 且栈不变；簿记保持一致
            return;
        }
        final float[] stack = matrixStacks[mode];
        System.arraycopy(stack, (depth - 1) * 16, stack, depth * 16, 16);
        matrixDepths[mode] = depth + 1;
    }

    void onPopMatrix() {
        final int mode = currentMatrixMode();
        if (mode < 0) {
            return;
        }
        if (matrixDepths[mode] > 1) {
            matrixDepths[mode]--;
        }
        // 深度 1 时真实 GL 报 GL_STACK_UNDERFLOW 且栈顶不变，簿记保持一致
    }

    // ------------------------------------------------------------------
    // attrib 记录点
    // ------------------------------------------------------------------

    void onPushAttrib(final int mask) {
        if (!attribTrackingValid) {
            return;
        }
        if (attribDepth >= MAX_ATTRIB_STACK) {
            // 溢出后 push/pop 配对关系不可知，失效化整组跟踪
            attribTrackingValid = false;
            attribDepth = 0;
            return;
        }
        // 按位快照：pop 时原值恢复，簿记全程不失效
        if ((mask & GL11.GL_VIEWPORT_BIT) != 0 && viewportValid) {
            System.arraycopy(viewport, 0, attribViewportSnaps, attribDepth * 4, 4);
            attribViewportSnapValid[attribDepth] = true;
        } else {
            attribViewportSnapValid[attribDepth] = false;
        }
        if ((mask & GL11.GL_TRANSFORM_BIT) != 0 && matrixModeValid) {
            attribMatrixModeSnaps[attribDepth] = matrixMode;
            attribMatrixModeSnapValid[attribDepth] = true;
        } else {
            attribMatrixModeSnapValid[attribDepth] = false;
        }
        attribMasks[attribDepth++] = mask;
    }

    void onPopAttrib() {
        if (!attribTrackingValid || attribDepth == 0) {
            // 配对关系不可知：保守失效化所有 attrib 可恢复分组
            viewportValid = false;
            matrixModeValid = false;
            return;
        }
        final int mask = attribMasks[--attribDepth];
        if ((mask & GL11.GL_VIEWPORT_BIT) != 0) {
            if (attribViewportSnapValid[attribDepth]) {
                System.arraycopy(attribViewportSnaps, attribDepth * 4, viewport, 0, 4);
                viewportValid = true;
            } else {
                viewportValid = false;
            }
        }
        if ((mask & GL11.GL_TRANSFORM_BIT) != 0) {
            if (attribMatrixModeSnapValid[attribDepth]) {
                matrixMode = attribMatrixModeSnaps[attribDepth];
                matrixModeValid = true;
            } else {
                matrixModeValid = false;
            }
        }
    }

    /** 显示模式/全屏切换重建上下文后：全部状态回到全新上下文默认值。 */
    void onContextRecreated() {
        currentProgram = 0;
        activeTexture = GL13.GL_TEXTURE0;
        drawBuffer = GL11.GL_BACK;
        drawBufferValid = true;
        viewportValid = false;
        java.util.Arrays.fill(texture2dBinding, 0);
        matrixMode = GL11.GL_MODELVIEW;
        matrixModeValid = true;
        for (int mode = 0; mode < MODE_COUNT; mode++) {
            matrixDepths[mode] = 1;
            matrixValid[mode] = true;
            identity(matrixStacks[mode], 0);
        }
        attribDepth = 0;
        attribTrackingValid = true;
    }

    // ------------------------------------------------------------------
    // getter 仿真（返回值/写出成功返回 true，未跟踪或失效返回 false→回退阻塞）
    // ------------------------------------------------------------------

    /** 单值 glGetInteger 仿真；未跟踪或失效返回 null。 */
    Integer getInteger(final int pname) {
        switch (pname) {
            case GL20.GL_CURRENT_PROGRAM:
                return currentProgram;
            case GL13.GL_ACTIVE_TEXTURE:
                return activeTexture;
            case GL11.GL_DRAW_BUFFER:
                return drawBufferValid ? drawBuffer : null;
            case GL11.GL_TEXTURE_BINDING_2D: {
                final int unit = activeTexture - GL13.GL_TEXTURE0;
                if (unit < 0 || unit >= MAX_TEXTURE_UNITS) {
                    return null;
                }
                return texture2dBinding[unit];
            }
            case GL11.GL_MATRIX_MODE:
                return matrixModeValid ? matrixMode : null;
            case GL11.GL_VIEWPORT:
                return viewportValid ? viewport[0] : null;
            default:
                return null;
        }
    }

    /** glGetInteger(pname, IntBuffer) 仿真：当前仅 GL_VIEWPORT（4 值）。 */
    boolean getInteger(final int pname, final IntBuffer params) {
        if (pname != GL11.GL_VIEWPORT || !viewportValid || params.remaining() < 4) {
            return false;
        }
        params.put(viewport[0]).put(viewport[1]).put(viewport[2]).put(viewport[3]);
        return true;
    }

    /** glGetFloat(pname, FloatBuffer) 仿真：三个矩阵栈顶（16 值）。 */
    boolean getFloat(final int pname, final FloatBuffer params) {
        final int mode;
        switch (pname) {
            case GL11.GL_MODELVIEW_MATRIX:
                mode = MODE_MODELVIEW;
                break;
            case GL11.GL_PROJECTION_MATRIX:
                mode = MODE_PROJECTION;
                break;
            case GL11.GL_TEXTURE_MATRIX:
                mode = MODE_TEXTURE;
                break;
            default:
                return false;
        }
        if (!matrixValid[mode] || params.remaining() < 16) {
            return false;
        }
        final float[] stack = matrixStacks[mode];
        final int top = topOffset(mode);
        for (int i = 0; i < 16; i++) {
            params.put(stack[top + i]);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 当前矩阵模式映射到跟踪栈；COLOR 等未跟踪模式返回 -1（操作忽略，getter 不受影响）。 */
    private int currentMatrixMode() {
        switch (matrixMode) {
            case GL11.GL_MODELVIEW:
                return MODE_MODELVIEW;
            case GL11.GL_PROJECTION:
                return MODE_PROJECTION;
            case GL11.GL_TEXTURE:
                return MODE_TEXTURE;
            default:
                return -1;
        }
    }

    private int topOffset(final int mode) {
        return (matrixDepths[mode] - 1) * 16;
    }

    private static void identity(final float[] matrix, final int offset) {
        for (int i = 0; i < 16; i++) {
            matrix[offset + i] = 0.0f;
        }
        matrix[offset] = 1.0f;
        matrix[offset + 5] = 1.0f;
        matrix[offset + 10] = 1.0f;
        matrix[offset + 15] = 1.0f;
    }

    /** 列主序 4x4 原地右乘：{@code stack[top] = stack[top] * operand}。 */
    private static void multiplyInPlace(final float[] stack, final int top, final float[] operand) {
        final float[] result = new float[16];
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                result[col * 4 + row] = stack[top + row] * operand[col * 4]
                        + stack[top + 4 + row] * operand[col * 4 + 1]
                        + stack[top + 8 + row] * operand[col * 4 + 2]
                        + stack[top + 12 + row] * operand[col * 4 + 3];
            }
        }
        System.arraycopy(result, 0, stack, top, 16);
    }
}
