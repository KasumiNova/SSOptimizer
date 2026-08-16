package github.kasuminova.ssoptimizer.common.render.spritebatch;

import com.fs.graphics.util.GLListManager;
import github.kasuminova.ssoptimizer.common.render.engine.DynamicVbo;
import github.kasuminova.ssoptimizer.common.render.runtime.NativeRuntime;
import org.apache.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * {@link SpriteBatch} 实现：流式严格保序合批。
 * <p>
 * 收集期把 quad 顶点用收集时刻的 MVP 矩阵烘焙到裁剪空间，累积进固定容量
 * scratch（单 run 上限 {@link SpriteQuadPacker#MAX_QUADS_PER_DRAW}，超出即先 flush）；
 * flush 时绑定 run 的 (纹理, blend, 混合方程) 状态，以单位 modelview 绘制环形 VBO 中的顶点
 * （顶点已是裁剪空间，flush 时投影/模型视图均置单位矩阵），随后完整恢复 blend / 纹理绑定 / VBO 绑定 /
 * client state / 矩阵 / 当前颜色（glColor4ub 恢复为该 run 最后一个 sprite 的颜色，
 * 与原版逐 sprite 设置的残留语义一致）。
 * <p>
 * 开关：{@code -Dssoptimizer.render.spritebatch.enable}（默认 true，false 时
 * {@link #submitIfActive} 仅一次布尔判断直接放行原版路径）。
 * <p>
 * 收集路径优先走 {@link SpriteBatchNative} 的单次 JNI（guard + 矩阵读取 + 打包合一），
 * native 库缺失时回退 Java 打包路径，两者语义一致。
 */
public final class SpriteBatchImpl implements SpriteBatch {
    private static final Logger LOGGER = Logger.getLogger(SpriteBatchImpl.class);

    public static final String ENABLE_PROPERTY = "ssoptimizer.render.spritebatch.enable";

    private static final SpriteBatchImpl INSTANCE = new SpriteBatchImpl();

    private static final int VERTEX_SCRATCH_BYTES = SpriteQuadPacker.MAX_QUADS_PER_DRAW * 4
            * SpriteQuadPacker.VERTEX_BYTES;
    private static final int INDEX_SCRATCH_BYTES  = SpriteQuadPacker.MAX_QUADS_PER_DRAW * 6 * 2;
    private static final int VERTEX_VBO_CAPACITY  = 1024 * 1024;
    private static final int INDEX_VBO_CAPACITY   = 256 * 1024;
    /** LWJGL2 GL11 未暴露的 attrib 位：0x0400（保存/恢复 stencil func/op/mask 等状态）。 */
    private static final int GL_STENCIL_TEST_BIT  = 0x0400;

    private final boolean enabled;

    private DynamicVbo vertexVbo;
    private DynamicVbo indexVbo;
    private ByteBuffer vertexScratch;
    private ByteBuffer indexScratch;

    /** 收集期 modelview 读取缓冲（渲染线程复用）。 */
    private final FloatBuffer mvBuf =
            ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer();
    /** 收集期 projection 读取缓冲（渲染线程复用）。 */
    private final FloatBuffer pjBuf =
            ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final float[] mv  = new float[16];
    private final float[] pj  = new float[16];
    /** 合成后的 MVP 2D 仿射（只用槽位 0/1/4/5/12/13）。 */
    private final float[] mvp = new float[16];

    // 当前 run 状态
    private long currentKey = -1;
    private int  currentTexture;
    private int  currentBlendSrc;
    private int  currentBlendDest;
    private int  currentBlendEquation;
    /** 当前 run 是否处于扩展状态区（stencil / alpha test），以及捕获的状态快照。 */
    private boolean currentExtendedState;
    private boolean currentStencilEnabled;
    private int     currentStencilFunc;
    private int     currentStencilRef;
    private int     currentStencilMask;
    private int     currentStencilOpSfail;
    private int     currentStencilOpDpfail;
    private int     currentStencilOpDppass;
    private boolean currentAlphaEnabled;
    private int     currentAlphaFunc;
    private float   currentAlphaRef;
    private int  pendingQuads;
    private int  lastR, lastG, lastB, lastA;

    /** 扩展状态捕获期 alpha ref 读取缓冲（渲染线程复用）。 */
    private final FloatBuffer alphaRefBuf =
            ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asFloatBuffer();

    private SpriteBatchImpl() {
        String rawEnable = System.getProperty(ENABLE_PROPERTY, "true");
        this.enabled = !"false".equalsIgnoreCase(rawEnable.trim());
    }

    public static SpriteBatchImpl getInstance() {
        return INSTANCE;
    }

    @Override
    public boolean submitIfActive(int textureId,
                                  float posX, float posY, float width, float height,
                                  float centerX, float centerY, float angle,
                                  int r, int g, int b, int a,
                                  int blendSrc, int blendDest,
                                  float texX, float texY, float texWidth, float texHeight,
                                  boolean texClamp) {
        if (!enabled) {
            return false;
        }
        // 免费检查先行；GL 状态检查由 native 单次 JNI 完成（native 缺失时走 Java 路径）
        if (!SpriteBatchStats.isInCombatScope() || texClamp || GLListManager.buildingList) {
            // 拒绝收集：先 flush 已有批次，保证后续原版绘制的相对顺序不变
            flushPending();
            return false;
        }

        long key = SpriteGroupStats.key(textureId, blendSrc, blendDest);
        if (key != currentKey) {
            flushPending();
        }
        if (pendingQuads >= SpriteQuadPacker.MAX_QUADS_PER_DRAW) {
            flushPending();
        }
        if (vertexScratch == null) {
            vertexScratch = ByteBuffer.allocateDirect(VERTEX_SCRATCH_BYTES).order(ByteOrder.nativeOrder());
            indexScratch = ByteBuffer.allocateDirect(INDEX_SCRATCH_BYTES).order(ByteOrder.nativeOrder());
        }

        // 混合方程也是 run 分组键的一部分：CombatEntityPluginWithParticles 的暗色层
        // （现实干扰器弹体等负片粒子）会用 GL14.glBlendEquation(GL_FUNC_REVERSE_SUBTRACT)
        // 包裹整段渲染，延迟 flush 时必须按收集时刻的方程绘制，否则暗色粒子退化为加法发光。
        // stencil / alpha test 同理构成扩展状态区（武器损伤渲染 beginDamageRender 等），
        // 状态快照入 run 键，flush 时回放
        int blendEquation;
        boolean extendedState;
        if (NativeRuntime.isLoaded()) {
            int expected = pendingQuads > 0 ? currentBlendEquation : -1;
            int requireExtended = pendingQuads > 0 && currentExtendedState ? 1 : 0;
            int result = SpriteBatchNative.nativeSubmit(vertexScratch, indexScratch,
                    pendingQuads, expected, requireExtended,
                    posX, posY, width, height, centerX, centerY, angle,
                    r, g, b, a, texX, texY, texWidth, texHeight);
            if (result == SpriteBatchNative.RESULT_EQUATION_MISMATCH
                    || result == SpriteBatchNative.RESULT_STATE_MISMATCH) {
                // 方程/扩展状态切换：flush 后空 run 重试（不检查方程与扩展状态）
                flushPending();
                result = SpriteBatchNative.nativeSubmit(vertexScratch, indexScratch, 0, -1, 0,
                        posX, posY, width, height, centerX, centerY, angle,
                        r, g, b, a, texX, texY, texWidth, texHeight);
            }
            if (result == SpriteBatchNative.RESULT_INVALID_BUFFER) {
                throw new IllegalStateException("SpriteBatch scratch 必须是 direct ByteBuffer");
            }
            if (result == SpriteBatchNative.RESULT_EXTENDED_STATE) {
                // stencil/alpha 区（低频）：native 未写入，Java 侧捕获状态快照并打包
                blendEquation = GL11.glGetInteger(GL14.GL_BLEND_EQUATION);
                if (pendingQuads > 0 && blendEquation != currentBlendEquation) {
                    flushPending();
                }
                captureExtendedState();
                packQuadJava(posX, posY, width, height, centerX, centerY, angle,
                        r, g, b, a, texX, texY, texWidth, texHeight);
                extendedState = true;
            } else if (result < 0) {
                // FBO 绑定非 0 表示模组/游戏的离屏 pass（GraphicsLib 法线/材质图等），
                // 其投影与渲染目标均不同，必须走原版立即路径
                flushPending();
                return false;
            } else {
                blendEquation = result;
                vertexScratch.position(vertexScratch.position() + 4 * SpriteQuadPacker.VERTEX_BYTES);
                indexScratch.position(indexScratch.position() + 12);
                extendedState = false;
            }
        } else {
            if (GL11.glGetInteger(GL11.GL_MATRIX_MODE) != GL11.GL_MODELVIEW
                    || GL11.glGetBoolean(GL11.GL_SCISSOR_TEST)
                    || GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING) != 0) {
                flushPending();
                return false;
            }
            blendEquation = GL11.glGetInteger(GL14.GL_BLEND_EQUATION);
            if (pendingQuads > 0 && blendEquation != currentBlendEquation) {
                flushPending();
            }
            extendedState = GL11.glGetBoolean(GL11.GL_STENCIL_TEST) || GL11.glGetBoolean(GL11.GL_ALPHA_TEST);
            if (extendedState) {
                captureExtendedState();
            } else if (pendingQuads > 0 && currentExtendedState) {
                // 离开扩展状态区：普通 sprite 不能并入扩展 run
                flushPending();
            }
            packQuadJava(posX, posY, width, height, centerX, centerY, angle,
                    r, g, b, a, texX, texY, texWidth, texHeight);
        }
        if (pendingQuads == 0) {
            // 新 run 开始：记录组键状态
            currentKey = key;
            currentTexture = textureId;
            currentBlendSrc = blendSrc;
            currentBlendDest = blendDest;
            currentBlendEquation = blendEquation;
            currentExtendedState = extendedState;
        }
        pendingQuads++;
        lastR = r;
        lastG = g;
        lastB = b;
        lastA = a;
        return true;
    }

    @Override
    public void flushPending() {
        if (pendingQuads == 0) {
            return;
        }
        if (vertexVbo == null) {
            vertexVbo = new DynamicVbo(GL15.GL_ARRAY_BUFFER, VERTEX_VBO_CAPACITY);
            indexVbo = new DynamicVbo(GL15.GL_ELEMENT_ARRAY_BUFFER, INDEX_VBO_CAPACITY);
            LOGGER.info("[SSOptimizer] Sprite 合批 VBO 已初始化");
        }

        // 必须先捕获调用方原始绑定：DynamicVbo.write 会绑定自身 VBO 且不解除
        int prevMatrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        int prevArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int prevElementBuffer = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);

        vertexScratch.flip();
        indexScratch.flip();
        int vertexBase = vertexVbo.write(vertexScratch);
        int indexBase = indexVbo.write(indexScratch);
        vertexScratch.clear();
        indexScratch.clear();

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_TEXTURE_BIT | GL11.GL_CURRENT_BIT | GL_STENCIL_TEST_BIT);
        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        try {
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(currentBlendSrc, currentBlendDest);
            // 恢复收集时刻的混合方程（GL_COLOR_BUFFER_BIT 的 popAttrib 负责复原调用方方程）
            GL14.glBlendEquation(currentBlendEquation);
            // 回放收集时刻的 stencil / alpha 状态快照（扩展状态区 run）；
            // 普通 run 显式关闭，避免 flush 落在调用方 stencil/alpha 开启区间内时被波及
            if (currentStencilEnabled) {
                GL11.glEnable(GL11.GL_STENCIL_TEST);
                GL11.glStencilFunc(currentStencilFunc, currentStencilRef, currentStencilMask);
                GL11.glStencilOp(currentStencilOpSfail, currentStencilOpDpfail, currentStencilOpDppass);
            } else {
                GL11.glDisable(GL11.GL_STENCIL_TEST);
            }
            if (currentAlphaEnabled) {
                GL11.glEnable(GL11.GL_ALPHA_TEST);
                GL11.glAlphaFunc(currentAlphaFunc, currentAlphaRef);
            } else {
                GL11.glDisable(GL11.GL_ALPHA_TEST);
            }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, currentTexture);

            // 顶点已烘焙到裁剪空间：投影与模型视图均置单位矩阵，与 flush 时刻的矩阵栈完全解耦
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPushMatrix();
            GL11.glLoadIdentity();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPushMatrix();
            GL11.glLoadIdentity();

            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
            vertexVbo.bind();
            GL11.glVertexPointer(2, GL11.GL_FLOAT, SpriteQuadPacker.VERTEX_BYTES, (long) vertexBase);
            GL11.glTexCoordPointer(2, GL11.GL_FLOAT, SpriteQuadPacker.VERTEX_BYTES, (long) vertexBase + 8);
            GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, SpriteQuadPacker.VERTEX_BYTES, (long) vertexBase + 16);
            indexVbo.bind();
            GL11.glDrawElements(GL11.GL_TRIANGLES, pendingQuads * 6, GL11.GL_UNSIGNED_SHORT, indexBase);

            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(prevMatrixMode);
        } finally {
            GL11.glPopClientAttrib();
            GL11.glPopAttrib();
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, prevElementBuffer);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevArrayBuffer);
            // 原版逐 sprite glColor4ub 的残留语义：当前颜色 = 最后一个 sprite 的颜色
            GL11.glColor4ub((byte) lastR, (byte) lastG, (byte) lastB, (byte) lastA);
        }

        pendingQuads = 0;
        currentKey = -1;
    }

    /**
     * 捕获收集时刻的 stencil / alpha test 状态快照；若与当前 run 不一致则先 flush。
     * 仅扩展状态区（低频）调用。
     */
    private void captureExtendedState() {
        boolean stencil = GL11.glGetBoolean(GL11.GL_STENCIL_TEST);
        boolean alpha = GL11.glGetBoolean(GL11.GL_ALPHA_TEST);
        int sFunc = 0, sRef = 0, sMask = 0, sOpSfail = 0, sOpDpfail = 0, sOpDppass = 0;
        int aFunc = 0;
        float aRef = 0.0f;
        if (stencil) {
            sFunc = GL11.glGetInteger(GL11.GL_STENCIL_FUNC);
            sRef = GL11.glGetInteger(GL11.GL_STENCIL_REF);
            sMask = GL11.glGetInteger(GL11.GL_STENCIL_VALUE_MASK);
            sOpSfail = GL11.glGetInteger(GL11.GL_STENCIL_FAIL);
            sOpDpfail = GL11.glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_FAIL);
            sOpDppass = GL11.glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_PASS);
        }
        if (alpha) {
            aFunc = GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC);
            alphaRefBuf.clear();
            GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF, alphaRefBuf);
            aRef = alphaRefBuf.get(0);
        }
        if (pendingQuads > 0 && (!currentExtendedState
                || stencil != currentStencilEnabled
                || sFunc != currentStencilFunc || sRef != currentStencilRef || sMask != currentStencilMask
                || sOpSfail != currentStencilOpSfail || sOpDpfail != currentStencilOpDpfail
                || sOpDppass != currentStencilOpDppass
                || alpha != currentAlphaEnabled
                || aFunc != currentAlphaFunc || aRef != currentAlphaRef)) {
            // 扩展状态切换（如武器损伤渲染中 ALWAYS/INCR → EQUAL/KEEP）：flush 后开启新 run
            flushPending();
        }
        currentStencilEnabled = stencil;
        currentStencilFunc = sFunc;
        currentStencilRef = sRef;
        currentStencilMask = sMask;
        currentStencilOpSfail = sOpSfail;
        currentStencilOpDpfail = sOpDpfail;
        currentStencilOpDppass = sOpDppass;
        currentAlphaEnabled = alpha;
        currentAlphaFunc = aFunc;
        currentAlphaRef = aRef;
    }

    /** Java 打包路径：读取收集时刻矩阵并追加一个烘焙到裁剪空间的 quad（native 缺失或扩展状态区使用）。 */
    private void packQuadJava(float posX, float posY, float width, float height,
                              float centerX, float centerY, float angle,
                              int r, int g, int b, int a,
                              float texX, float texY, float texWidth, float texHeight) {
        mvBuf.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, mvBuf);
        for (int i = 0; i < 16; i++) {
            mv[i] = mvBuf.get(i);
        }
        pjBuf.clear();
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, pjBuf);
        for (int i = 0; i < 16; i++) {
            pj[i] = pjBuf.get(i);
        }
        // 合成投影×模型视图的 2D 仿射（列主序槽位 0/1/4/5/12/13），
        // 顶点直接烘焙到裁剪空间，flush 时两个矩阵均置单位矩阵，与矩阵栈完全解耦
        mvp[0] = pj[0] * mv[0] + pj[4] * mv[1];
        mvp[1] = pj[1] * mv[0] + pj[5] * mv[1];
        mvp[4] = pj[0] * mv[4] + pj[4] * mv[5];
        mvp[5] = pj[1] * mv[4] + pj[5] * mv[5];
        mvp[12] = pj[0] * mv[12] + pj[4] * mv[13] + pj[12];
        mvp[13] = pj[1] * mv[12] + pj[5] * mv[13] + pj[13];
        SpriteQuadPacker.packQuad(vertexScratch, indexScratch, pendingQuads * 4, mvp,
                posX, posY, width, height, centerX, centerY, angle,
                r, g, b, a, texX, texY, texWidth, texHeight);
    }
}
