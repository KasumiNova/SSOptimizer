package github.kasuminova.ssoptimizer.common.loading;

import github.kasuminova.ssoptimizer.common.loading.TextureCompressionSupport.Format;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TextureCompressionEligibility} 判定矩阵（纯函数直调，不触碰 GL/磁盘）。
 * <p>
 * 质量优先矩阵（按实际像素 alpha 内容 {@link AlphaKind} 而非 ColorModel 声明）：
 * bptc 可用（首选 BC7）一律 BC7，仅 {@code bc1ForOpaque=true} 时全不透明大图用 BC1；
 * S3TC 回退（首选 BC3）按 alpha 内容分流：OPAQUE/BINARY 大图 → BC1（后者带
 * 1-bit punch-through alpha），FULL 或小尺寸 → BC3。
 */
class TextureCompressionEligibilityTest {
    private static final String SHIP = "graphics/ships/kite.png";

    @Test
    void smallTexturesAreExcluded() {
        // max(w,h) < 64
        assertEquals(Format.NONE, select("graphics/icons/icon.png", 32, 32, AlphaKind.FULL, Format.BC7, true, false));
        assertEquals(Format.NONE, select("graphics/icons/icon.png", 63, 63, AlphaKind.OPAQUE, Format.BC7, true, false));
        // 原始 RGBA 字节 < 16KB（64x63: max 达标但 64*63*4=16128 < 16384）
        assertEquals(Format.NONE, select(SHIP, 64, 63, AlphaKind.OPAQUE, Format.BC7, true, false));
        // 恰好 64x64（16384B）达到下限，不排除
        assertEquals(Format.BC7, select(SHIP, 64, 64, AlphaKind.FULL, Format.BC7, true, false));
    }

    @Test
    void materialSuffixesAreExcluded() {
        assertEquals(Format.NONE, select("graphics/ships/kite_normal.png", 512, 512, AlphaKind.OPAQUE, Format.BC7, true, false));
        assertEquals(Format.NONE, select("graphics/ships/kite_surface.png", 512, 512, AlphaKind.FULL, Format.BC7, true, false));
        assertEquals(Format.NONE, select("graphics/ships/kite_material.png", 512, 512, AlphaKind.OPAQUE, Format.BC3, true, false));
        // 忽略大小写与扩展名
        assertEquals(Format.NONE, select("graphics/ships/KITE_NORMAL.PNG", 512, 512, AlphaKind.OPAQUE, Format.BC7, true, false));
        assertEquals(Format.NONE, select("graphics/ships/kite_Normal.tga", 512, 512, AlphaKind.OPAQUE, Format.BC7, true, false));
        // 后缀出现在目录名/文件名中段不误判
        assertEquals(Format.BC7, select("graphics/normal_maps/kite.png", 512, 512, AlphaKind.OPAQUE, Format.BC7, true, false));
    }

    @Test
    void fontAtlasesAreExcluded() {
        assertEquals(Format.NONE, select("graphics/fonts/insignia15.png", 512, 512, AlphaKind.FULL, Format.BC7, true, false));
        assertEquals(Format.NONE, select("graphics/fonts/orbitron12.png", 512, 512, AlphaKind.FULL, Format.BC7, true, false));
        assertEquals(Format.NONE, select("graphics/fonts/victor12.png", 512, 512, AlphaKind.FULL, Format.BC7, true, false));
        assertEquals(Format.NONE, select("ssoptimizer/runtimefonts/graphics/fonts/insignia15.png",
                512, 512, AlphaKind.FULL, Format.BC7, true, false));
    }

    @Test
    void bptcAvailablePrefersBc7ForEverything() {
        // 格式矩阵与路径排除面正交：本测试清空排除面只验格式选择
        System.setProperty(TextureCompressionEligibility.EXCLUDED_PATHS_PROPERTY, "");
        try {
            // 质量优先：大而平滑的不透明贴图（星云背景等）不再落 BC1（4bpp 块效应色带不可接受）
            assertEquals(Format.BC7, select("graphics/backgrounds/nebula.png", 2048, 2048, AlphaKind.OPAQUE, Format.BC7, true, false));
            assertEquals(Format.BC7, select(SHIP, 256, 256, AlphaKind.OPAQUE, Format.BC7, true, false));
            assertEquals(Format.BC7, select(SHIP, 512, 512, AlphaKind.BINARY, Format.BC7, true, false));
            assertEquals(Format.BC7, select(SHIP, 512, 512, AlphaKind.FULL, Format.BC7, true, false));
            // null alphaKind（元数据缺失的保守兜底）按 FULL 处理
            assertEquals(Format.BC7, select(SHIP, 512, 512, null, Format.BC7, true, false));
        } finally {
            System.clearProperty(TextureCompressionEligibility.EXCLUDED_PATHS_PROPERTY);
        }
    }

