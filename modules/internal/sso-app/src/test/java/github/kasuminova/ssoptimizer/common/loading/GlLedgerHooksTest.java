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

    @Test
    void texImageBytesComputesByFormatAndLevels() {
        // RGBA8 单层
        assertEquals(1920L * 1080 * 4, GlLedgerHooks.texImageBytes(32856, 1920, 1080, 0));
        assertEquals(1920L * 1080 * 4, GlLedgerHooks.texImageBytes(32856, 1920, 1080, 1));
        // levels>1（texStorage 完整链）：×4/3 近似
        assertEquals(GlMemoryLedger.withMipmaps(1920L * 1080 * 4),
                GlLedgerHooks.texImageBytes(32856, 1920, 1080, 5));
        // R32F 1D 查找表（LightShader 同型）
        assertEquals(4096L * 4, GlLedgerHooks.texImageBytes(33326, 4096, 1, 0));
        // 非法尺寸不入账
        assertEquals(0L, GlLedgerHooks.texImageBytes(32856, 0, 1080, 0));
        assertEquals(0L, GlLedgerHooks.texImageBytes(32856, 1920, -1, 0));
    }

    @Test
    void trackedTextureReplaceCrossCategoryAndFree() {
        GlLedgerHooks.noteTextureBytes(Category.UPTEX, 101, 1000);
        assertEquals(1000L, GlMemoryLedger.bytesOf(Category.UPTEX));

        // 同 id 重分配（尺寸变化重建）：替换而非累加
        GlLedgerHooks.noteTextureBytes(Category.UPTEX, 101, 2000);
        assertEquals(2000L, GlMemoryLedger.bytesOf(Category.UPTEX));
        assertEquals(1L, GlMemoryLedger.objectsOf(Category.UPTEX));

        // 跨分类复用同 id：旧值从原分类减去
        GlLedgerHooks.noteTextureBytes(Category.SCREEN_RT, 101, 4000);
        assertEquals(0L, GlMemoryLedger.bytesOf(Category.UPTEX));
        assertEquals(4000L, GlMemoryLedger.bytesOf(Category.SCREEN_RT));

        GlLedgerHooks.noteTextureFreed(101);
        assertEquals(0L, GlMemoryLedger.bytesOf(Category.SCREEN_RT));
        assertEquals(0L, GlMemoryLedger.objectsOf(Category.SCREEN_RT));
        // 重复删除幂等
        GlLedgerHooks.noteTextureFreed(101);
        assertEquals(0L, GlMemoryLedger.bytesOf(Category.SCREEN_RT));
    }

    @Test
    void untrackedTextureIdFallsBackToGrossAdd() {
        // id 不可得（绑定查询失败）时退化为只加不减的毛计量
        GlLedgerHooks.noteTextureBytes(Category.UPTEX, 0, 500);
        GlLedgerHooks.noteTextureBytes(Category.UPTEX, -1, 700);
        assertEquals(1200L, GlMemoryLedger.bytesOf(Category.UPTEX));
        assertEquals(2L, GlMemoryLedger.objectsOf(Category.UPTEX));
        // 毛计量条目无 id，删除不影响
        GlLedgerHooks.noteTextureFreed(0);
        assertEquals(1200L, GlMemoryLedger.bytesOf(Category.UPTEX));
    }

    @Test
    void trackedBufferReplaceAndFree() {
        GlLedgerHooks.noteBufferBytes(55, 1L << 20);
        assertEquals(1L << 20, GlMemoryLedger.bytesOf(Category.VBO));

        // 同 id 扩容重分配（glBufferData 语义即旧存储释放）：替换计
        GlLedgerHooks.noteBufferBytes(55, 2L << 20);
        assertEquals(2L << 20, GlMemoryLedger.bytesOf(Category.VBO));
        assertEquals(1L, GlMemoryLedger.objectsOf(Category.VBO));

        GlLedgerHooks.noteBufferFreed(55);
        assertEquals(0L, GlMemoryLedger.bytesOf(Category.VBO));
        assertEquals(0L, GlMemoryLedger.objectsOf(Category.VBO));
        GlLedgerHooks.noteBufferFreed(55);

        // id 不可得：毛计量
        GlLedgerHooks.noteBufferBytes(-1, 4096);
        assertEquals(4096L, GlMemoryLedger.bytesOf(Category.VBO));
    }

    @Test
    void gameTexBytesForUploadComputesRgba8WithMipmaps() {
        // RGBA8 单层
        assertEquals(256L * 128 * 4, GlLedgerHooks.gameTexBytesForUpload(false, 256, 128));
        // GL_GENERATE_MIPMAP=1 完整链 ×4/3
        assertEquals(GlMemoryLedger.withMipmaps(256L * 128 * 4),
                GlLedgerHooks.gameTexBytesForUpload(true, 256, 128));
        // 非法尺寸不入账
        assertEquals(0L, GlLedgerHooks.gameTexBytesForUpload(false, 0, 128));
        assertEquals(0L, GlLedgerHooks.gameTexBytesForUpload(true, 256, -1));
    }

    @Test
    void compressedContainerBytesSumsLevelDataLengths() {
        // BC7 64x64 单层：16x16 块 × 16B = 4096
        final SsobcContainer single = SsobcContainer.parse(
                SsobcTestContainers.build(NativeTextureCompressor.FORMAT_BC7, 64, 64, 1));
        assertEquals(4096L, GlLedgerHooks.compressedContainerBytes(single));

        // BC1 32x32 完整链（6 级：512+128+32+8+8+8 = 696）
        final SsobcContainer fullChain = SsobcContainer.parse(
                SsobcTestContainers.buildFullChain(NativeTextureCompressor.FORMAT_BC1, 32, 32));
        long expected = 0L;
        for (final SsobcContainer.Level level : fullChain.levels()) {
            expected += level.dataLength();
        }
        assertEquals(696L, expected);
        assertEquals(expected, GlLedgerHooks.compressedContainerBytes(fullChain));
    }

    @Test
    void gameTexLifecycleUploadUpgradeEvict() {
        // 首轮未压缩上传（mipmaps）
        GlLedgerHooks.noteGameTexBytes(201,
                GlLedgerHooks.gameTexBytesForUpload(true, 256, 128));
        final long uncompressed = GlMemoryLedger.withMipmaps(256L * 128 * 4);
        assertEquals(uncompressed, GlMemoryLedger.bytesOf(Category.GAME_TEX));
        assertEquals(1L, GlMemoryLedger.objectsOf(Category.GAME_TEX));

        // 热重传升级压缩形态：同 id 替换（旧未压缩存储随 texImage 重定义释放）
        final SsobcContainer container = SsobcContainer.parse(
                SsobcTestContainers.buildFullChain(NativeTextureCompressor.FORMAT_BC7, 256, 128));
        final long compressed = GlLedgerHooks.compressedContainerBytes(container);
        GlLedgerHooks.noteGameTexBytes(201, compressed);
        assertEquals(compressed, GlMemoryLedger.bytesOf(Category.GAME_TEX));
        assertEquals(1L, GlMemoryLedger.objectsOf(Category.GAME_TEX));

        // 闲置驱逐：对称减量归零；重复删除幂等
        GlLedgerHooks.noteGameTexFreed(201);
        assertEquals(0L, GlMemoryLedger.bytesOf(Category.GAME_TEX));
        assertEquals(0L, GlMemoryLedger.objectsOf(Category.GAME_TEX));
        GlLedgerHooks.noteGameTexFreed(201);
        assertEquals(0L, GlMemoryLedger.bytesOf(Category.GAME_TEX));
    }

    @Test
    void gameTexSharesIdTrackingWithOtherTextureCategories() {
        // TRACKED_TEXTURES 按 id 全局跟踪：模组直传纹理与受管贴图复用同 id 时跨分类替换
        GlLedgerHooks.noteTextureBytes(Category.UPTEX, 202, 999);
        GlLedgerHooks.noteGameTexBytes(202, 500);
        assertEquals(0L, GlMemoryLedger.bytesOf(Category.UPTEX));
        assertEquals(500L, GlMemoryLedger.bytesOf(Category.GAME_TEX));

        // gameTex 的删除钩子同样能对称清掉其它分类登记的同 id 条目
        GlLedgerHooks.noteGameTexFreed(202);
        assertEquals(0L, GlMemoryLedger.bytesOf(Category.GAME_TEX));
    }
}
