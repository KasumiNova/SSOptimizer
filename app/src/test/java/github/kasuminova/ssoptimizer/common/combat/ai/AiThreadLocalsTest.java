package github.kasuminova.ssoptimizer.common.combat.ai;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AiThreadLocalsTest {

    @Test
    void blockingShipsRoundTripWithinFrame() {
        assertNull(AiThreadLocals.getBlockingShips());
        List<String> list = new ArrayList<>();
        AiThreadLocals.setBlockingShips(list);
        assertSame(list, AiThreadLocals.getBlockingShips());
        AiThreadLocals.setBlockingShips(null);
        assertNull(AiThreadLocals.getBlockingShips());
    }

    @Test
    void nextFrameInvalidatesBlockingShipsCache() {
        AiThreadLocals.setBlockingShips(new ArrayList<>());
        assertNotNull(AiThreadLocals.getBlockingShips());
        AiThreadLocals.nextFrame();
        assertNull(AiThreadLocals.getBlockingShips());
    }

    @Test
    void aimErrorOffsetsRoundTrip() {
        AiThreadLocals.setAimErrorOffset1(1.5F);
        AiThreadLocals.setAimErrorOffset2(-2.5F);
        assertEquals(1.5F, AiThreadLocals.getAimErrorOffset1());
        assertEquals(-2.5F, AiThreadLocals.getAimErrorOffset2());
        AiThreadLocals.setAimErrorOffset1(0.0F);
        AiThreadLocals.setAimErrorOffset2(0.0F);
    }

    @Test
    void nullFlagRoundTrip() {
        assertFalse(AiThreadLocals.getNullFlag());
        AiThreadLocals.setNullFlag(true);
        assertTrue(AiThreadLocals.getNullFlag());
        AiThreadLocals.setNullFlag(false);
    }

    @Test
    void stateIsolatedAcrossThreads() throws InterruptedException {
        AiThreadLocals.setAimErrorOffset1(42.0F);
        AiThreadLocals.setNullFlag(true);
        AiThreadLocals.setBlockingShips(new ArrayList<>());

        boolean[] workerObserved = new boolean[3];
        CountDownLatch done = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            workerObserved[0] = AiThreadLocals.getAimErrorOffset1() == 0.0F;
            workerObserved[1] = !AiThreadLocals.getNullFlag();
            workerObserved[2] = AiThreadLocals.getBlockingShips() == null;
            done.countDown();
        });
        worker.start();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertTrue(workerObserved[0] && workerObserved[1] && workerObserved[2]);

        AiThreadLocals.setAimErrorOffset1(0.0F);
        AiThreadLocals.setNullFlag(false);
        AiThreadLocals.setBlockingShips(null);
    }
}