    @Test
    void bc1ForOpaquePropertyOptsIntoBc1ForOpaqueLarge() {
        System.setProperty(TextureCompressionEligibility.EXCLUDED_PATHS_PROPERTY, "");
        try {
            // bc1ForOpaque=true + 实际全不透明 + max≥256 + s3tc → BC1（省显存换画质，用户显式取舍）
            assertEquals(Format.BC1, select("graphics/backgrounds/nebula.png", 2048, 2048, AlphaKind.OPAQUE, Format.BC7, true, true));
            assertEquals(Format.BC1, select(SHIP, 256, 256, AlphaKind.OPAQUE, Format.BC7, true, true));
            // BINARY/FULL 不受开关影响，仍 BC7
            assertEquals(Format.BC7, select(SHIP, 512, 512, AlphaKind.BINARY, Format.BC7, true, true));
            assertEquals(Format.BC7, select(SHIP, 512, 512, AlphaKind.FULL, Format.BC7, true, true));
            // 尺寸下限：255 差一档仍 BC7
            assertEquals(Format.BC7, select(SHIP, 255, 255, AlphaKind.OPAQUE, Format.BC7, true, true));
            // 无 s3tc 能力时即便开关开启也不选 BC1（首选 BC7 直通）
            assertEquals(Format.BC7, select(SHIP, 512, 512, AlphaKind.OPAQUE, Format.BC7, false, true));
        } finally {
            System.clearProperty(TextureCompressionEligibility.EXCLUDED_PATHS_PROPERTY);
        }
    }

    @Test
    void defaultExcludePathsCoverBackgroundsAndFx() {
        // 默认排除面（实测 high 档 BC7 仍有可见色阶，画质优先直接排除）：
        // background/starscape/nebula/illustration//fx/ 子串命中即不压缩
        assertEquals(Format.NONE, select("graphics/backgrounds/nebula.png", 2048, 2048, AlphaKind.OPAQUE, Format.BC7, true, false));
        assertEquals(Format.NONE, select("graphics/starscape/bg.png", 2048, 2048, AlphaKind.OPAQUE, Format.BC7, true, false));
        assertEquals(Format.NONE, select("graphics/illustrations/rat_genesis.jpg", 2048, 2048, AlphaKind.OPAQUE, Format.BC7, true, false));
        assertEquals(Format.NONE, select("graphics/fx/explosion_large.png", 512, 512, AlphaKind.FULL, Format.BC7, true, false));
        // 模组路径（子串匹配、大小写不敏感）
        assertEquals(Format.NONE, select("graphics/aEP_Background/x.png", 1024, 1024, AlphaKind.OPAQUE, Format.BC7, true, false));
        // "/fx/" 不误伤 "sfx/"；普通舰船贴图不受影响
        assertEquals(Format.BC7, select("graphics/sfx/beam.png", 512, 512, AlphaKind.FULL, Format.BC7, true, false));
        assertEquals(Format.BC7, select(SHIP, 512, 512, AlphaKind.FULL, Format.BC7, true, false));
    }

    @Test
    void s3tcFallbackSelectsByActualAlphaContent() {
        // 回退路径（首选 BC3 = bptc 不可用或用户强制 bc3）：
        // 实际全不透明大图 → BC1（声明了 alpha 通道但像素全 255 也走这里，修复显存浪费）
        assertEquals(Format.BC1, select(SHIP, 2048, 2048, AlphaKind.OPAQUE, Format.BC3, true, false));
        // alpha 仅 0/255 的大图 → BC1（1-bit punch-through alpha）
        assertEquals(Format.BC1, select(SHIP, 512, 512, AlphaKind.BINARY, Format.BC3, true, false));
        // 半透明渐变 → BC3（插值 alpha）
        assertEquals(Format.BC3, select(SHIP, 512, 512, AlphaKind.FULL, Format.BC3, true, false));
        // BC1 尺寸下限：不达标的 OPAQUE/BINARY 落 BC3
        assertEquals(Format.BC3, select(SHIP, 255, 255, AlphaKind.OPAQUE, Format.BC3, true, false));
        assertEquals(Format.BC3, select(SHIP, 128, 128, AlphaKind.BINARY, Format.BC3, true, false));
        // 无 s3tc 能力（首选 BC3 却无 s3tc 属异常输入）→ 直通调用方的首选
        assertEquals(Format.BC3, select(SHIP, 512, 512, AlphaKind.OPAQUE, Format.BC3, false, false));
        // 首选 NONE（两扩展皆无）→ 不压缩
        assertEquals(Format.NONE, select(SHIP, 512, 512, AlphaKind.OPAQUE, Format.NONE, false, false));
    }

