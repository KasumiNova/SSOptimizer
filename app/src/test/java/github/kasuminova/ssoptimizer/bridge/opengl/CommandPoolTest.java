package github.kasuminova.ssoptimizer.bridge.opengl;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link CommandPool} 的借还语义验证：池空新建、归还复用、多线程并发借还
 * 不丢不重（录制线程借出与渲染线程归还的生产形态）。
 */
class CommandPoolTest {

    @Test
    void acquireCreatesWhenEmptyAndReleaseEnablesReuse() {
        CommandPool<Object> pool = new CommandPool<>(Object::new);
        Object first = pool.acquire();
        assertNotNull(first);
        assertEquals(0, pool.idleCount());
        pool.release(first);
        assertEquals(1, pool.idleCount());
        assertSame(first, pool.acquire(), "归还后再次借出必须复用同一实例");
        assertEquals(0, pool.idleCount());
    }

    @Test
    void concurrentAcquireReleaseKeepsAccounting() throws InterruptedException {
        CommandPool<Object> pool = new CommandPool<>(Object::new);
        int threads = 4;
        int perThread = 500;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger distinct = new AtomicInteger();
        ConcurrentLinkedQueue<Object> seen = new ConcurrentLinkedQueue<>();
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            workers[t] = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < perThread; i++) {
                    Object item = pool.acquire();
                    seen.add(item);
                    pool.release(item);
                }
            });
            workers[t].start();
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(worker.isAlive());
        }
        // 借还配对后池内对象数 = 并发期间实际流通的不同实例数，且全部被归还
        distinct.set(Set.copyOf(seen).size());
        assertEquals(distinct.get(), pool.idleCount(), "借还配对后全部实例必须回到池中");
    }
}
