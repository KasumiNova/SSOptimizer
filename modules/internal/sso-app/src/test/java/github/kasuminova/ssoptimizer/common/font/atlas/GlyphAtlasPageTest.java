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
 * {@link GlyphAtlasPage} 的纹理创建线程规则验证。
 * <p>
 * 规则：纹理创建走 {@code GlDispatch.allocate} 阻塞资源通道（内部 swapFrames
 * 切割当前录制帧），只允许主录制线程发起；CJK 预热 daemon 线程的 writeBitmap
 * 仅写 staging + 标脏（textureId 保持 0），非主录制线程的 flushDirty 只保留
 * 脏标记，纹理由主录制线程补齐。
 * <p>
 * GL 环境：桩队列的 allocate 通道发假纹理 id（不执行 callable，执行体会触碰
 * 真实 GL），submit 只记录命令。JUnit 工作线程是 RenderQueueImpl 类初始化线程，
 * 即主录制线程判定（{@code GlDispatch.isMainRecordingThread()}）为 true 的一侧；
 * 用例内新起的线程为非主录制线程。
 */
class GlyphAtlasPageTest {

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

    private static NativeGlyphBitmap solid4x4() {
        final int[] pixels = new int[16];
        Arrays.fill(pixels, 0xFFFFFFFF);
        return new NativeGlyphBitmap(4, 4, pixels, 0, 0, 0);
    }

    /** 在非主录制线程（新起线程）执行并 join，传播断言失败。 */
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

        // 主录制线程 flush 补齐：建纹理 + 整页回传
        page.flushDirty();
        assertTrue(page.textureId() != 0, "主录制线程 flush 时补建纹理");
        assertEquals(1, queue.allocateCalls.get());
        assertEquals(1, queue.submitted.size(), "整页标脏 → 一条上传命令");
    }

    @Test
    void flushDirtyOnNonMainRecordingThreadOnlyKeepsDirtyMarks() throws Exception {
        final GlyphAtlasPage page = new GlyphAtlasPage(64);
        page.writeBitmap(0, 0, solid4x4()); // 主录制线程：首写建纹理
        assertEquals(1, queue.allocateCalls.get());
        page.onContextRecreated(); // id 归零 + 整页标脏
        assertEquals(0, page.textureId());
        queue.submitted.clear();

        runOffMainThread(page::flushDirty);

        assertEquals(0, page.textureId(), "非主录制线程 flush 不建纹理");
        assertEquals(1, queue.allocateCalls.get(), "未触发新的 allocate");
        assertTrue(queue.submitted.isEmpty(), "未提交上传命令（脏标记保留）");

        page.flushDirty(); // 主录制线程：重建 + 上传
        assertTrue(page.textureId() != 0, "主录制线程 flush 重建纹理");
        assertEquals(2, queue.allocateCalls.get());
        assertEquals(1, queue.submitted.size());
    }
}