    @Test
    void preferredFormatPassesThrough() {
        // BC7 偏好直通（FULL alpha）
        assertEquals(Format.BC7, select(SHIP, 128, 128, AlphaKind.FULL, Format.BC7, true, false));
        // NONE 直通（两扩展皆无）
        assertEquals(Format.NONE, select(SHIP, 512, 512, AlphaKind.FULL, Format.NONE, false, false));
    }

    @Test
    void punchThroughAlphaOnlyForBinaryBc1() {
        assertTrue(TextureCompressionEligibility.usesPunchThroughAlpha(Format.BC1, AlphaKind.BINARY));
        assertFalse(TextureCompressionEligibility.usesPunchThroughAlpha(Format.BC1, AlphaKind.OPAQUE));
        assertFalse(TextureCompressionEligibility.usesPunchThroughAlpha(Format.BC1, AlphaKind.FULL));
        assertFalse(TextureCompressionEligibility.usesPunchThroughAlpha(Format.BC7, AlphaKind.BINARY));
        assertFalse(TextureCompressionEligibility.usesPunchThroughAlpha(Format.BC3, AlphaKind.BINARY));
        assertFalse(TextureCompressionEligibility.usesPunchThroughAlpha(Format.NONE, AlphaKind.BINARY));
    }

    @Test
    void resolveCompressedUploadOnlyHitsWhenEligibleAndCached() {
        final SsobcContainer container = SsobcContainer.parse(SsobcTestContainers.buildFullChain(
                NativeTextureCompressor.FORMAT_BC7, 128, 128));
        assertNotNull(container);

        // 适用 + 命中 → 返回容器
        assertSame(container, TextureCompressionEligibility.resolveCompressedUpload(
                SHIP, 128, 128, AlphaKind.FULL, Format.BC7, true, "hash", true, key -> container));
        // 适用 + 未命中 → null
        assertNull(TextureCompressionEligibility.resolveCompressedUpload(
                SHIP, 128, 128, AlphaKind.FULL, Format.BC7, true, "hash", true, key -> null));
        // 不适用（小图）即便缓存有货也不走压缩上传
        assertNull(TextureCompressionEligibility.resolveCompressedUpload(
                "graphics/icons/icon.png", 32, 32, AlphaKind.FULL, Format.BC7, true, "hash", true, key -> container));
        // 无源哈希（兜底上传）不走压缩路径
        assertNull(TextureCompressionEligibility.resolveCompressedUpload(
                SHIP, 128, 128, AlphaKind.FULL, Format.BC7, true, null, true, key -> container));
        assertNull(TextureCompressionEligibility.resolveCompressedUpload(
                SHIP, 128, 128, AlphaKind.FULL, Format.BC7, true, "  ", true, key -> container));
    }

    private static Format select(final String path,
                                 final int width,
                                 final int height,
                                 final AlphaKind alphaKind,
                                 final Format preferred,
                                 final boolean s3tc,
                                 final boolean bc1ForOpaque) {
        return TextureCompressionEligibility.selectFormat(path, width, height, alphaKind, preferred, s3tc, bc1ForOpaque);
    }

    @Test
    void excludePathsPropertyDisablesCompression() {
        System.setProperty(TextureCompressionEligibility.EXCLUDED_PATHS_PROPERTY, "backgrounds, fx/");
        try {
            assertEquals(Format.NONE, select("graphics/backgrounds/nebula.png",
                    2048, 2048, AlphaKind.OPAQUE, Format.BC7, true, false));
            // 子串匹配大小写不敏感
            assertEquals(Format.NONE, select("graphics/BACKGROUNDS/Nebula.PNG",
                    2048, 2048, AlphaKind.OPAQUE, Format.BC7, true, false));
            // 未命中路径不受影响
            assertEquals(Format.BC7, select(SHIP, 512, 512, AlphaKind.FULL, Format.BC7, true, false));
        } finally {
            System.clearProperty(TextureCompressionEligibility.EXCLUDED_PATHS_PROPERTY);
        }
    }

    @Test
    void emptyExcludePathsPropertyKeepsEverythingEligible() {
        System.setProperty(TextureCompressionEligibility.EXCLUDED_PATHS_PROPERTY, "");
        try {
            assertEquals(Format.BC7, select("graphics/backgrounds/nebula.png",
                    2048, 2048, AlphaKind.OPAQUE, Format.BC7, true, false));
        } finally {
            System.clearProperty(TextureCompressionEligibility.EXCLUDED_PATHS_PROPERTY);
        }
    }
}
