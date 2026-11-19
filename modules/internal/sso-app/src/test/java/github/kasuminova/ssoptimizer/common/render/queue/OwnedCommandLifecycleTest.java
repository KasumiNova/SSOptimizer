package github.kasuminova.ssoptimizer.common.render.queue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class OwnedCommandLifecycleTest {
    private final RenderQueueImpl queue = new RenderQueueImpl(new FramePool(FramePool.DEFAULT_CAPACITY),
            new StallDetector(), RenderQueueImpl.GlErrorProbe.COMMAND, () -> 0);

    @AfterEach
    void shutdown() {
        queue.shutdown();
    }

    @Test
    void releasesExecutedCommandsAndDiscardsOnlyTheUnexecutedTail() {
        IllegalArgumentException commandFailure = new IllegalArgumentException("绘制失败");
        TrackedCommand first = new TrackedCommand();
        TrackedCommand failing = new TrackedCommand(() -> {
            throw commandFailure;
        });
        TrackedCommand tail = new TrackedCommand();
        queue.submit(first);
        queue.submit(failing);
        queue.submit(new ProbeSiteCommand(tail));
        queue.swapFrames();

        IllegalStateException failure = assertThrows(IllegalStateException.class, queue::swapFramesAndSync);

        assertSame(commandFailure, failure.getCause());
        assertEquals(List.of(Transition.EXECUTED, Transition.RELEASED), first.transitions());
        assertEquals(List.of(Transition.EXECUTED, Transition.RELEASED), failing.transitions());
        assertEquals(List.of(Transition.DISCARDED), tail.transitions());
    }

    @Test
    void retainsTheTailUntilAProbedFenceAllowsContinuation() throws InterruptedException {
        FrameFence fence = new FrameFenceImpl();
        TrackedCommand first = new TrackedCommand();
        TrackedCommand tail = new TrackedCommand();
        queue.submit(first);
        queue.submit(new ProbeSiteCommand(new WaitFenceCommand(fence)));
        queue.submit(tail);
        queue.swapFrames();
        queue.swapFramesAndSync();

        assertEquals(List.of(Transition.EXECUTED, Transition.RELEASED), first.transitions());
        assertEquals(List.of(), tail.transitions());
        fence.signal();
        assertTrue(tail.returned.await(5, TimeUnit.SECONDS));
        queue.waitUncounted(() -> {});
        assertEquals(List.of(Transition.EXECUTED, Transition.RELEASED), first.transitions());
        assertEquals(List.of(Transition.EXECUTED, Transition.RELEASED), tail.transitions());
    }

    @Test
    void shutdownDiscardsActiveTailSuspendedAndUnstartedFrames() throws Exception {
        TrackedCommand suspended = new TrackedCommand();
        queue.submit(new WaitFenceCommand(new FrameFenceImpl()));
        queue.submit(suspended);
        queue.swapFrames();
        queue.swapFramesAndSync();

        var running = new CompletableFuture<Thread>();
        var release = new CompletableFuture<Void>();
        TrackedCommand active = new TrackedCommand(() -> {
            running.complete(Thread.currentThread());
            release.join();
        });
        TrackedCommand activeTail = new TrackedCommand();
        TrackedCommand queued = new TrackedCommand();
        TrackedCommand recorded = new TrackedCommand();
        Thread consumer;
        try {
            queue.submit(active);
            queue.submit(activeTail);
            queue.swapFrames();
            consumer = running.get(5, TimeUnit.SECONDS);
            queue.submit(queued);
            queue.swapFrames();
            queue.submit(recorded);
        } finally {
            queue.shutdown();
            release.complete(null);
        }

        consumer.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(consumer.isAlive());
        assertEquals(List.of(Transition.EXECUTED, Transition.RELEASED), active.transitions());
        for (TrackedCommand command : List.of(activeTail, suspended, queued, recorded)) {
            assertEquals(List.of(Transition.DISCARDED), command.transitions());
        }
    }

    private enum Transition {
        EXECUTED,
        RELEASED,
        DISCARDED
    }

    private static final class TrackedCommand implements DiscardableGlCommand {
        private final Runnable body;
        private final ConcurrentLinkedQueue<Transition> transitions = new ConcurrentLinkedQueue<>();
        private final CountDownLatch returned = new CountDownLatch(1);

        TrackedCommand() {
            this(() -> {});
        }

        TrackedCommand(Runnable body) {
            this.body = body;
        }

        @Override
        public void execute() {
            transitions.add(Transition.EXECUTED);
            try {
                body.run();
            } finally {
                transitions.add(Transition.RELEASED);
                returned.countDown();
            }
        }

        @Override
        public void discard() {
            transitions.add(Transition.DISCARDED);
            returned.countDown();
        }

        List<Transition> transitions() {
            return List.copyOf(transitions);
        }
    }
}
