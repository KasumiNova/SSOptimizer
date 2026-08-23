package github.kasuminova.ssoptimizer.common.loading;

import com.fs.graphics.TextureObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 热重传标记（{@link LazyTextureManager#noteCompressedTextureAvailable}）行为测试：
 * 匹配路径+源哈希的驻留条目被标记升级键；路径/哈希不匹配、开关关闭均不标记。
 * 实际 GL 重传（maybeUpgradeToCompressed）依赖 GL 上下文，由实机联调覆盖。
 */
class TextureCompressionHotReloadTest {
    @AfterEach
    void tearDown() {
        System.clearProperty(LazyTextureManager.HOT_RELOAD_PROPERTY);
    }

    @Test
    void marksMatchingResidentEntryWithUpgradeKey() {
        final TextureObject texture = new TextureObject(3553, -1, "graphics/ships/hotreload_a.png");
        LazyTextureManager.trackResidentTextureForTests(texture, "graphics/ships/hotreload_a.png");

        final int queueBefore = LazyTextureManager.pendingCompressionUpgradeCountForTests();
        final CompressedTextureCache.Key key = new CompressedTextureCache.Key(
                "test-hash", 64, 64, true, TextureCompressionSupport.Format.BC7, NativeTextureCompressor.QUALITY_NORMAL);
        LazyTextureManager.noteCompressedTextureAvailable("graphics/ships/hotreload_a.png", key);

        assertSame(key, LazyTextureManager.compressedUpgradeKeyForTests(texture));
        assertEquals(queueBefore + 1, LazyTextureManager.pendingCompressionUpgradeCountForTests(),
                "标记升级键的同时应入待升级队列（主动重传不依赖再次绑定）");
    }

    @Test
    void duplicateNotificationDoesNotDoubleEnqueue() {
        final TextureObject texture = new TextureObject(3553, -1, "graphics/ships/hotreload_dup.png");
        LazyTextureManager.trackResidentTextureForTests(texture, "graphics/ships/hotreload_dup.png");

        final int queueBefore = LazyTextureManager.pendingCompressionUpgradeCountForTests();
        final CompressedTextureCache.Key key = new CompressedTextureCache.Key(
                "test-hash", 64, 64, true, TextureCompressionSupport.Format.BC7, NativeTextureCompressor.QUALITY_NORMAL);
        LazyTextureManager.noteCompressedTextureAvailable("graphics/ships/hotreload_dup.png", key);
        LazyTextureManager.noteCompressedTextureAvailable("graphics/ships/hotreload_dup.png", key);

        assertEquals(queueBefore + 1, LazyTextureManager.pendingCompressionUpgradeCountForTests(),
                "重复通知不得产生重复队列项");
    }

    @Test
    void ignoresSourceHashMismatch() {
        final TextureObject texture = new TextureObject(3553, -1, "graphics/ships/hotreload_b.png");
        LazyTextureManager.trackResidentTextureForTests(texture, "graphics/ships/hotreload_b.png");

        final int queueBefore = LazyTextureManager.pendingCompressionUpgradeCountForTests();
        LazyTextureManager.noteCompressedTextureAvailable("graphics/ships/hotreload_b.png",
                new CompressedTextureCache.Key("other-hash", 64, 64, true,
                        TextureCompressionSupport.Format.BC7, NativeTextureCompressor.QUALITY_NORMAL));

        assertNull(LazyTextureManager.compressedUpgradeKeyForTests(texture));
        assertEquals(queueBefore, LazyTextureManager.pendingCompressionUpgradeCountForTests());
    }

    @Test
    void ignoresResourcePathMismatch() {
        final TextureObject texture = new TextureObject(3553, -1, "graphics/ships/hotreload_c.png");
        LazyTextureManager.trackResidentTextureForTests(texture, "graphics/ships/hotreload_c.png");

        final int queueBefore = LazyTextureManager.pendingCompressionUpgradeCountForTests();
        LazyTextureManager.noteCompressedTextureAvailable("graphics/ships/unrelated.png",
                new CompressedTextureCache.Key("test-hash", 64, 64, true,
                        TextureCompressionSupport.Format.BC7, NativeTextureCompressor.QUALITY_NORMAL));

        assertNull(LazyTextureManager.compressedUpgradeKeyForTests(texture));
        assertEquals(queueBefore, LazyTextureManager.pendingCompressionUpgradeCountForTests());
    }

    @Test
    void hotReloadSwitchDisablesMarking() {
        System.setProperty(LazyTextureManager.HOT_RELOAD_PROPERTY, "false");
        final TextureObject texture = new TextureObject(3553, -1, "graphics/ships/hotreload_d.png");
        LazyTextureManager.trackResidentTextureForTests(texture, "graphics/ships/hotreload_d.png");

        LazyTextureManager.noteCompressedTextureAvailable("graphics/ships/hotreload_d.png",
                new CompressedTextureCache.Key("test-hash", 64, 64, true,
                        TextureCompressionSupport.Format.BC7, NativeTextureCompressor.QUALITY_NORMAL));

        assertNull(LazyTextureManager.compressedUpgradeKeyForTests(texture));
    }

    @Test
    void normalizesLeadingSlashWhenMatching() {
        final TextureObject texture = new TextureObject(3553, -1, "graphics/ships/hotreload_e.png");
        LazyTextureManager.trackResidentTextureForTests(texture, "graphics/ships/hotreload_e.png");

        final CompressedTextureCache.Key key = new CompressedTextureCache.Key(
                "test-hash", 64, 64, false, TextureCompressionSupport.Format.BC7, NativeTextureCompressor.QUALITY_NORMAL);
        LazyTextureManager.noteCompressedTextureAvailable("/graphics/ships/hotreload_e.png", key);

        assertEquals(key, LazyTextureManager.compressedUpgradeKeyForTests(texture));
    }
}
