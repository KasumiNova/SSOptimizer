package github.kasuminova.ssoptimizer.common.loading;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ResourceLoaderThreadStateTest {

    @Test
    void sourceFilterSetConsumeSequenceMatchesVanillaSemantics() {
        ResourceLoaderThreadState.setSourceFilter("mod_dir");
        assertEquals("mod_dir", ResourceLoaderThreadState.getSourceFilter(),
                "同线程 set 后必须可读（对应 openResource 开头的读取）");

        ResourceLoaderThreadState.setSourceFilter(null);
        assertNull(ResourceLoaderThreadState.getSourceFilter(), "set(null) 后必须恢复未设置状态");
    }

    @Test
    void sourceFilterIsNotVisibleAcrossThreads() throws InterruptedException {
        ResourceLoaderThreadState.setSourceFilter("thread_a_filter");

        final AtomicReference<String> seenByOtherThread = new AtomicReference<>("unset");
        final Thread other = new Thread(
                () -> seenByOtherThread.set(ResourceLoaderThreadState.getSourceFilter()));
        other.start();
        other.join();

        assertNull(seenByOtherThread.get(), "其他线程不得看到本线程设置的 source filter");
        assertEquals("thread_a_filter", ResourceLoaderThreadState.getSourceFilter(),
                "本线程的 filter 不受其他线程影响");

        ResourceLoaderThreadState.setSourceFilter(null);
    }

    @Test
    void sourceFilterWrittenByOtherThreadDoesNotLeak() throws InterruptedException {
        final Thread other = new Thread(() -> ResourceLoaderThreadState.setSourceFilter("leaked"));
        other.start();
        other.join();

        assertNull(ResourceLoaderThreadState.getSourceFilter(),
                "其他线程写入的 filter 不得泄漏到本线程（并行加载干扰修复目标）");
    }

    @Test
    void suppressCustomResourcesDefaultsFalseAndConsumes() {
        assertFalse(ResourceLoaderThreadState.isSuppressCustomResources(), "缺省必须为 false");

        ResourceLoaderThreadState.setSuppressCustomResources(true);
        assertTrue(ResourceLoaderThreadState.isSuppressCustomResources());

        ResourceLoaderThreadState.setSuppressCustomResources(false);
        assertFalse(ResourceLoaderThreadState.isSuppressCustomResources(),
                "消费置 false 后必须恢复缺省");
    }

    @Test
    void suppressCustomResourcesIsNotVisibleAcrossThreads() throws InterruptedException {
        ResourceLoaderThreadState.setSuppressCustomResources(true);

        final AtomicReference<Boolean> seenByOtherThread = new AtomicReference<>();
        final Thread other = new Thread(
                () -> seenByOtherThread.set(ResourceLoaderThreadState.isSuppressCustomResources()));
        other.start();
        other.join();

        assertEquals(Boolean.FALSE, seenByOtherThread.get(),
                "其他线程不得看到本线程的 suppress 标记");

        ResourceLoaderThreadState.setSuppressCustomResources(false);
    }
}
