package github.kasuminova.ssoptimizer.common.combat.ai;

import com.fs.starfarer.api.combat.MissileAIPlugin;
import com.fs.starfarer.combat.entities.Missile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelAiDispatcherModAiLockTest {

    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void guidedWrapperWithModPluginWaitsForModScriptLock() throws Exception {
        ProbeMissileAI plugin = new ProbeMissileAI();
        Missile.GuidedMissileAIWrapper wrapped = new Missile.GuidedMissileAIWrapper(plugin);
        assertBlockedByModScriptLock(wrapped, plugin);
    }

    @Test
    void missileWrapperWithModPluginWaitsForModScriptLock() throws Exception {
        ProbeMissileAI plugin = new ProbeMissileAI();
        Missile.MissileAIWrapper wrapped = new Missile.MissileAIWrapper(plugin);
        assertBlockedByModScriptLock(wrapped, plugin);
    }

    @Test
    void missileWrapperWithNullPluginDoesNotTouchModScriptLock() throws Exception {
        Missile.MissileAIWrapper wrapped = new Missile.MissileAIWrapper(null);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = holdLock(holding, release);
        try {
            assertTrue(holding.await(5, TimeUnit.SECONDS), "lock holder failed to start");
            Future<?> dispatched = pool.submit(() -> wrapped.advance(0.1f));
            dispatched.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            holder.join(5000);
        }
    }

    private void assertBlockedByModScriptLock(com.fs.starfarer.combat.ai.AI wrapped, ProbeMissileAI plugin)
            throws Exception {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = holdLock(holding, release);
        try {
            assertTrue(holding.await(5, TimeUnit.SECONDS), "lock holder failed to start");
            Future<?> dispatched = pool.submit(() -> ParallelAiDispatcher.dispatch(wrapped, 0.1f));
            assertFalse(dispatched.isDone(), "mod missile AI must block on MOD_SCRIPT_LOCK");
            assertFalse(plugin.advanced, "advance must not run while lock is held");
            release.countDown();
            dispatched.get(5, TimeUnit.SECONDS);
            assertTrue(plugin.advanced, "advance must run after lock release");
        } finally {
            release.countDown();
            holder.join(5000);
        }
    }

    private Thread holdLock(CountDownLatch holding, CountDownLatch release) {
        Thread holder = new Thread(() -> {
            synchronized (ParallelAiDispatcher.MOD_SCRIPT_LOCK) {
                holding.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        holder.setDaemon(true);
        holder.start();
        return holder;
    }

    private static final class ProbeMissileAI implements MissileAIPlugin {
        private volatile boolean advanced;

        @Override
        public void advance(float amount) {
            advanced = true;
        }
    }
}
