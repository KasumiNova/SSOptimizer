package github.kasuminova.ssoptimizer.common.loading;

import github.kasuminova.ssoptimizer.common.loading.GlMemoryLedger.Category;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link GlLedgerHooks} 计量口径的完整逻辑验证：直调钩子入口并核对账本读数，
 * 覆盖创建/重复创建/删除的全生命周期。
 */
class GlLedgerHooksTest {

    @BeforeEach
    void setUp() {
        GlMemoryLedger.reset();
        GlLedgerHooks.resetForTesting();
    }

    @AfterEach
    void tearDown() {
        GlMemoryLedger.reset();
        GlLedgerHooks.resetForTesting();
    }

    @Test
    void nextPowerOfTwoMirrorsGameLogic() {
        assertEquals(2, GlLedgerHooks.nextPowerOfTwo(1));
        assertEquals(2, GlLedgerHooks.nextPowerOfTwo(2));
        assertEquals(4, GlLedgerHooks.nextPowerOfTwo(3));
        assertEquals(1024, GlLedgerHooks.nextPowerOfTwo(1024));
        assertEquals(2048, GlLedgerHooks.nextPowerOfTwo(1920));
    }

    @Test
    void vanillaFboLifecycle() {
        // 100x50 → POT 128x64 RGBA8
        GlLedgerHooks.noteVanillaFboCreated(7, 100, 50, false);
        assertEquals(128L * 64 * 4, GlMemoryLedger.bytesOf(Category.FBO_TEX));
        assertEquals(1L, GlMemoryLedger.objectsOf(Category.FBO_TEX));

        // 同 fboId 重建（resize）：旧值替换而非累加；mipmaps ×4/3
        GlLedgerHooks.noteVanillaFboCreated(7, 100, 50, true);
        assertEquals(GlMemoryLedger.withMipmaps(128L * 64 * 4),
                GlMemoryLedger.bytesOf(Category.FBO_TEX));
        assertEquals(1L, GlMemoryLedger.objectsOf(Category.FBO_TEX));

        GlLedgerHooks.noteVanillaFboDeleted(7);
        assertEquals(0L, GlMemoryLedger.bytesOf(Category.FBO_TEX));
        assertEquals(0L, GlMemoryLedger.objectsOf(Category.FBO_TEX));

        // 未登记 id 的删除是无害空操作
        GlLedgerHooks.noteVanillaFboDeleted(7);
        assertEquals(0L, GlMemoryLedger.bytesOf(Category.FBO_TEX));
    }

    @Test
    void shaderLibInitAccountsThreeScreenBuffersAndReinitReplaces() {
        final int w = 1920;
        final int h = 1080;
        final long pixels = (long) w * h;
        GlLedgerHooks.noteShaderLibInit(true, true, false, w, h, 11, 12, 13);
        final long expected = GlMemoryLedger.withMipmaps(pixels * 3L)
                + GlMemoryLedger.withMipmaps(pixels * 4L)
                + GlMemoryLedger.withMipmaps(pixels * 4L);
        assertEquals(expected, GlMemoryLedger.bytesOf(Category.GFXLIB_TEX));
        assertEquals(3L, GlMemoryLedger.objectsOf(Category.GFXLIB_TEX));

        // 64bit 辅助缓冲 + 重复 init（同 id）：RGBA16 且总量不翻倍
        GlLedgerHooks.noteShaderLibInit(true, true, true, w, h, 11, 12, 13);
        assertEquals(GlMemoryLedger.withMipmaps(pixels * 3L)
                        + GlMemoryLedger.withMipmaps(pixels * 4L)
                        + GlMemoryLedger.withMipmaps(pixels * 8L),
                GlMemoryLedger.bytesOf(Category.GFXLIB_TEX));
        assertEquals(3L, GlMemoryLedger.objectsOf(Category.GFXLIB_TEX));

        // buffersAllowed=false：只计 screenTex
        GlMemoryLedger.reset();
        GlLedgerHooks.resetForTesting();
        GlLedgerHooks.noteShaderLibInit(true, false, false, w, h, 11, 12, 13);
        assertEquals(GlMemoryLedger.withMipmaps(pixels * 3L),
                GlMemoryLedger.bytesOf(Category.GFXLIB_TEX));
    }

    @Test
    void shaderLibRenderbufferIsStencil8PeakOnly() {
        GlLedgerHooks.noteShaderLibRenderbuffer(42, 1920, 1080);
        assertEquals(1920L * 1080, GlMemoryLedger.bytesOf(Category.RBO));
        assertEquals(1L, GlMemoryLedger.objectsOf(Category.RBO));
    }

