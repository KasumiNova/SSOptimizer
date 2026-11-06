package github.kasuminova.ssoptimizer.common.render.spritebatch;

import com.fs.graphics.util.GLListManager;
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

    private final FloatBuffer mvBuf =
            ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final FloatBuffer pjBuf =
            ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final float[] mv  = new float[16];
    private final float[] pj  = new float[16];
    private final float[] mvp = new float[16];

    private long currentKey = -1;
    private int  currentTexture;
    private int  currentBlendSrc;
    private int  currentBlendDest;
    private int  currentBlendEquation;
    // stencil 的读写与其他绘制交错，延迟提交无法保证顺序，必须透传。
    private boolean currentExtendedState;
    private int     currentAlphaFunc;
    private float   currentAlphaRef;
    private int currentFbo;
    private int currentVpX;
    private int currentVpY;
    private int currentVpW;
    private int currentVpH;
    private int  pendingQuads;
    private int  lastR, lastG, lastB, lastA;

    private final FloatBuffer alphaRefBuf =
            ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer();

    private final IntBuffer runTargetBuf =
            ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asIntBuffer();
    private final IntBuffer vpBuf =
            ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asIntBuffer();

    private SpriteBatchImpl() {
        String rawEnable = System.getProperty(ENABLE_PROPERTY, "true");
        boolean enable = !"false".equalsIgnoreCase(rawEnable.trim());
        if (enable && RenderThreadMode.isEnabled()) {
            LOGGER.info("[SSOptimizer] 渲染线程分离模式：SpriteBatch 收集禁用"
                    + "（Java 回退 flush 实测负优化，immediate 路径已优化）");
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
        if (!SpriteBatchStats.isInCombatScope() || texClamp || GLListManager.buildingList) {
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
                blendEquation = GL11.glGetInteger(GL14.GL_BLEND_EQUATION);
                if (pendingQuads > 0 && blendEquation != currentBlendEquation) {
                    flushPending();
                }
                captureAlphaState();
                packQuadJava(posX, posY, width, height, centerX, centerY, angle,
                        r, g, b, a, texX, texY, texWidth, texHeight);
                extendedState = true;
            } else if (result < 0) {
                flushPending();
                return false;
            } else {
                blendEquation = result;
                vertexScratch.position(vertexScratch.position() + 4 * SpriteQuadPacker.VERTEX_BYTES);
                indexScratch.position(indexScratch.position() + 12);
                extendedState = false;
            }
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
                flushPending();
            }
            packQuadJava(posX, posY, width, height, centerX, centerY, angle,
                    r, g, b, a, texX, texY, texWidth, texHeight);
        }
        if (pendingQuads == 0) {
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
        if (SpriteBatchStats.isEnabled()) {
            SpriteBatchStats.onFlushBarrier();
        }
        doFlush();
    }

    private void doFlush() {
        if (pendingQuads == 0) {
            return;
        }
        if (vertexVbo == null) {
            vertexVbo = new DynamicVbo(GL15.GL_ARRAY_BUFFER, VERTEX_VBO_CAPACITY);
            indexVbo = new DynamicVbo(GL15.GL_ELEMENT_ARRAY_BUFFER, INDEX_VBO_CAPACITY);
            LOGGER.info("[SSOptimizer] Sprite 合批 VBO 已初始化");
        }

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
            SpriteBatchNative.nativeFlush(vertexVbo.getBufferId(), vertexBase,
                    indexVbo.getBufferId(), indexBase, pendingQuads,
                    currentTexture, currentBlendSrc, currentBlendDest, currentBlendEquation,
                    currentFbo, currentVpX, currentVpY, currentVpW, currentVpH,
                    currentExtendedState, currentAlphaFunc, currentAlphaRef,
                    lastR, lastG, lastB, lastA,
                    prevMatrixMode);
            // glad 不更新 LWJGL2 StateTracker，VBO 绑定必须经 LWJGL 恢复。
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, prevElementBuffer);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevArrayBuffer);
            pendingQuads = 0;
            currentKey = -1;
            return;
        }

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
            // 延迟 flush 必须回放收集时的 FBO 与 viewport，并在结束后恢复入口状态。
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, currentFbo);
            GL11.glViewport(currentVpX, currentVpY, currentVpW, currentVpH);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(currentBlendSrc, currentBlendDest);
            GL14.glBlendEquation(currentBlendEquation);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            if (currentExtendedState) {
                GL11.glEnable(GL11.GL_ALPHA_TEST);
                GL11.glAlphaFunc(currentAlphaFunc, currentAlphaRef);
            } else {
                GL11.glDisable(GL11.GL_ALPHA_TEST);
            }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, currentTexture);

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
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
            GL11.glViewport(prevVpX, prevVpY, prevVpW, prevVpH);
            GL11.glColor4ub((byte) lastR, (byte) lastG, (byte) lastB, (byte) lastA);
        }

        pendingQuads = 0;
        currentKey = -1;
    }

    private void captureAlphaState() {
        int aFunc = GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC);
        alphaRefBuf.clear();
        GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF, alphaRefBuf);
        float aRef = alphaRefBuf.get(0);
        if (pendingQuads > 0 && (!currentExtendedState
                || aFunc != currentAlphaFunc || aRef != currentAlphaRef)) {
            flushPending();
        }
        currentAlphaFunc = aFunc;
        currentAlphaRef = aRef;
    }

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
