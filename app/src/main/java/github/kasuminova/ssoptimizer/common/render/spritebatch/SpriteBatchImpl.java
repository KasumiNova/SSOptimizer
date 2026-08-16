package github.kasuminova.ssoptimizer.common.render.spritebatch;

import com.fs.graphics.util.GLListManager;
import github.kasuminova.ssoptimizer.common.render.engine.DynamicVbo;
import org.apache.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * {@link SpriteBatch} 实现：流式严格保序合批。
 * <p>
 * 收集期把 quad 顶点用收集时刻的 modelview 矩阵烘焙到观察空间，累积进固定容量
 * scratch（单 run 上限 {@link SpriteQuadPacker#MAX_QUADS_PER_DRAW}，超出即先 flush）；
 * flush 时绑定 run 的 (纹理, blend) 状态，以单位 modelview 绘制环形 VBO 中的顶点
 * （顶点已是观察空间，避免重复变换），随后完整恢复 blend / 纹理绑定 / VBO 绑定 /
 * client state / 矩阵 / 当前颜色（glColor4ub 恢复为该 run 最后一个 sprite 的颜色，
 * 与原版逐 sprite 设置的残留语义一致）。
 * <p>
 * 开关：{@code -Dssoptimizer.render.spritebatch.enable}（默认 true，false 时
 * {@link #submitIfActive} 仅一次布尔判断直接放行原版路径）。
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
    private final float[] mv = new float[16];

    // 当前 run 状态
    private long currentKey = -1;
    private int  currentTexture;
    private int  currentBlendSrc;
    private int  currentBlendDest;
    private int  pendingQuads;
    private int  lastR, lastG, lastB, lastA;

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
        if (!SpriteBatchStats.isInCombatScope() || texClamp
                || GLListManager.buildingList
                || GL11.glGetInteger(GL11.GL_MATRIX_MODE) != GL11.GL_MODELVIEW
                || GL11.glGetBoolean(GL11.GL_STENCIL_TEST)
                || GL11.glGetBoolean(GL11.GL_SCISSOR_TEST)) {
            // 拒绝收集：先 flush 已有批次，保证后续原版绘制的相对顺序不变
            flushPending();
            return false;
        }

        long key = SpriteGroupStats.key(textureId, blendSrc, blendDest);
        if (key != currentKey) {
            flushPending();
            currentKey = key;
            currentTexture = textureId;
            currentBlendSrc = blendSrc;
            currentBlendDest = blendDest;
        }
        if (pendingQuads >= SpriteQuadPacker.MAX_QUADS_PER_DRAW) {
            flushPending();
        }
        if (vertexScratch == null) {
            vertexScratch = ByteBuffer.allocateDirect(VERTEX_SCRATCH_BYTES).order(ByteOrder.nativeOrder());
            indexScratch = ByteBuffer.allocateDirect(INDEX_SCRATCH_BYTES).order(ByteOrder.nativeOrder());
        }

        mvBuf.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, mvBuf);
        for (int i = 0; i < 16; i++) {
            mv[i] = mvBuf.get(i);
        }
        SpriteQuadPacker.packQuad(vertexScratch, indexScratch, pendingQuads * 4, mv,
                posX, posY, width, height, centerX, centerY, angle,
                r, g, b, a, texX, texY, texWidth, texHeight);
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
                | GL11.GL_TEXTURE_BIT | GL11.GL_CURRENT_BIT);
        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        try {
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(currentBlendSrc, currentBlendDest);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, currentTexture);

            // 顶点已烘焙到观察空间：以单位 modelview 绘制，避免 flush 时刻矩阵栈的二次变换
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
            if (prevMatrixMode != GL11.GL_MODELVIEW) {
                GL11.glMatrixMode(prevMatrixMode);
            }
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
}
