package github.kasuminova.ssoptimizer.common.save;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        MemoryRefResolveGuard.onEngineInstalled(oldEngine);
        assertTrue(MemoryRefResolveGuard.isUnsafeWindow());
        assertTrue(log.isEmpty());

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

        MemoryRefResolveGuard.onEngineInstalled(new Object());
        assertTrue(log.isEmpty());
    }

    @Test
    void engineInstallOutsideWindowIsIgnored() {
        final List<String> log = new ArrayList<>();

        assertFalse(MemoryRefResolveGuard.isUnsafeWindow());
        MemoryRefResolveGuard.onEngineInstalled(new Object());
        assertFalse(MemoryRefResolveGuard.isUnsafeWindow());

        MemoryRefResolveGuard.enterLoad(new Object());
        MemoryRefResolveGuard.recordSuppressed(new FakeMemory(log, "A", false));
        MemoryRefResolveGuard.onEngineInstalled(new Object());
        assertEquals(List.of("A"), log);
    }
}
