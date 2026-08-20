package github.kasuminova.ssoptimizer.common.render.hud;

import com.fs.graphics.Sprite;
import com.fs.graphics.TextureManager;
import com.fs.graphics.TextureObject;
import github.kasuminova.ssoptimizer.common.render.spritebatch.SpriteBatch;
import org.apache.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.opengl.EXTFramebufferObject;

import java.awt.Color;
import java.nio.IntBuffer;

/**
 * {@link RadarCompositeCache} 实现：单张 2048² FBO 纹理划分为 128² 单元格，
 * 每个图标占两个单元格（成员/幽灵位变体），按需烘焙、超时回收。
 * <p>
 * 烘焙在 HUD 帧内惰性触发：开始前先 {@link SpriteBatch#flushPending()}
 * （必须在改动 FBO/视口/矩阵前落盘待绘批次，否则残批会被画进 FBO 单元格），
 * 结束后完整恢复 FBO 绑定/视口/矩阵/颜色掩码/裁剪状态。
 * 烘焙期开启 scissor，portrait sprite 仍被合批 guard 拒绝走立即路径；
 * 即便烘焙区未来不再开 scissor，渲染目标（FBO + viewport）已纳入合批 run 状态键，
 * 收集与 flush 均按收集时刻的目标回放，绘制仍落在正确的 FBO 单元格内。
 * <p>
 * 与原版的行为差异（均已评估可接受，截图验证）：
 * <ul>
 *   <li>原版最终把图标区帧缓冲 alpha 覆写为 1（alpha-only 矩形），此处不覆写——
 *       默认帧缓冲 alpha 无下游消费者；</li>
 *   <li>网格 Y 的 {@code (int)g} 亚像素对齐取烘焙时刻的值，滚动时最多差 1px；</li>
 *   <li>逐帧透明度以灰度顶点色 h² 调制（原版剪影 alpha 含 h² 项，逐位等价）。</li>
 * </ul>
 * 上下文重建（显示模式切换）后缓存纹理失效，与图集一致需重启（初版不做热重建）。
 */
public final class RadarCompositeCacheImpl implements RadarCompositeCache {
    private static final Logger LOGGER = Logger.getLogger(RadarCompositeCacheImpl.class);

    private static final RadarCompositeCacheImpl INSTANCE = new RadarCompositeCacheImpl();

    /** 合成纹理边长（像素）。 */
    static final int TEXTURE_SIZE = 2048;
    /** 单元格边长（像素）；图标内衬矩形（portrait-2）实测不超过 ~100px。 */
    static final int CELL_SIZE = 128;

    private static final int COLUMNS = TEXTURE_SIZE / CELL_SIZE;
    private static final int CELL_COUNT = COLUMNS * COLUMNS;

    private boolean initAttempted;
    private boolean available;
    private int fboId;
    private int textureId;
    private TextureObject compositeTexture;

    /** 聚光灯/网格 sprite（纹理与原版 ShipPortraitRenderer 相同，颜色为默认黄）。 */
    private Sprite spotlight;
    private Sprite grid;
    private float gridSize;

    private final CellPool cells = new CellPool(CELL_COUNT, System::nanoTime);
    private final int[] contentX = new int[CELL_COUNT];
    private final int[] contentY = new int[CELL_COUNT];
    private final int[] contentW = new int[CELL_COUNT];
    private final int[] contentH = new int[CELL_COUNT];

    private RadarCompositeCacheImpl() {
    }

    public static RadarCompositeCacheImpl getInstance() {
        return INSTANCE;
    }

    @Override
    public boolean isAvailable() {
        ensureInit();
        return available;
    }

    @Override
    public int acquireCell(final Object owner) {
        ensureInit();
        if (!available) {
            return -1;
        }
        final int cell = cells.acquire(owner);
        if (cell < 0 && cells.pollExhaustionEvent()) {
            LOGGER.warn("[SSOptimizer] Radar composite cache exhausted (" + CELL_COUNT
                    + " cells), fallback to stencil path for new icons");
        }
        return cell;
    }

    @Override
    public boolean touchCell(final int cell, final Object owner) {
        return cells.touch(cell, owner);
    }

    @Override
    public void bakeCell(final int cell, final Sprite portrait, final boolean withSpotlight,
                         final float gridShiftY) {
        if (!available) {
            return;
        }
        // 关键顺序：先把 HUD 待绘批次在原始状态下落盘，再切换 FBO/视口/矩阵
        SpriteBatch.getInstance().flushPending();

        final int cellX = (cell % COLUMNS) * CELL_SIZE;
        final int cellY = (cell / COLUMNS) * CELL_SIZE;
        final float pw = portrait.getWidth();
        final float ph = portrait.getHeight();
        // 原版裁剪掩码内矩形恒为 (w-2)×(h-2)（外框 max(grid+2, w-2) 仅影响 stencil 清理范围）
        final int innerW = Math.max(1, Math.round(pw - 2.0F));
        final int innerH = Math.max(1, Math.round(ph - 2.0F));
        final int scX = cellX + (CELL_SIZE - innerW) / 2;
        final int scY = cellY + (CELL_SIZE - innerH) / 2;
        contentX[cell] = scX;
        contentY[cell] = scY;
        contentW[cell] = innerW;
        contentH[cell] = innerH;

        final int prevFbo = GL11.glGetInteger(EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT);
        GL11.glPushAttrib(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_ENABLE_BIT
                | GL11.GL_VIEWPORT_BIT | GL11.GL_TRANSFORM_BIT);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0, CELL_SIZE, 0, CELL_SIZE, -1000, 1000);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        try {
            EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, fboId);
            GL11.glViewport(cellX, cellY, CELL_SIZE, CELL_SIZE);
            // scissor 复刻原版 stencil 矩形裁剪（同样轴对齐）
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(scX, scY, innerW, innerH);
            GL11.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

