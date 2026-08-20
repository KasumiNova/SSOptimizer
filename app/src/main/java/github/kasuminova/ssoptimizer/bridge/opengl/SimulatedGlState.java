package github.kasuminova.ssoptimizer.bridge.opengl;

import org.apache.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
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
 * 第二批覆盖面（SpriteBatch 收集守卫在分离模式下恢复所需的回读集）：
 * 五个 enable 能力（TEXTURE_2D/BLEND/ALPHA_TEST/STENCIL_TEST/SCISSOR_TEST，
 * glEnable/glDisable 与流内 streamEnable/streamDisable 镜像）、GL_BLEND_EQUATION
 * （glBlendEquation 镜像）、GL_ALPHA_TEST_FUNC/REF（glAlphaFunc 镜像）、
 * GL_ARRAY/ELEMENT_ARRAY_BUFFER_BINDING（glBindBuffer 镜像）。
 * pushAttrib 分组按规范双归属簿记：上述五个 enable 位同时归属 GL_ENABLE_BIT
 * 与各自功能组（TEXTURE/COLOR_BUFFER/STENCIL_BUFFER/SCISSOR_BIT），任一位入
 * mask 都做快照恢复；blendEquation 与 alphaFunc/ref 仅属 COLOR_BUFFER_BIT；
 * ARRAY_BUFFER 绑定属 client 状态，由 push/popClientAttrib 的
 * CLIENT_VERTEX_ARRAY_BIT 快照恢复。
 * <p>
 * 语义安全：glPushAttrib/glPopAttrib 对各覆盖位做按位快照恢复，
 * glDrawBuffers 按规范簿记首元素（GL_DRAW_BUFFER 单值查询即 DRAW_BUFFER0）。
 * attrib 栈溢出/下溢按 GL 规范为空操作（GL_STACK_OVERFLOW/UNDERFLOW 仅报错、
 * 状态不变），簿记同样保持不变；显示模式切换重建上下文时全部状态回到默认值。
 * getter 回退真实阻塞通道时会把读回的权威值采入簿记再同步（adopt 族），
 * 一次性成本而非永久回退。
 * 矩阵栈初始为单位阵（与全新 GL 上下文一致），视口在首个 glViewport 录制前无效。
 * <p>
 * 实例非线程安全，线程隔离由 {@link RecordingContext} 访问约定保证；
 * 仅主录制线程的 getter 走仿真（见桥接调用点），aux 线程一律回退阻塞——
 * aux 线程对状态的突变不会反映到主线程簿记（与 FRAMEBUFFER 绑定跟踪同级的
 * 记录序近似，见 BridgeSupport 类 javadoc）。
 */
final class SimulatedGlState {
    private static final Logger LOGGER = Logger.getLogger(SimulatedGlState.class);
    /** 跟踪的纹理单元数（GL_TEXTURE0+i，i&lt;32；越界绑定不跟踪，getter 回退）。 */
    static final int MAX_TEXTURE_UNITS = 32;
    /** 矩阵栈深度上限（真实实现 MODELVIEW ≥32，此处统一 64，溢出保持栈顶不变）。 */
    static final int MAX_MATRIX_STACK  = 64;
    /** attrib mask 栈深度上限（溢出/下溢按 GL 规范为空操作，簿记保持不变）。 */
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

    // ------------------------------------------------------------------
    // enable 位与标量状态跟踪（SpriteBatch 收集守卫等 glGetBoolean/glGetInteger
    // 回读的仿真数据源；镜像点为 bridge glEnable/glDisable/glAlphaFunc/
    // glBlendEquation/glBindBuffer 与流内 streamEnable/streamDisable/streamBindTexture）
    // ------------------------------------------------------------------

