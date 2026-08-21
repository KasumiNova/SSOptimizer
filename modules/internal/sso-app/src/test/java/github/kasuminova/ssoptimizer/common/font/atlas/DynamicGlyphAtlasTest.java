package github.kasuminova.ssoptimizer.common.font.atlas;

import github.kasuminova.ssoptimizer.bridge.opengl.GL11;
import github.kasuminova.ssoptimizer.common.font.NativeGlyphBitmap;
import github.kasuminova.ssoptimizer.common.render.queue.GlCommand;
import github.kasuminova.ssoptimizer.common.render.queue.RenderFrame;
import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DynamicGlyphAtlas} / {@link GlyphAtlasPage} 的分配、淘汰与上传行为验证。
 * <p>
 * GL 环境：经 {@link GL11#install(RenderQueue)} 安装桩队列——资源申请通道返回
 * 假纹理 id（不执行 callable，执行体会触碰真实 GL），submit 只记录命令。
 */
class DynamicGlyphAtlasTest {

    private static final String FACE = "graphics/fonts/insignia15LTaa.fnt";

    /** 桩队列：allocate 通道发假纹理 id 并计数，submit 记录命令（不执行）。 */
    private static final class StubRenderQueue implements RenderQueue {
        final List<GlCommand> submitted = new ArrayList<>();
        final AtomicInteger nextTextureId = new AtomicInteger(100);
        final AtomicInteger allocateCalls = new AtomicInteger();
        private final RenderFrame frame = new RenderFrame();

        @Override
        public RenderFrame currentFrame() {
            return frame;
        }

        @Override
        public void submit(final GlCommand command) {
            submitted.add(command);
            frame.add(command);
        }

        @Override
        public void swapFrames() {
        }

        @Override
        public void swapFramesAndSync() {
        }

        @Override
        public <T> T get(final Callable<T> getter) {
            throw new UnsupportedOperationException("桩队列不支持 get");
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getUncounted(final Callable<T> getter) {
            allocateCalls.incrementAndGet();
            return (T) Integer.valueOf(nextTextureId.getAndIncrement());
        }

        @Override
        public void wait(final Runnable task) {
        }

        @Override
        public boolean isRenderThread() {
            return false;
        }
    }

    private StubRenderQueue queue;

    @BeforeEach
    void setUp() {
        queue = new StubRenderQueue();
        GL11.install(queue);
    }

    @AfterEach
    void tearDown() {
        GL11.uninstall();
    }

    /** 固定 4×4 实心位图的栅格化桩。 */
    private static GlyphRasterizer solid4x4() {
        return solid4x4(new AtomicInteger());
    }

    private static GlyphRasterizer solid4x4(final AtomicInteger callCount) {
        return (codePoint, baseline, strokeWidthPx) -> {
            callCount.incrementAndGet();
            final int[] pixels = new int[16];
            Arrays.fill(pixels, 0xFFFFFFFF);
            return new NativeGlyphBitmap(4, 4, pixels, 0, 0, 0);
        };
    }

    // ── shelf 分配 ──────────────────────────────────────────────────────

    @Test
    void shelfAllocationAdvancesWithOnePixelPadding() {
        final DynamicGlyphAtlas atlas = new DynamicGlyphAtlas(64, 16);
        final GlyphSlot first = atlas.request(FACE, 1000, 0, 0x4E00, 13, solid4x4());
        final GlyphSlot second = atlas.request(FACE, 1000, 0, 0x4E01, 13, solid4x4());

        assertEquals(0, first.x());
        assertEquals(0, first.y());
        assertEquals(5, second.x(), "第二个 cell 紧跟首个 + 1px padding");
        assertEquals(0, second.y());
        assertEquals(5f / 64f, second.texX(), 1e-6f);
        assertEquals(4f / 64f, second.texWidth(), 1e-6f);
        assertSame(first.page(), second.page());
    }

    @Test
    void rowFullStartsNewShelf() {
        final DynamicGlyphAtlas atlas = new DynamicGlyphAtlas(16, 16);
        // 每行 3 个（4+1 步进：0/5/10），第 4 个换 shelf：y = 4+1 = 5
        final GlyphSlot[] slots = new GlyphSlot[4];
        for (int i = 0; i < 4; i++) {
            slots[i] = atlas.request(FACE, 1000, 0, 0x4E00 + i, 13, solid4x4());
        }
        assertEquals(10, slots[2].x());
        assertEquals(0, slots[2].y());
        assertEquals(0, slots[3].x());
        assertEquals(5, slots[3].y(), "行满换 shelf，shelf 间同样 1px padding");
        assertEquals(1, atlas.pageCount());
    }

    @Test
    void pageFullOpensNewPage() {
        final DynamicGlyphAtlas atlas = new DynamicGlyphAtlas(9, 16);
        // 9px 页：4px 字形每行 2 个（x=0/5），两行（y=0/5），共 4 个后页满
        final GlyphSlot first = atlas.request(FACE, 1000, 0, 0x4E00, 13, solid4x4());
        for (int i = 1; i < 4; i++) {
            assertSame(first.page(), atlas.request(FACE, 1000, 0, 0x4E00 + i, 13, solid4x4()).page(),
                    "前 4 个字形同页");
        }
        final GlyphSlot fifth = atlas.request(FACE, 1000, 0, 0x4E04, 13, solid4x4());

        assertEquals(2, atlas.pageCount(), "页满开新页");
        assertNotSame(first.page(), fifth.page());
        assertEquals(0, fifth.x());
        assertEquals(0, fifth.y(), "新页从 (0,0) 开始分配");
    }

    // ── 缓存命中 / 失败 ──────────────────────────────────────────────────

    @Test
    void cacheHitSkipsRasterizationAndReturnsSameSlot() {
        final AtomicInteger calls = new AtomicInteger();
        final DynamicGlyphAtlas atlas = new DynamicGlyphAtlas(64, 16);
        final GlyphSlot first = atlas.request(FACE, 1000, 0, 0x4E00, 13, solid4x4(calls));
        final GlyphSlot second = atlas.request(FACE, 1000, 0, 0x4E00, 13, solid4x4(calls));

        assertEquals(1, calls.get(), "命中不重复栅格化");
        assertSame(first, second);
    }

    @Test
    void rasterizeFailureReturnsNullAndIsNotCached() {
        final AtomicInteger calls = new AtomicInteger();
        final GlyphRasterizer failing = (codePoint, baseline, strokeWidthPx) -> {
            calls.incrementAndGet();
            return null;
        };
        final DynamicGlyphAtlas atlas = new DynamicGlyphAtlas(64, 16);

        assertNull(atlas.request(FACE, 1000, 0, 0x4E00, 13, failing));
        assertNull(atlas.request(FACE, 1000, 0, 0x4E00, 13, failing));
        assertEquals(2, calls.get(), "失败不留缓存，下次重试栅格化");
    }

    @Test
    void differentBucketsAreDistinctSlots() {
        final DynamicGlyphAtlas atlas = new DynamicGlyphAtlas(64, 16);
        final GlyphSlot fill = atlas.request(FACE, 1000, 0, 0x4E00, 13, solid4x4());
        final GlyphSlot stroked = atlas.request(FACE, 1000, 500, 0x4E00, 13, solid4x4());
        final GlyphSlot otherSize = atlas.request(FACE, 2000, 0, 0x4E00, 26, solid4x4());

        assertNotSame(fill, stroked);
        assertNotSame(fill, otherSize);
        assertEquals(3, atlas.groupCount(), "同 face 不同 (bucket, stroke) 各占一组");
    }

    // ── LRU 整组淘汰 ────────────────────────────────────────────────────

    @Test
    void lruEvictsLeastRecentlyUsedGroupAndReleasesTextures() {
        final DynamicGlyphAtlas atlas = new DynamicGlyphAtlas(9, 2);
        // 组 A（bucket 1000）填满 2 页（9px 页每页 4 个 4px 字形）
        for (int i = 0; i < 5; i++) {
            atlas.request(FACE, 1000, 0, 0x4E00 + i, 13, solid4x4());
        }
        assertEquals(2, atlas.pageCount());
        final int commandsBeforeEvict = queue.submitted.size();

        // 组 B（bucket 1125）开页 → 总页数 3 > 2，淘汰最久未用的整组 A
        atlas.request(FACE, 1125, 0, 0x4E00, 13, solid4x4());

        assertEquals(0, atlas.slotCount(FACE, 1000, 0), "组 A 槽位整体清除");
        assertEquals(1, atlas.groupCount());
        assertEquals(1, atlas.pageCount());
        assertEquals(2, queue.submitted.size() - commandsBeforeEvict,
                "组 A 两页各提交一条纹理删除命令");
    }

    @Test
    void recentlyTouchedGroupSurvivesEviction() {
        final DynamicGlyphAtlas atlas = new DynamicGlyphAtlas(8, 2);
        // 8px 页每页只容 1 个 4px 字形：组 A 1 页、组 B 1 页；随后命中 A 使其成为最近使用
        atlas.request(FACE, 1000, 0, 0x4E00, 13, solid4x4());
        atlas.request(FACE, 1125, 0, 0x4E00, 13, solid4x4());
        atlas.request(FACE, 1000, 0, 0x4E00, 13, solid4x4());

        // 组 C 开页 → 淘汰最久未用的 B（而非最近命中的 A）
        atlas.request("graphics/fonts/orbitron20aa.fnt", 1000, 0, 0x4E00, 13, solid4x4());

        assertEquals(1, atlas.slotCount(FACE, 1000, 0), "最近命中的组 A 保留");
        assertEquals(0, atlas.slotCount(FACE, 1125, 0), "最久未用的组 B 被淘汰");
        assertEquals(2, atlas.groupCount());
    }

    // ── 脏矩形上传 / 上下文重建 ─────────────────────────────────────────

    @Test
    void flushDirtyUploadsOnceAndClears() {
        final DynamicGlyphAtlas atlas = new DynamicGlyphAtlas(64, 16);
        atlas.request(FACE, 1000, 0, 0x4E00, 13, solid4x4());

        atlas.flushDirty();
        final int afterFirstFlush = queue.submitted.size();
        assertEquals(1, afterFirstFlush, "首 flush 提交一条上传命令");

        atlas.flushDirty();
        assertEquals(afterFirstFlush, queue.submitted.size(), "脏队列已清空，二次 flush 无操作");
    }

    @Test
    void contextRecreatedResetsTextureIdAndKeepsSlots() {
        final DynamicGlyphAtlas atlas = new DynamicGlyphAtlas(64, 16);
        final GlyphSlot slot = atlas.request(FACE, 1000, 0, 0x4E00, 13, solid4x4());
        final int originalTexture = slot.textureId();
        assertTrue(originalTexture != 0, "首写即创建纹理（布局期烘焙 textureId 的前提）");

        atlas.onContextRecreated();
        assertEquals(0, slot.textureId(), "上下文重建后纹理 id 归零");

        atlas.flushDirty();
        final int rebuiltTexture = slot.textureId();
        assertTrue(rebuiltTexture != 0 && rebuiltTexture != originalTexture,
                "flush 重建新纹理");
        final GlyphSlot hit = atlas.request(FACE, 1000, 0, 0x4E00, 13, solid4x4());
        assertSame(slot, hit, "槽位保留（staging 数据仍在，不重新栅格化）");
        assertEquals(rebuiltTexture, hit.textureId());
    }

    // ── 命中路径的零纹理窗口（R3） ──────────────────────────────────────

    @Test
    void cacheHitWithZeroTextureIdRecreatesTextureOnMainThread() {
        final DynamicGlyphAtlas atlas = new DynamicGlyphAtlas(64, 16);
        final GlyphSlot slot = atlas.request(FACE, 1000, 0, 0x4E00, 13, solid4x4());
        final int allocatesBefore = queue.allocateCalls.get();
        atlas.onContextRecreated();
        assertEquals(0, slot.textureId(), "上下文重建后纹理 id 归零");

        final GlyphSlot hit = atlas.request(FACE, 1000, 0, 0x4E00, 13, solid4x4());

        assertSame(slot, hit, "命中路径不重新栅格化");
        assertTrue(hit.textureId() != 0,
                "主录制线程命中零 id 槽位时就地重建纹理（否则本帧 quad 烘焙到 0 号纹理）");
        assertEquals(allocatesBefore + 1, queue.allocateCalls.get(), "命中路径补一次 allocate");
    }

    @Test
    void requestOnNonMainRecordingThreadNeverAllocatesTexture() throws Exception {
        final DynamicGlyphAtlas atlas = new DynamicGlyphAtlas(64, 16);
        final GlyphSlot[] slot = new GlyphSlot[1];
        final Throwable[] failure = new Throwable[1];
        final Thread warmupLike = new Thread(() -> {
            try {
                // 预热线程形态：建槽 + 命中都不建纹理（allocate 会切割主线程录制帧）
                slot[0] = atlas.request(FACE, 1000, 0, 0x4E00, 13, solid4x4());
                atlas.request(FACE, 1000, 0, 0x4E00, 13, solid4x4());
            } catch (Throwable t) {
                failure[0] = t;
            }
        }, "DynamicGlyphAtlasTest-warmupLike");
        warmupLike.start();
        warmupLike.join();
        if (failure[0] != null) {
            throw new AssertionError("非主录制线程侧执行失败", failure[0]);
        }

        assertNotNull(slot[0]);
        assertEquals(0, slot[0].textureId(), "非主录制线程建槽不建纹理");
        assertEquals(0, queue.allocateCalls.get(), "全程零 allocate");

        // 主录制线程命中时补齐纹理
        final GlyphSlot hit = atlas.request(FACE, 1000, 0, 0x4E00, 13, solid4x4());
        assertTrue(hit.textureId() != 0, "主录制线程命中补齐纹理");
        assertEquals(1, queue.allocateCalls.get());
    }
}
