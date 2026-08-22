package github.kasuminova.ssoptimizer.common.loading;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TextureCompressionSupport} 的判定纯函数直调验证：扩展字符串解析与格式决策
 * 不触碰真实 GL（探测入口本身需要 GL 上下文，不在单测面内）。
 */
class TextureCompressionSupportTest {
    private static final String BPTC = "GL_ARB_texture_compression_bptc";
    private static final String S3TC = "GL_EXT_texture_compression_s3tc";

    @Test
    void bptcExtensionStringPrefersBc7() {
        final TextureCompressionSupport.ProbeResult probe = TextureCompressionSupport.parseExtensionSupport(
                "GL_ARB_texture_storage " + BPTC + ' ' + S3TC + " GL_EXT_texture_filter_anisotropic");

        assertTrue(probe.bptc());
        assertTrue(probe.s3tc());
        assertEquals(TextureCompressionSupport.Format.BC7,
                TextureCompressionSupport.decideFormat(true, "auto", probe.bptc(), probe.s3tc()));
    }

    @Test
    void s3tcOnlyExtensionStringFallsBackToBc3() {
        final TextureCompressionSupport.ProbeResult probe = TextureCompressionSupport.parseExtensionSupport(
                "GL_ARB_texture_storage " + S3TC);

        assertFalse(probe.bptc());
        assertTrue(probe.s3tc());
        assertEquals(TextureCompressionSupport.Format.BC3,
                TextureCompressionSupport.decideFormat(true, "auto", probe.bptc(), probe.s3tc()));
    }

    @Test
    void missingExtensionsYieldNone() {
        final TextureCompressionSupport.ProbeResult probe = TextureCompressionSupport.parseExtensionSupport(
                "GL_ARB_texture_storage GL_EXT_texture_filter_anisotropic");

        assertFalse(probe.bptc());
        assertFalse(probe.s3tc());
        assertEquals(TextureCompressionSupport.Format.NONE,
                TextureCompressionSupport.decideFormat(true, "auto", probe.bptc(), probe.s3tc()));
        // null / 空串（glGetString 失败形态）同样按全不支持处理
        assertEquals(TextureCompressionSupport.Format.NONE,
                TextureCompressionSupport.decideFormat(true, "auto",
                        TextureCompressionSupport.parseExtensionSupport(null).bptc(),
                        TextureCompressionSupport.parseExtensionSupport(null).s3tc()));
    }

    @Test
    void substringLookalikesDoNotMatch() {
        final TextureCompressionSupport.ProbeResult probe = TextureCompressionSupport.parseExtensionSupport(
                "GL_ARB_texture_compression_bptc2 GL_EXT_texture_compression_s3tc_extra");

        assertFalse(probe.bptc());
        assertFalse(probe.s3tc());
    }

    @Test
    void disabledMasterSwitchYieldsNone() {
        assertEquals(TextureCompressionSupport.Format.NONE,
                TextureCompressionSupport.decideFormat(false, "auto", true, true));
        assertEquals(TextureCompressionSupport.Format.NONE,
                TextureCompressionSupport.decideFormat(false, "bc7", true, true));
    }

    @Test
    void forcedBc3NarrowsPreferredFormat() {
        // bc3 强制：即便 bc7 可用也返回 BC3
        assertEquals(TextureCompressionSupport.Format.BC3,
                TextureCompressionSupport.decideFormat(true, "bc3", true, true));
        // bc7 强制：无 bptc 能力时不静默降级，返回 NONE
        assertEquals(TextureCompressionSupport.Format.NONE,
                TextureCompressionSupport.decideFormat(true, "bc7", false, true));
        assertEquals(TextureCompressionSupport.Format.BC7,
                TextureCompressionSupport.decideFormat(true, "bc7", true, true));
        // 未知值按 auto 处理
        assertEquals(TextureCompressionSupport.Format.BC7,
                TextureCompressionSupport.decideFormat(true, "typo", true, false));
    }
}