    /** 跟踪的 enable 能力槽数（槽位映射见 {@link #capSlot(int)}）。 */
    private static final int CAP_COUNT = 5;
    /** attrib 配对丢失的一次性告警标记（server/client 各一）。 */
    private boolean unpairedServerPopWarned;
    private boolean unpairedClientPopWarned;
    /**
     * 各槽位的 pushAttrib 覆盖位：GL 规范中这些 enable 位同时归属「enable 组」
     * （{@code GL_ENABLE_BIT}）与各自功能组（TEXTURE_2D→TEXTURE_BIT、
     * BLEND/ALPHA_TEST→COLOR_BUFFER_BIT、STENCIL_TEST→STENCIL_BUFFER_BIT、
     * SCISSOR_TEST→SCISSOR_BIT），任一位入 mask 都会被 push/pop 快照恢复。
     */
    private static final int[] CAP_PUSH_MASKS = {
            GL11.GL_TEXTURE_BIT | GL11.GL_ENABLE_BIT,
            GL11.GL_COLOR_BUFFER_BIT | GL11.GL_ENABLE_BIT,
            GL11.GL_COLOR_BUFFER_BIT | GL11.GL_ENABLE_BIT,
            GL11.GL_STENCIL_BUFFER_BIT | GL11.GL_ENABLE_BIT,
            GL11.GL_SCISSOR_BIT | GL11.GL_ENABLE_BIT,
    };
    /** 各能力当前启用状态（全新 GL 上下文默认全 false）。 */
    private final boolean[] capEnabled = new boolean[CAP_COUNT];
    /** 各能力簿记有效性（pop 恢复未快照能力时失效；绝对设置/adopt 采入使其重新生效）。 */
    private final boolean[] capValid   = {true, true, true, true, true};

    /** GL_BLEND_EQUATION 簿记（glBlendEquation 镜像），全新上下文默认 GL_FUNC_ADD。 */
    private int     blendEquation      = GL14.GL_FUNC_ADD;
    private boolean blendEquationValid = true;
    /** GL_ALPHA_TEST_FUNC 簿记（glAlphaFunc 镜像），全新上下文默认 GL_ALWAYS。 */
    private int     alphaFunc      = GL11.GL_ALWAYS;
    private boolean alphaFuncValid = true;
    /** GL_ALPHA_TEST_REF 簿记（glAlphaFunc 镜像），默认 0；与 func 各自独立有效位。 */
    private float   alphaRef;
    private boolean alphaRefValid = true;

    /** GL_ARRAY_BUFFER_BINDING 簿记（glBindBuffer 镜像；client attrib 可快照恢复）。 */
    private int     arrayBufferBinding;
    private boolean arrayBufferBindingValid = true;
    /** GL_ELEMENT_ARRAY_BUFFER_BINDING 簿记（server 状态，不入任何 attrib 组）。 */
    private int elementArrayBufferBinding;

    /** attrib 栈每槽的能力快照：attribCapSnapSet 位图标记该槽快照了哪些能力。 */
    private final boolean[][] attribCapSnaps        = new boolean[MAX_ATTRIB_STACK][CAP_COUNT];
    private final int[]       attribCapSnapSet      = new int[MAX_ATTRIB_STACK];
    /** attrib 栈每槽的 COLOR_BUFFER_BIT 标量快照（blendEquation 与 alphaFunc/ref 各自独立有效位）。 */
    private final int[]     attribBlendEqSnaps       = new int[MAX_ATTRIB_STACK];
    private final boolean[] attribBlendEqSnapValid   = new boolean[MAX_ATTRIB_STACK];
    private final int[]     attribAlphaFuncSnaps     = new int[MAX_ATTRIB_STACK];
    private final float[]   attribAlphaRefSnaps      = new float[MAX_ATTRIB_STACK];
    private final boolean[] attribAlphaSnapValid     = new boolean[MAX_ATTRIB_STACK];