    @Test
    void lightShaderLifecycleUsesCachedInternalSize() {
        GlLedgerHooks.noteShaderLibInternalWidth(1920);
        GlLedgerHooks.noteShaderLibInternalHeight(1080);
        final long pixels = 1920L * 1080;
        final Object shader = new Object();
        GlLedgerHooks.noteLightShaderCreated(shader, 1, 2, 3, 4, 5, 3);
        final long bloomPixels = (1920L >> 2) * (1080L >> 2);
        final long expected = 4096L * 4
                + GlMemoryLedger.withMipmaps(pixels * 3L)
                + GlMemoryLedger.withMipmaps(pixels * 6L)
                + 2 * GlMemoryLedger.withMipmaps(bloomPixels * 3L);
        assertEquals(expected, GlMemoryLedger.bytesOf(Category.GFXLIB_TEX));
        assertEquals(5L, GlMemoryLedger.objectsOf(Category.GFXLIB_TEX));

        GlLedgerHooks.noteLightShaderDestroyed(shader);
        assertEquals(0L, GlMemoryLedger.bytesOf(Category.GFXLIB_TEX));
        assertEquals(0L, GlMemoryLedger.objectsOf(Category.GFXLIB_TEX));
    }

    @Test
    void boxRenderingBufferAccountsLayersBloomAndRbo() {
        final int[][] scaleSize = {{1920, 1080}, {960, 540}};
        final int[][] formats = {{32856}, {32856, 32856}};
        final int[][] texId = {{10}, {20, 21}};
        // bloomPingPongTex[0] 是 texID[1][0] 的别名，不参与计量
        final int[] bloom = {20, 30};
        final Object buffer = new Object();
        GlLedgerHooks.noteBoxRenderingBufferCreated(buffer, texId, formats, scaleSize,
                bloom, 2, new boolean[]{true, true}, 5);

        final long full = 1920L * 1080;
        assertEquals(full * 4 * 3 + 960L * 540 * 4, GlMemoryLedger.bytesOf(Category.BOX_TEX));
        assertEquals(4L, GlMemoryLedger.objectsOf(Category.BOX_TEX));
        assertEquals(full * 2, GlMemoryLedger.bytesOf(Category.RBO));
    }

    @Test
    void boxRenderingBufferSkipsUnfinishedLayersAndRbo() {
        final int[][] scaleSize = {{1920, 1080}, {960, 540}};
        final int[][] formats = {{32856}, {32856, 32856}};
        final int[][] texId = {{10}, {20, 21}};
        final Object buffer = new Object();
        // layer 0 未完工：行 0 与 RBO 均不入账（构造器内部 delete(0) 已清理）
        GlLedgerHooks.noteBoxRenderingBufferCreated(buffer, texId, formats, scaleSize,
                new int[]{20, 30}, 2, new boolean[]{false, true}, 5);
        assertEquals(1920L * 1080 * 4 * 2 + 960L * 540 * 4,
                GlMemoryLedger.bytesOf(Category.BOX_TEX));
        assertEquals(0L, GlMemoryLedger.bytesOf(Category.RBO));
    }

    @Test
    void publicFboLifecycleUsesCachedScreenScale() {
        GlLedgerHooks.noteBoxScaleWidth(1920);
        GlLedgerHooks.noteBoxScaleHeight(1080);
        final long pixels = 1920L * 1080;
        final Object fbo = new Object();
        GlLedgerHooks.notePublicFboCreated(fbo, new int[]{31, 32, 33, 34},
                new int[][]{{32856}, {32856}, {34842}, {32856}}, true, 9);
        assertEquals(pixels * (4 + 4 + 8 + 4), GlMemoryLedger.bytesOf(Category.BOX_TEX));
        assertEquals(4L, GlMemoryLedger.objectsOf(Category.BOX_TEX));
        assertEquals(pixels * 4, GlMemoryLedger.bytesOf(Category.RBO));

        GlLedgerHooks.notePublicFboDeleted(fbo);
        assertEquals(0L, GlMemoryLedger.bytesOf(Category.BOX_TEX));
        assertEquals(0L, GlMemoryLedger.bytesOf(Category.RBO));

        // 构造失败（finished=false）不入账，其内部 delete() 调用也为空操作
        GlLedgerHooks.notePublicFboCreated(new Object(), new int[]{31},
                new int[][]{{32856}}, false, 9);
        assertEquals(0L, GlMemoryLedger.bytesOf(Category.BOX_TEX));
    }
}
