package github.kasuminova.ssoptimizer.bridge.opengl;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link CommandPool} 的借还语义验证：池空新建、归还复用、多线程并发借还
 * 不丢不重（录制线程借出与渲染线程归还的生产形态）、线程本地预借栈命中
 * 复用已归还对象且不触发新建。
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
    void localPrefetchReusesReleasedObjectsWithoutNewAllocation() {
        AtomicInteger created = new AtomicInteger();
        CommandPool<Object> pool = new CommandPool<>(() -> {
            created.incrementAndGet();
            return new Object();
        });
        // 预置 32 个空闲对象进全局池（本地栈满额度，单次补货即可全部预借）
        Set<Object> released = new HashSet<>();
        for (int i = 0; i < 32; i++) {
            released.add(pool.acquire());
        }
        for (Object object : released) {
            pool.release(object);
        }

        // 重新借出 32 个：首次触发批量预借（栈 32 → 取 1），其余全部命中本地栈
        Set<Object> acquired = new HashSet<>();
        for (int i = 0; i < 32; i++) {
            acquired.add(pool.acquire());
        }
        assertEquals(32, acquired.size(), "借出数量必须完整");
        assertEquals(32, created.get(), "本地预借命中时不得新建对象");
        assertEquals(released, acquired, "必须全部复用已归还的对象（不丢不重）");
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
        // 借还配对后全部借出成功（不丢）；release 全进全局池，线程本地栈
        // 可预借持有部分实例——全局池不变量为「不超过流通实例数」（不凭空多实例）
        assertEquals(threads * perThread, seen.size(), "每次借出都必须成功");
        distinct.set(Set.copyOf(seen).size());
        assertTrue(pool.idleCount() <= distinct.get(), "全局池不得持有超过流通量的实例");
    }
}