    /** client attrib 栈（仅跟踪 CLIENT_VERTEX_ARRAY_BIT 覆盖的 ARRAY_BUFFER 绑定）。 */
    private final int[]     clientMasks          = new int[MAX_ATTRIB_STACK];
    private final int[]     clientArrayBufSnaps  = new int[MAX_ATTRIB_STACK];
    private final boolean[] clientArraySnapValid = new boolean[MAX_ATTRIB_STACK];
    private int     clientDepth;

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

    /** glEnable/流内 streamEnable 簿记；未跟踪的能力忽略（getter 回退阻塞通道）。 */
    void onEnable(final int cap) {
        final int slot = capSlot(cap);
        if (slot >= 0) {
            capEnabled[slot] = true;
            capValid[slot] = true;
        }
    }

    /** glDisable/流内 streamDisable 簿记，语义同 {@link #onEnable(int)}。 */
    void onDisable(final int cap) {
        final int slot = capSlot(cap);
        if (slot >= 0) {
            capEnabled[slot] = false;
            capValid[slot] = true;
        }
    }

    void onBlendEquation(final int mode) {
        blendEquation = mode;
        blendEquationValid = true;
    }

    void onAlphaFunc(final int func, final float ref) {
        alphaFunc = func;
        alphaRef = ref;
        alphaFuncValid = true;
        alphaRefValid = true;
    }

