package github.kasuminova.ssoptimizer.common.render.spritebatch;

import github.kasuminova.ssoptimizer.bridge.opengl.DisplayListGuard;
import github.kasuminova.ssoptimizer.common.render.engine.DynamicVbo;
import github.kasuminova.ssoptimizer.common.render.runtime.NativeRuntime;
import github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode;
import org.apache.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * {@link SpriteBatch} 实现：流式严格保序合批。
 * <p>
 * 收集期把 quad 顶点用收集时刻的 MVP 矩阵烘焙到裁剪空间，累积进固定容量
 * scratch（单 run 上限 {@link SpriteQuadPacker#MAX_QUADS_PER_DRAW}，超出即先 flush）；
 * flush 时绑定 run 的 (纹理, blend, 混合方程, 渲染目标) 状态，以单位 modelview 绘制环形 VBO 中的顶点
 * （顶点已是裁剪空间，flush 时投影/模型视图均置单位矩阵），随后完整恢复 blend / 纹理绑定 / VBO 绑定 /
 * client state / 矩阵 / 当前颜色（glColor4ub 恢复为该 run 最后一个 sprite 的颜色，
 * 与原版逐 sprite 设置的残留语义一致）。
 * <p>
 * 渲染目标（GL_FRAMEBUFFER_BINDING + GL_VIEWPORT）纳入 run 状态键：FBO 离屏 pass
 * （GraphicsLib 光照 renderForeground/drawNormalMaps 等）内的 sprite 不再被拒收，
 * 目标切换触发 flush 开新 run；flush 绘制前绑定收集时刻捕获的 FBO 与 viewport
 * （而非 flush 时刻上下文），绘制后恢复 flush 入口处的绑定与 viewport，
 * 保证延迟到 pass 切换之后的 flush 仍落在正确的渲染目标上。
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
    /** 当前 run 是否处于扩展状态区（alpha test），以及捕获的状态快照。
     *  注意 stencil 区不参与合批：stencil 缓冲是跨绘制共享的读改写状态，
     *  蒙版写入（NEVER/INCR 等）与读取与 sprite 绘制交错，延迟 flush 无法保证顺序，一律透传。 */
    private boolean currentExtendedState;
    private int     currentAlphaFunc;
    private float   currentAlphaRef;
    /** 当前 run 的渲染目标（收集时刻捕获）：FBO 绑定（0 = 默认帧缓冲）与 viewport。 */
    private int currentFbo;
    private int currentVpX;
    private int currentVpY;
    private int currentVpW;
    private int currentVpH;
    private int  pendingQuads;
    private int  lastR, lastG, lastB, lastA;

    /** 扩展状态捕获期 alpha ref 读取缓冲（渲染线程复用；LWJGL2 glGetFloat 强制要求剩余容量 ≥16）。 */
    private final FloatBuffer alphaRefBuf =
            ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer();

    /** native 收集路径的 run 渲染目标进出缓冲（[0]=FBO，[1..4]=viewport；渲染线程复用）。 */
    private final IntBuffer runTargetBuf =
            ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asIntBuffer();
    /** Java 回退路径的 viewport 读取缓冲（渲染线程复用；LWJGL2 glGetInteger 要求剩余容量 ≥16）。 */
    private final IntBuffer vpBuf =
            ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asIntBuffer();

    private SpriteBatchImpl() {
        String rawEnable = System.getProperty(ENABLE_PROPERTY, "true");
        boolean enable = !"false".equalsIgnoreCase(rawEnable.trim());
        if (enable && RenderThreadMode.isEnabled()) {
            // 分离模式：收集端的 glGet 守卫（矩阵模式/stencil/scissor/FBO/viewport）
            // 每个都是阻塞通道全管线 drain，每 sprite 数次 drain 会打穿管线；
            // 直接禁用收集，sprite 走 SpriteRenderHelper Java 立即路径录制
            // （v1 正确性优先，状态仿真接入后再恢复收集）。
            LOGGER.info("[SSOptimizer] 渲染线程分离模式：禁用 SpriteBatch 收集"
                    + "（收集端 glGet 守卫在分离模式下为全管线 drain）");
            enable = false;
        }
        this.enabled = enable;
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
        if (!SpriteBatchStats.isInCombatScope() || texClamp || DisplayListGuard.isBuildingList()) {
            // 拒绝收集：先 flush 已有批次，保证后续原版绘制的相对顺序不变
            flushPending();
            return false;
        }

        long key = SpriteGroupStats.key(textureId, blendSrc, blendDest);
        if (key != currentKey) {
            doFlush();
        }
        if (pendingQuads >= SpriteQuadPacker.MAX_QUADS_PER_DRAW) {
            doFlush();
        }
        if (vertexScratch == null) {
            vertexScratch = ByteBuffer.allocateDirect(VERTEX_SCRATCH_BYTES).order(ByteOrder.nativeOrder());
            indexScratch = ByteBuffer.allocateDirect(INDEX_SCRATCH_BYTES).order(ByteOrder.nativeOrder());
        }

        // 混合方程也是 run 分组键的一部分：CombatEntityPluginWithParticles 的暗色层
        // （现实干扰器弹体等负片粒子）会用 GL14.glBlendEquation(GL_FUNC_REVERSE_SUBTRACT)
        // 包裹整段渲染，延迟 flush 时必须按收集时刻的方程绘制，否则暗色粒子退化为加法发光。
        // alpha test 同理入键（扩展状态区），flush 时回放；stencil 区一律透传。
        // 渲染目标（FBO + viewport）同样入键：native 在 pendingQuads>0 时与 runTargetBuf
        // 中的期望值比较，不一致返回 RESULT_STATE_MISMATCH 走 flush 重试
        int blendEquation;
        boolean extendedState;
        int submitFbo;
        int submitVpX;
        int submitVpY;
        int submitVpW;
        int submitVpH;
        if (NativeRuntime.isGlReady()) {
            int expected = pendingQuads > 0 ? currentBlendEquation : -1;
            int requireExtended = pendingQuads > 0 && currentExtendedState ? 1 : 0;
            int result = SpriteBatchNative.nativeSubmit(vertexScratch, indexScratch, runTargetBuf,
                    pendingQuads, expected, requireExtended,
                    posX, posY, width, height, centerX, centerY, angle,
                    r, g, b, a, texX, texY, texWidth, texHeight);
            if (result == SpriteBatchNative.RESULT_EQUATION_MISMATCH
                    || result == SpriteBatchNative.RESULT_STATE_MISMATCH) {
                // 方程/扩展状态/渲染目标切换：flush 后空 run 重试（不检查方程与扩展状态，
                // native 重新捕获渲染目标写入 runTargetBuf）
                flushPending();
                result = SpriteBatchNative.nativeSubmit(vertexScratch, indexScratch, runTargetBuf,
                        0, -1, 0,
                        posX, posY, width, height, centerX, centerY, angle,
                        r, g, b, a, texX, texY, texWidth, texHeight);
            }
            if (result == SpriteBatchNative.RESULT_INVALID_BUFFER) {
                throw new IllegalStateException("SpriteBatch scratch 必须是 direct ByteBuffer");
            }
            if (result == SpriteBatchNative.RESULT_EXTENDED_STATE) {
                // alpha test 区（低频）：native 未写入，Java 侧捕获状态快照并打包
                blendEquation = GL11.glGetInteger(GL14.GL_BLEND_EQUATION);
                if (pendingQuads > 0 && blendEquation != currentBlendEquation) {
                    flushPending();
                }
                captureAlphaState();
                packQuadJava(posX, posY, width, height, centerX, centerY, angle,
                        r, g, b, a, texX, texY, texWidth, texHeight);
                extendedState = true;
            } else if (result < 0) {
                // stencil/scissor 区（蒙版读写与 sprite 绘制交错，不可延迟）
                // 与矩阵模式非常规时必须走原版立即路径
                flushPending();
                return false;
            } else {
                blendEquation = result;
                vertexScratch.position(vertexScratch.position() + 4 * SpriteQuadPacker.VERTEX_BYTES);
                indexScratch.position(indexScratch.position() + 12);
                extendedState = false;
            }
            // 收集时刻的渲染目标由 native 写入 runTargetBuf
            // （pendingQuads>0 时已与 run 比较一致，值与当前 run 相同）
            submitFbo = runTargetBuf.get(0);
            submitVpX = runTargetBuf.get(1);
            submitVpY = runTargetBuf.get(2);
            submitVpW = runTargetBuf.get(3);
            submitVpH = runTargetBuf.get(4);
        } else {
            if (GL11.glGetInteger(GL11.GL_MATRIX_MODE) != GL11.GL_MODELVIEW
                    || GL11.glGetBoolean(GL11.GL_STENCIL_TEST)
                    || GL11.glGetBoolean(GL11.GL_SCISSOR_TEST)) {
                flushPending();
                return false;
            }
            // FBO 离屏 pass 不再拒绝：捕获渲染目标入 run 状态键，flush 时回放
            submitFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
            vpBuf.clear();
            GL11.glGetInteger(GL11.GL_VIEWPORT, vpBuf);
            submitVpX = vpBuf.get(0);
            submitVpY = vpBuf.get(1);
            submitVpW = vpBuf.get(2);
            submitVpH = vpBuf.get(3);
            if (pendingQuads > 0 && (submitFbo != currentFbo
                    || submitVpX != currentVpX || submitVpY != currentVpY
                    || submitVpW != currentVpW || submitVpH != currentVpH)) {
                // 渲染目标切换（进入/离开 FBO 离屏 pass）：flush 后开启新 run
                flushPending();
            }
            blendEquation = GL11.glGetInteger(GL14.GL_BLEND_EQUATION);
            if (pendingQuads > 0 && blendEquation != currentBlendEquation) {
                flushPending();
            }
            extendedState = GL11.glGetBoolean(GL11.GL_ALPHA_TEST);
            if (extendedState) {
                captureAlphaState();
            } else if (pendingQuads > 0 && currentExtendedState) {
                // 离开扩展状态区：普通 sprite 不能并入扩展 run
                flushPending();
            }
            packQuadJava(posX, posY, width, height, centerX, centerY, angle,
                    r, g, b, a, texX, texY, texWidth, texHeight);
        }
        if (pendingQuads == 0) {
            // 新 run 开始：记录组键状态与渲染目标
            currentKey = key;
            currentTexture = textureId;
            currentBlendSrc = blendSrc;
            currentBlendDest = blendDest;
            currentBlendEquation = blendEquation;
            currentExtendedState = extendedState;
            currentFbo = submitFbo;
            currentVpX = submitVpX;
            currentVpY = submitVpY;
            currentVpW = submitVpW;
            currentVpH = submitVpH;
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
        // 外部 barrier（非 sprite 绘制边界/拒绝路径/作用域结束）：统计上关闭当前段
        if (SpriteBatchStats.isEnabled()) {
            SpriteBatchStats.onFlushBarrier();
        }
        doFlush();
    }

    /** 组切换/容量满等内部 flush：不构成 barrier（段内归并的可优化对象正是这类 flush）。 */
    private void doFlush() {
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

        if (NativeRuntime.isGlReady()) {
            // native 单次 JNI 完成状态回放 + 绘制 + 恢复（约 25 次 LWJGL 调用折叠为一次跨界）；
            // FBO 绑定与 viewport 的保存/回放/恢复同样在 native 内完成（LWJGL2 StateTracker
            // 不跟踪这两项，native 恢复 flush 入口值即保证真实 GL 状态正确）
            SpriteBatchNative.nativeFlush(vertexVbo.getBufferId(), vertexBase,
                    indexVbo.getBufferId(), indexBase, pendingQuads,
                    currentTexture, currentBlendSrc, currentBlendDest, currentBlendEquation,
                    currentFbo, currentVpX, currentVpY, currentVpW, currentVpH,
                    currentExtendedState, currentAlphaFunc, currentAlphaRef,
                    lastR, lastG, lastB, lastA,
                    prevMatrixMode);
            // VBO 绑定必须经 LWJGL 恢复：DynamicVbo.write 经 LWJGL 绑定（StateTracker 已记录
            // 合批 VBO），native 经 glad 的恢复不会更新 tracker，不重绑会导致后续 LWJGL
            // Buffer 绘制（如 Contrail/Particle 的 glColorPointer）误判数组 VBO 仍启用
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, prevElementBuffer);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevArrayBuffer);
            pendingQuads = 0;
            currentKey = -1;
            return;
        }

        // flush 入口处的渲染目标：绘制后必须恢复（绘制期间绑定的是收集时刻捕获的目标）
        int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        vpBuf.clear();
        GL11.glGetInteger(GL11.GL_VIEWPORT, vpBuf);
        int prevVpX = vpBuf.get(0);
        int prevVpY = vpBuf.get(1);
        int prevVpW = vpBuf.get(2);
        int prevVpH = vpBuf.get(3);

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_TEXTURE_BIT | GL11.GL_CURRENT_BIT);
        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        try {
            // 渲染目标回放收集时刻的捕获值：flush 可能延迟到 pass 切换之后触发
            // （组切换 flush 发生在下一次 submit 时），必须落回收集时刻的 FBO 与 viewport
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, currentFbo);
            GL11.glViewport(currentVpX, currentVpY, currentVpW, currentVpH);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(currentBlendSrc, currentBlendDest);
            // 恢复收集时刻的混合方程（GL_COLOR_BUFFER_BIT 的 popAttrib 负责复原调用方方程）
            GL14.glBlendEquation(currentBlendEquation);
            // stencil 区一律透传不参与合批，但 flush 可能由 stencil 区内的 guard 拒绝触发
            // （落在调用方 stencil 开启区间），必须显式关闭避免波及当前 run
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            // 回放收集时刻的 alpha test 状态快照（扩展状态区 run）
            if (currentExtendedState) {
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
            // FBO 绑定与 viewport 不在 pushAttrib 覆盖范围（本段未含 GL_VIEWPORT_BIT），显式恢复
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
            GL11.glViewport(prevVpX, prevVpY, prevVpW, prevVpH);
            // 原版逐 sprite glColor4ub 的残留语义：当前颜色 = 最后一个 sprite 的颜色
            GL11.glColor4ub((byte) lastR, (byte) lastG, (byte) lastB, (byte) lastA);
        }

        pendingQuads = 0;
        currentKey = -1;
    }

    /**
     * 捕获收集时刻的 alpha test 状态快照；若与当前 run 不一致则先 flush。
     * 仅扩展状态区（低频）调用。
     */
    private void captureAlphaState() {
        int aFunc = GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC);
        alphaRefBuf.clear();
        GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF, alphaRefBuf);
        float aRef = alphaRefBuf.get(0);
        if (pendingQuads > 0 && (!currentExtendedState
                || aFunc != currentAlphaFunc || aRef != currentAlphaRef)) {
            // alpha 状态切换：flush 后开启新 run
            flushPending();
        }
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