            final float cx = CELL_SIZE * 0.5F;
            final float cy = CELL_SIZE * 0.5F;

            // alpha-only 阶段：累积 肖像α×聚光灯α 剪影（原版 stencil 区间内序列）
            GL11.glColorMask(false, false, false, true);
            portrait.setAlphaMult(1.0F);
            portrait.setBlendFunc(GL11.GL_SRC_ALPHA, 0);
            portrait.renderAtCenter(cx, cy);
            spotlight.setSize(pw * 1.25F, ph * 1.25F);
            spotlight.setAlphaMult(1.0F);
            spotlight.setBlendFunc(0, GL11.GL_SRC_ALPHA);
            spotlight.renderAtCenter(cx, cy);

            // additive 阶段：聚光灯（成员）与网格按 DST_ALPHA 加权
            GL11.glColorMask(true, true, true, true);
            if (withSpotlight) {
                spotlight.setBlendFunc(GL11.GL_DST_ALPHA, 1);
                spotlight.renderAtCenter(cx, cy);
            }
            grid.setSize(gridSize, gridSize);
            grid.renderAtCenter(cx - 0.5F, cy + gridShiftY);

            // 恢复 portrait 残留状态（原版 render 末尾同样复位 blend 供后续基础图标绘制）
            portrait.setBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        } finally {
            EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, prevFbo);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopAttrib();
        }
    }

    @Override
    public int cellContentUv(final int cell, final float[] out) {
        out[0] = contentX[cell] / (float) TEXTURE_SIZE;
        out[1] = contentY[cell] / (float) TEXTURE_SIZE;
        out[2] = contentW[cell] / (float) TEXTURE_SIZE;
        out[3] = contentH[cell] / (float) TEXTURE_SIZE;
        return (contentW[cell] << 16) | contentH[cell];
    }

    @Override
    public TextureObject compositeTexture() {
        ensureInit();
        return compositeTexture;
    }

    /** 惰性初始化 GL 资源（首次渲染线程调用）。失败记警告并永久回退原版路径。 */
    private void ensureInit() {
        if (initAttempted) {
            return;
        }
        initAttempted = true;
        if (!GLContext.getCapabilities().GL_EXT_framebuffer_object) {
            LOGGER.warn("[SSOptimizer] Radar composite cache disabled: FBO unsupported");
            return;
        }
        final IntBuffer ids = BufferUtils.createIntBuffer(2);
        EXTFramebufferObject.glGenFramebuffersEXT(ids);
        fboId = ids.get(0);
        GL11.glGenTextures(ids);
        textureId = ids.get(1);

        final int prevFbo = GL11.glGetInteger(EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT);
        final int prevTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, 33071);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, 33071);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA,
                    TEXTURE_SIZE, TEXTURE_SIZE, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
            EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, fboId);
            EXTFramebufferObject.glFramebufferTexture2DEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT,
                    EXTFramebufferObject.GL_COLOR_ATTACHMENT0_EXT, GL11.GL_TEXTURE_2D, textureId, 0);
            if (EXTFramebufferObject.glCheckFramebufferStatusEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT)
                    != EXTFramebufferObject.GL_FRAMEBUFFER_COMPLETE_EXT) {
                LOGGER.warn("[SSOptimizer] Radar composite cache disabled: framebuffer incomplete");
                return;
            }
            GL11.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        } finally {
            EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, prevFbo);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTexture);
        }

        compositeTexture = new TextureObject(GL11.GL_TEXTURE_2D, textureId);
        compositeTexture.setTextureWidth(TEXTURE_SIZE);
        compositeTexture.setTextureHeight(TEXTURE_SIZE);
        compositeTexture.setImageWidth(TEXTURE_SIZE);
        compositeTexture.setImageHeight(TEXTURE_SIZE);

        spotlight = new Sprite(TextureManager.getTexture("graphics/hud/spotlight_small.png"));
        spotlight.setColor(new Color(255, 255, 0));
        grid = new Sprite(TextureManager.getTexture("graphics/hud/grid_small.png"));
        grid.setColor(new Color(255, 210, 0));
        grid.setBlendFunc(GL11.GL_DST_ALPHA, 1);
        gridSize = grid.getWidth();

        available = true;
        LOGGER.info("[SSOptimizer] Radar composite cache initialized: " + TEXTURE_SIZE + "², "
                + CELL_COUNT + " cells of " + CELL_SIZE + "²");
    }
}