    /** glBindBuffer 簿记；其余 target（PIXEL_PACK 等）不跟踪。 */
    void onBindBuffer(final int target, final int buffer) {
        if (target == GL15.GL_ARRAY_BUFFER) {
            arrayBufferBinding = buffer;
            arrayBufferBindingValid = true;
        } else if (target == GL15.GL_ELEMENT_ARRAY_BUFFER) {
            elementArrayBufferBinding = buffer;
        }
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
        if (attribDepth >= MAX_ATTRIB_STACK) {
            // 真实 GL 报 GL_STACK_OVERFLOW 且栈不变；簿记保持一致（空操作）
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
        // enable 位按覆盖组逐能力快照（见 CAP_PUSH_MASKS 的双组归属说明）
        int capSet = 0;
        for (int cap = 0; cap < CAP_COUNT; cap++) {
            if ((mask & CAP_PUSH_MASKS[cap]) != 0 && capValid[cap]) {
                attribCapSnaps[attribDepth][cap] = capEnabled[cap];
                capSet |= 1 << cap;
            }
        }
        attribCapSnapSet[attribDepth] = capSet;
        // COLOR_BUFFER_BIT 标量：blendEquation 与 alphaFunc/ref 各有独立有效位
        if ((mask & GL11.GL_COLOR_BUFFER_BIT) != 0 && blendEquationValid) {
            attribBlendEqSnaps[attribDepth] = blendEquation;
            attribBlendEqSnapValid[attribDepth] = true;
        } else {
            attribBlendEqSnapValid[attribDepth] = false;
        }
        if ((mask & GL11.GL_COLOR_BUFFER_BIT) != 0 && alphaFuncValid && alphaRefValid) {
            attribAlphaFuncSnaps[attribDepth] = alphaFunc;
            attribAlphaRefSnaps[attribDepth] = alphaRef;
            attribAlphaSnapValid[attribDepth] = true;
        } else {
            attribAlphaSnapValid[attribDepth] = false;
        }
        attribMasks[attribDepth++] = mask;
    }

    void onPopAttrib() {
        if (attribDepth == 0) {
            // 真实 GL 报 GL_STACK_UNDERFLOW 且状态不变（空操作）；簿记同样不变。
            // 实测游戏/模组存在真实下溢（探针残留 0x504），保守失效化会把
            // 一次空操作放大为永久回退阻塞，故严格按规范语义处理
            warnUnpairedPopOnce();
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
        // enable 位按覆盖组逐能力恢复；push 时未快照的能力失效化
        for (int cap = 0; cap < CAP_COUNT; cap++) {
            if ((mask & CAP_PUSH_MASKS[cap]) != 0) {
                if ((attribCapSnapSet[attribDepth] & (1 << cap)) != 0) {
                    capEnabled[cap] = attribCapSnaps[attribDepth][cap];
                    capValid[cap] = true;
                } else {
                    capValid[cap] = false;
                }
            }
        }
        if ((mask & GL11.GL_COLOR_BUFFER_BIT) != 0) {
            if (attribBlendEqSnapValid[attribDepth]) {
                blendEquation = attribBlendEqSnaps[attribDepth];
                blendEquationValid = true;
            } else {
                blendEquationValid = false;
            }
            if (attribAlphaSnapValid[attribDepth]) {
                alphaFunc = attribAlphaFuncSnaps[attribDepth];
                alphaRef = attribAlphaRefSnaps[attribDepth];
                alphaFuncValid = true;
                alphaRefValid = true;
            } else {
                alphaFuncValid = false;
                alphaRefValid = false;
            }
        }
    }

    /** glPushClientAttrib 簿记：仅 CLIENT_VERTEX_ARRAY_BIT 覆盖的 ARRAY_BUFFER 绑定。 */
    void onPushClientAttrib(final int mask) {
        if (clientDepth >= MAX_ATTRIB_STACK) {
            // 真实 GL 报 GL_STACK_OVERFLOW 且栈不变；簿记保持一致（空操作）
            return;
        }
        if ((mask & GL11.GL_CLIENT_VERTEX_ARRAY_BIT) != 0 && arrayBufferBindingValid) {
            clientArrayBufSnaps[clientDepth] = arrayBufferBinding;
            clientArraySnapValid[clientDepth] = true;
        } else {
            clientArraySnapValid[clientDepth] = false;
        }
        clientMasks[clientDepth++] = mask;
    }

    void onPopClientAttrib() {
        if (clientDepth == 0) {
            // 与 server 栈同理：真实 GL 下溢为空操作，簿记保持不变
            if (!unpairedClientPopWarned) {
                unpairedClientPopWarned = true;
                LOGGER.warn("[SSOptimizer] GL client attrib 栈下溢（无配对 push 的 pop），"
                        + "按 GL 规范为空操作，簿记保持不变");
            }
            return;
        }
        final int mask = clientMasks[--clientDepth];
        if ((mask & GL11.GL_CLIENT_VERTEX_ARRAY_BIT) != 0) {
            if (clientArraySnapValid[clientDepth]) {
                arrayBufferBinding = clientArrayBufSnaps[clientDepth];
                arrayBufferBindingValid = true;
            } else {
                arrayBufferBindingValid = false;
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
        java.util.Arrays.fill(capEnabled, false);
        java.util.Arrays.fill(capValid, true);
        blendEquation = GL14.GL_FUNC_ADD;
        blendEquationValid = true;
        alphaFunc = GL11.GL_ALWAYS;
        alphaRef = 0.0f;
        alphaFuncValid = true;
        alphaRefValid = true;
        arrayBufferBinding = 0;
        arrayBufferBindingValid = true;
        elementArrayBufferBinding = 0;
        clientDepth = 0;
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
            case GL14.GL_BLEND_EQUATION:
                return blendEquationValid ? blendEquation : null;
            case GL11.GL_ALPHA_TEST_FUNC:
                return alphaFuncValid ? alphaFunc : null;
            case GL15.GL_ARRAY_BUFFER_BINDING:
                return arrayBufferBindingValid ? arrayBufferBinding : null;
            case GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING:
                return elementArrayBufferBinding;
            default:
                return null;
        }
    }

    /** 单值 glGetBoolean 仿真（跟踪的五个 enable 能力）；未跟踪或失效返回 null。 */
    Boolean getBoolean(final int pname) {
        final int slot = capSlot(pname);
        if (slot < 0 || !capValid[slot]) {
            return null;
        }
        return capEnabled[slot];
    }

    /** glGetInteger(pname, IntBuffer) 仿真：当前仅 GL_VIEWPORT（4 值）。 */
    boolean getInteger(final int pname, final IntBuffer params) {
        if (pname != GL11.GL_VIEWPORT || !viewportValid || params.remaining() < 4) {
            return false;
        }
        params.put(viewport[0]).put(viewport[1]).put(viewport[2]).put(viewport[3]);
        return true;
    }

    // ------------------------------------------------------------------
    // 失效再同步（getter 回退阻塞通道读回权威值后采入簿记）
    // ------------------------------------------------------------------

    /**
     * glGetBoolean 回退读回后采入：簿记失效（push 时该能力未快照、pop 按位恢复
     * 后标记失效等）时的恢复通道。阻塞通道排空命令流后才采样，读回值即该点的
     * 真实 GL 状态；采入后后续命令流镜像继续保持逐指令一致，失效从「永久回退
     * 阻塞」降为一次性成本。未跟踪能力忽略。
     */
    void adoptBoolean(final int pname, final boolean value) {
        final int slot = capSlot(pname);
        if (slot >= 0) {
            capEnabled[slot] = value;
            capValid[slot] = true;
        }
    }

    /** glGetInteger 回退读回后采入，语义同 {@link #adoptBoolean(int, boolean)}。 */
    void adoptInteger(final int pname, final int value) {
        switch (pname) {
            case GL11.GL_MATRIX_MODE:
                matrixMode = value;
                matrixModeValid = true;
                break;
            case GL14.GL_BLEND_EQUATION:
                blendEquation = value;
                blendEquationValid = true;
                break;
            case GL11.GL_ALPHA_TEST_FUNC:
                alphaFunc = value;
                alphaFuncValid = true;
                break;
            case GL15.GL_ARRAY_BUFFER_BINDING:
                arrayBufferBinding = value;
                arrayBufferBindingValid = true;
                break;
            case GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING:
                elementArrayBufferBinding = value;
                break;
            default:
                break;
        }
    }

    /** glGetInteger(GL_VIEWPORT, buf) 回退读回后采入（4 值），语义同 {@link #adoptBoolean}。 */
    void adoptViewport(final int x, final int y, final int width, final int height) {
        viewport[0] = x;
        viewport[1] = y;
        viewport[2] = width;
        viewport[3] = height;
        viewportValid = true;
    }

    /** glGetFloat(GL_ALPHA_TEST_REF, buf) 回退读回后采入，语义同 {@link #adoptBoolean}。 */
    void adoptAlphaRef(final float value) {
        alphaRef = value;
        alphaRefValid = true;
    }

    /** glGetFloat(pname, FloatBuffer) 仿真：三个矩阵栈顶（16 值）与 GL_ALPHA_TEST_REF（单值）。 */
    boolean getFloat(final int pname, final FloatBuffer params) {
        if (pname == GL11.GL_ALPHA_TEST_REF) {
            if (!alphaRefValid || params.remaining() < 1) {
                return false;
            }
            params.put(alphaRef);
            return true;
        }
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

    private void warnUnpairedPopOnce() {
        if (!unpairedServerPopWarned) {
            unpairedServerPopWarned = true;
            LOGGER.warn("[SSOptimizer] GL attrib 栈下溢（无配对 push 的 pop），"
                    + "按 GL 规范为空操作，状态簿记保持不变");
        }
    }

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

    /** 跟踪能力 → 槽位映射；未跟踪能力返回 -1（簿记忽略，getter 回退阻塞通道）。 */
    private static int capSlot(final int cap) {
        switch (cap) {
            case GL11.GL_TEXTURE_2D:
                return 0;
            case GL11.GL_BLEND:
                return 1;
            case GL11.GL_ALPHA_TEST:
                return 2;
            case GL11.GL_STENCIL_TEST:
                return 3;
            case GL11.GL_SCISSOR_TEST:
                return 4;
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
