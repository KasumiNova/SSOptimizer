package github.kasuminova.ssoptimizer.common.loading;

import github.kasuminova.ssoptimizer.common.loading.GlMemoryLedger.Category;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link GlMemoryLedger} 的纯逻辑验证：add/remove 累计、格式串确定性、
 * 内部格式 bpp 映射、mipmap 近似系数。
 */
class GlMemoryLedgerTest {

    @AfterEach
    void tearDown() {
        GlMemoryLedger.reset();
    }

    @Test
    void addRemoveAccumulatesBytesAndObjects() {
        GlMemoryLedger.add(Category.FBO_TEX, 1024L, 1);
        GlMemoryLedger.add(Category.FBO_TEX, 2048L, 2);
        assertEquals(3072L, GlMemoryLedger.bytesOf(Category.FBO_TEX));
        assertEquals(3L, GlMemoryLedger.objectsOf(Category.FBO_TEX));

        GlMemoryLedger.remove(Category.FBO_TEX, 1024L, 1);
        assertEquals(2048L, GlMemoryLedger.bytesOf(Category.FBO_TEX));
        assertEquals(2L, GlMemoryLedger.objectsOf(Category.FBO_TEX));

        // 分类之间相互独立
        assertEquals(0L, GlMemoryLedger.bytesOf(Category.GFXLIB_TEX));
    }

    @Test
    void formatSummarySkipsEmptyCategoriesAndUsesEnumOrder() {
        assertEquals("", GlMemoryLedger.formatSummary());

        GlMemoryLedger.add(Category.FBO_TEX, 268L * 1024 * 1024, 4);
        GlMemoryLedger.add(Category.GFXLIB_TEX, 512L * 1024, 312);
        // rbo 为空应跳过；枚举顺序 fboTex 先于 gfxlibTex
        assertEquals("glLedger fboTex=268MiB(4) gfxlibTex=512KiB(312)",
                GlMemoryLedger.formatSummary());
    }

    @Test
    void formatSummaryReflectsRemoval() {
        GlMemoryLedger.add(Category.BOX_TEX, 1024L * 1024, 1);
        GlMemoryLedger.remove(Category.BOX_TEX, 1024L * 1024, 1);
        assertEquals("", GlMemoryLedger.formatSummary());
    }

    @Test
    void bytesPerPixelCoversKnownFormats() {
        assertEquals(3, GlMemoryLedger.bytesPerPixel(32849));  // GL_RGB8（ShaderLib screenTex）
        assertEquals(4, GlMemoryLedger.bytesPerPixel(32856));  // GL_RGBA8
        assertEquals(8, GlMemoryLedger.bytesPerPixel(32859));  // GL_RGBA16（ShaderLib aux 64bit）
        assertEquals(6, GlMemoryLedger.bytesPerPixel(32852));  // GL_RGB16（LightShader hdrTex）
        assertEquals(4, GlMemoryLedger.bytesPerPixel(33326));  // GL_R32F（LightShader lightTex）
        assertEquals(8, GlMemoryLedger.bytesPerPixel(34842));  // GL_RGBA16F
        assertEquals(16, GlMemoryLedger.bytesPerPixel(34836)); // GL_RGBA32F
        assertEquals(1, GlMemoryLedger.bytesPerPixel(36168));  // GL_STENCIL_INDEX8（ShaderLib rbo）
        assertEquals(2, GlMemoryLedger.bytesPerPixel(33189));  // GL_DEPTH_COMPONENT16（BoxUtil rbo）
        assertEquals(4, GlMemoryLedger.bytesPerPixel(35056));  // GL_DEPTH24_STENCIL8（PublicFBO rbo）
        // 未知格式按 4 计（WARN 一次，不抛异常）
        assertEquals(4, GlMemoryLedger.bytesPerPixel(999999));
    }

    @Test
    void withMipmapsApproximatesFullChain() {
        assertEquals(4L, GlMemoryLedger.withMipmaps(3L));
        assertEquals(1024L + 341L, GlMemoryLedger.withMipmaps(1024L));
    }
}
