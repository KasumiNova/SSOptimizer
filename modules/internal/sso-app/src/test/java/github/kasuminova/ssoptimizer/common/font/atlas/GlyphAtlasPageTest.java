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

class GlyphAtlasPageTest {

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

    private static NativeGlyphBitmap solid4x4() {
        final int[] pixels = new int[16];
        Arrays.fill(pixels, 0xFFFFFFFF);
        return new NativeGlyphBitmap(4, 4, pixels, 0, 0, 0);
    }

    private static void runOffMainThread(final Runnable task) throws Exception {
        final Throwable[] failure = new Throwable[1];
        final Thread thread = new Thread(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                failure[0] = t;
            }
        }, "GlyphAtlasPageTest-offMain");
        thread.start();
        thread.join();
        if (failure[0] != null) {
            throw new AssertionError("非主录制线程侧断言失败", failure[0]);
        }
    }

    @Test
    void writeBitmapOnNonMainRecordingThreadSkipsTextureCreation() throws Exception {
        final GlyphAtlasPage page = new GlyphAtlasPage(64);

        runOffMainThread(() -> page.writeBitmap(0, 0, solid4x4()));

        assertEquals(0, page.textureId(), "非主录制线程只写 staging + 标脏，不建纹理");
        assertEquals(0, queue.allocateCalls.get(), "非主录制线程不得触发阻塞资源申请");

        page.flushDirty();
        assertTrue(page.textureId() != 0, "主录制线程 flush 时补建纹理");
        assertEquals(1, queue.allocateCalls.get());
        assertEquals(1, queue.submitted.size(), "整页标脏 → 一条上传命令");
    }

    @Test
    void resetForReuseZeroesStaging() {
        final GlyphAtlasPage page = new GlyphAtlasPage(64);
        page.writeBitmap(0, 0, solid4x4());

        final byte[] written = page.stagingSnapshot();
        boolean hasNonZero = false;
        for (final byte b : written) {
            hasNonZero |= b != 0;
        }
        assertTrue(hasNonZero, "写入后 staging 应含非零字形像素");

        page.resetForReuse();

        final byte[] snapshot = page.stagingSnapshot();
        assertEquals(64 * 64, snapshot.length);
        for (int i = 0; i < snapshot.length; i++) {
            assertEquals(0, snapshot[i], "回收复用后 staging 必须全零（含 padding 区域），下标 " + i);
        }
    }

    @Test
    void flushDirtyOnNonMainRecordingThreadOnlyKeepsDirtyMarks() throws Exception {
        final GlyphAtlasPage page = new GlyphAtlasPage(64);
        page.writeBitmap(0, 0, solid4x4());
        assertEquals(1, queue.allocateCalls.get());
        page.onContextRecreated();
        assertEquals(0, page.textureId());
        queue.submitted.clear();

        runOffMainThread(page::flushDirty);

        assertEquals(0, page.textureId(), "非主录制线程 flush 不建纹理");
        assertEquals(1, queue.allocateCalls.get(), "未触发新的 allocate");
        assertTrue(queue.submitted.isEmpty(), "未提交上传命令（脏标记保留）");

        page.flushDirty();
        assertTrue(page.textureId() != 0, "主录制线程 flush 重建纹理");
        assertEquals(2, queue.allocateCalls.get());
        assertEquals(1, queue.submitted.size());
    }
}
