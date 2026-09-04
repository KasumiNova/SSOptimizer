package github.kasuminova.ssoptimizer.common.save;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MemoryRefResolveGuard} 读档窗口状态机测试。
 * <p>
 * 验证：窗口内外标志流转、异常路径（恢复旧引擎）不触发重放、
 * 成功路径（装新引擎）按序重放全部挂起解析、单个重放失败不阻断其余、
 * {@code loadFinished} 清理丢弃挂起记录。
 */
class MemoryRefResolveGuardTest {

    private static final class FakeMemory implements MemoryRefResolveGuard.PendingResolution {
        private final List<String> log;
        private final String name;
        private final boolean fail;

        private FakeMemory(final List<String> log, final String name, final boolean fail) {
            this.log = log;
            this.name = name;
            this.fail = fail;
        }

        @Override
        public void ssoptimizer$rerunResolution() {
            log.add(name);
            if (fail) {
                throw new IllegalStateException("模拟损坏 Memory");
            }
        }
    }

    @AfterEach
    void resetGuard() {
        MemoryRefResolveGuard.loadFinished();
    }

    @Test
    void windowOpensAtEnterLoadAndClosesOnNewEngineInstall() {
        final Object oldEngine = new Object();
        final Object newEngine = new Object();

        assertFalse(MemoryRefResolveGuard.isUnsafeWindow());
        MemoryRefResolveGuard.enterLoad(oldEngine);
        assertTrue(MemoryRefResolveGuard.isUnsafeWindow());

        MemoryRefResolveGuard.onEngineInstalled(newEngine);
        assertFalse(MemoryRefResolveGuard.isUnsafeWindow());
    }

    @Test
    void reinstallingHeadEngineKeepsWindowOpen() {
        final Object oldEngine = new Object();
        final Object newEngine = new Object();
        final List<String> log = new ArrayList<>();

        MemoryRefResolveGuard.enterLoad(oldEngine);
        MemoryRefResolveGuard.recordSuppressed(new FakeMemory(log, "A", false));

        // 异常路径恢复旧引擎：窗口保持，不重放
        MemoryRefResolveGuard.onEngineInstalled(oldEngine);
        assertTrue(MemoryRefResolveGuard.isUnsafeWindow());
        assertTrue(log.isEmpty());

        // 重试成功后安装新引擎：窗口关闭并重放
        MemoryRefResolveGuard.onEngineInstalled(newEngine);
        assertFalse(MemoryRefResolveGuard.isUnsafeWindow());
        assertEquals(List.of("A"), log);
    }

    @Test
    void suppressedMemoriesReplayInOrderAfterNewEngineInstall() {
        final List<String> log = new ArrayList<>();

        MemoryRefResolveGuard.enterLoad(new Object());
        MemoryRefResolveGuard.recordSuppressed(new FakeMemory(log, "A", false));
        MemoryRefResolveGuard.recordSuppressed(new FakeMemory(log, "B", false));
        MemoryRefResolveGuard.recordSuppressed(new FakeMemory(log, "C", false));
        assertTrue(log.isEmpty());

        MemoryRefResolveGuard.onEngineInstalled(new Object());
        assertEquals(List.of("A", "B", "C"), log);
    }

    @Test
    void failingReplayDoesNotBlockRemainingMemories() {
        final List<String> log = new ArrayList<>();

        MemoryRefResolveGuard.enterLoad(new Object());
        MemoryRefResolveGuard.recordSuppressed(new FakeMemory(log, "A", false));
        MemoryRefResolveGuard.recordSuppressed(new FakeMemory(log, "B", true));
        MemoryRefResolveGuard.recordSuppressed(new FakeMemory(log, "C", false));

        MemoryRefResolveGuard.onEngineInstalled(new Object());
        assertEquals(List.of("A", "B", "C"), log);
    }

    @Test
    void loadFinishedDiscardsPendingWithoutReplay() {
        final List<String> log = new ArrayList<>();

        MemoryRefResolveGuard.enterLoad(new Object());
        MemoryRefResolveGuard.recordSuppressed(new FakeMemory(log, "A", false));

        MemoryRefResolveGuard.loadFinished();
        assertFalse(MemoryRefResolveGuard.isUnsafeWindow());
        assertTrue(log.isEmpty());

        // 清理后迟到的引擎安装事件不得再触发任何重放
        MemoryRefResolveGuard.onEngineInstalled(new Object());
        assertTrue(log.isEmpty());
    }

    @Test
    void engineInstallOutsideWindowIsIgnored() {
        final List<String> log = new ArrayList<>();

        assertFalse(MemoryRefResolveGuard.isUnsafeWindow());
        MemoryRefResolveGuard.onEngineInstalled(new Object());
        assertFalse(MemoryRefResolveGuard.isUnsafeWindow());

        // 窗口外 normal save/reload 流程的安装事件不影响后续窗口
        MemoryRefResolveGuard.enterLoad(new Object());
        MemoryRefResolveGuard.recordSuppressed(new FakeMemory(log, "A", false));
        MemoryRefResolveGuard.onEngineInstalled(new Object());
        assertEquals(List.of("A"), log);
    }
}
