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

public final class RadarCompositeCacheImpl implements RadarCompositeCache {
    private static final Logger LOGGER = Logger.getLogger(RadarCompositeCacheImpl.class);

    private static final RadarCompositeCacheImpl INSTANCE = new RadarCompositeCacheImpl();

    static final int TEXTURE_SIZE = 2048;
    static final int CELL_SIZE = 128;

    private static final int COLUMNS = TEXTURE_SIZE / CELL_SIZE;
    private static final int CELL_COUNT = COLUMNS * COLUMNS;

    private boolean initAttempted;
    private boolean available;
    private int fboId;
    private int textureId;
    private TextureObject compositeTexture;

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
        // 切换 FBO/视口/矩阵前必须提交 HUD 待绘批次。
        SpriteBatch.getInstance().flushPending();

        final int cellX = (cell % COLUMNS) * CELL_SIZE;
        final int cellY = (cell / COLUMNS) * CELL_SIZE;
        final float pw = portrait.getWidth();
        final float ph = portrait.getHeight();
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
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(scX, scY, innerW, innerH);
            GL11.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

            final float cx = CELL_SIZE * 0.5F;
            final float cy = CELL_SIZE * 0.5F;

            GL11.glColorMask(false, false, false, true);
            portrait.setAlphaMult(1.0F);
            portrait.setBlendFunc(GL11.GL_SRC_ALPHA, 0);
            portrait.renderAtCenter(cx, cy);
            spotlight.setSize(pw * 1.25F, ph * 1.25F);
            spotlight.setAlphaMult(1.0F);
            spotlight.setBlendFunc(0, GL11.GL_SRC_ALPHA);
            spotlight.renderAtCenter(cx, cy);

            GL11.glColorMask(true, true, true, true);
            if (withSpotlight) {
                spotlight.setBlendFunc(GL11.GL_DST_ALPHA, 1);
                spotlight.renderAtCenter(cx, cy);
            }
            grid.setSize(gridSize, gridSize);
            grid.renderAtCenter(cx - 0.5F, cy + gridShiftY);

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
