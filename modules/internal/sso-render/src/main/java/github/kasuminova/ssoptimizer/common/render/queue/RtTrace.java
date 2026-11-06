package github.kasuminova.ssoptimizer.common.render.queue;

import org.apache.log4j.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class RtTrace {
    public static final String ENABLED_PROPERTY = "ssoptimizer.debug.rttrace";
    public static final String SIGNAL_FILE_PROPERTY = "ssoptimizer.debug.rttrace.signal";
    public static final String DUMP_DIR_PROPERTY = "ssoptimizer.debug.rttrace.dumpDir";
    public static final String WATCH_TEX_PROPERTY = "ssoptimizer.debug.rttrace.watchtex";

    private static final int CAPACITY = 1 << 19;
    private static final int SLOT_MASK = CAPACITY - 1;

    private static final Logger LOGGER = Logger.getLogger(RtTrace.class);

    private static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "false"));
    private static final String SIGNAL_FILE = System.getProperty(SIGNAL_FILE_PROPERTY, "").trim();
    private static final String DUMP_DIR = System.getProperty(DUMP_DIR_PROPERTY, "").trim();
    private static final Set<Integer> WATCH_TEX =
            parseWatchTex(System.getProperty(WATCH_TEX_PROPERTY, ""));
    private static final Set<String> WATCH_LOGGED = ConcurrentHashMap.newKeySet();

    private static final Entry[] RING = new Entry[CAPACITY];
    private static final AtomicLong CURSOR = new AtomicLong();
    private static final AtomicLong FRAME = new AtomicLong();
    private static volatile long lastSignalMtime;

    private static final ThreadLocal<String> THREAD_NAME = ThreadLocal.withInitial(() -> Thread.currentThread().getName());

    static {
        for (int i = 0; i < CAPACITY; i++) {
            RING[i] = new Entry();
        }
        if (ENABLED) {
            LOGGER.info("[SSOptimizer] RtTrace 已启用：capacity=" + CAPACITY
                    + " signal=" + (SIGNAL_FILE.isEmpty() ? "<未配置>" : SIGNAL_FILE)
                    + " dumpDir=" + (DUMP_DIR.isEmpty() ? "<user.dir>" : DUMP_DIR)
                    + (WATCH_TEX.isEmpty() ? "" : " watchTex=" + WATCH_TEX));
            // shutdown hook 使用平台线程，避免关停阶段依赖虚拟线程调度器。
            Runtime.getRuntime().addShutdownHook(new Thread(() -> dump("shutdown"), "SSOptimizer-RtTrace-Dump"));
        }
    }

    private RtTrace() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static void trace(final String tag, final long p1, final long p2, final long p3,
                             final String extra) {
        if (!ENABLED) {
            return;
        }
        final long seq = CURSOR.getAndIncrement();
        final Entry entry = RING[(int) (seq & SLOT_MASK)];
        entry.seq = seq;
        entry.frame = FRAME.get();
        entry.nanos = System.nanoTime();
        entry.thread = THREAD_NAME.get();
        entry.tag = tag;
        entry.p1 = p1;
        entry.p2 = p2;
        entry.p3 = p3;
        entry.extra = extra;
    }

    public static void traceBindWatch(final int target, final int texture) {
        if (!ENABLED || WATCH_TEX.isEmpty() || !WATCH_TEX.contains(texture)) {
            return;
        }
        final String caller = summarizeCaller();
        trace("BIND_WATCH", target, texture, 0, caller);
        if (WATCH_LOGGED.add(texture + "@" + caller)) {
            LOGGER.info("[SSOptimizer][RtTrace] BIND_WATCH tex=" + texture + " target=" + target
                    + " thread=" + Thread.currentThread().getName() + " caller=" + caller);
        }
    }

    static Set<Integer> parseWatchTex(final String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptySet();
        }
        final Set<Integer> ids = new HashSet<>();
        for (String token : raw.split(",")) {
            final String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                ids.add(Integer.parseInt(trimmed));
            } catch (NumberFormatException e) {
                LOGGER.warn("[SSOptimizer] RtTrace 忽略非法 watchtex 项: " + trimmed);
            }
        }
        return ids.isEmpty() ? Collections.emptySet() : ids;
    }

    private static String summarizeCaller() {
        final StringBuilder caller = new StringBuilder(160);
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            final String cls = frame.getClassName();
            if (cls.startsWith("java.") || cls.startsWith("github.kasuminova.ssoptimizer")) {
                continue;
            }
            if (caller.length() > 0) {
                caller.append(" <= ");
            }
            caller.append(cls).append('.').append(frame.getMethodName());
            if (caller.length() > 220) {
                break;
            }
        }
        return caller.toString();
    }

    public static void frameBoundary() {
        if (!ENABLED) {
            return;
        }
        FRAME.incrementAndGet();
        if (SIGNAL_FILE.isEmpty()) {
            return;
        }
        try {
            final Path signal = Path.of(SIGNAL_FILE);
            if (!Files.exists(signal)) {
                return;
            }
            final long mtime = Files.getLastModifiedTime(signal).toMillis();
            if (mtime != lastSignalMtime) {
                lastSignalMtime = mtime;
                dump("signal");
            }
        } catch (IOException e) {
            LOGGER.warn("[SSOptimizer] RtTrace 信号文件检查失败", e);
        }
    }

    public static synchronized void dump(final String reason) {
        if (!ENABLED) {
            return;
        }
        final long total = CURSOR.get();
        final long begin = Math.max(0, total - CAPACITY);
        final Path dir = DUMP_DIR.isEmpty()
                ? Path.of(System.getProperty("user.dir", "."))
                : Path.of(DUMP_DIR);
        final Path out = dir.resolve("rttrace-" + System.currentTimeMillis() + ".txt");
        try {
            Files.createDirectories(dir);
            final StringBuilder sb = new StringBuilder(64 * (int) Math.min(total - begin, 4096));
            sb.append("# RtTrace dump reason=").append(reason)
                    .append(" events=").append(total - begin)
                    .append(" (total=").append(total).append(")\n");
            sb.append("# seq frame nanos thread tag p1 p2 p3 extra\n");
            final java.io.BufferedWriter writer = Files.newBufferedWriter(out);
            try (writer) {
                writer.write(sb.toString());
                for (long seq = begin; seq < total; seq++) {
                    final Entry entry = RING[(int) (seq & SLOT_MASK)];
                    if (entry.seq != seq) {
                        continue; // dump 与记录并发时跳过已被更新事件覆写的槽位。
                    }
                    writer.write(Long.toString(entry.seq));
                    writer.write(' ');
                    writer.write(Long.toString(entry.frame));
                    writer.write(' ');
                    writer.write(Long.toString(entry.nanos));
                    writer.write(' ');
                    writer.write(entry.thread);
                    writer.write(' ');
                    writer.write(entry.tag);
                    writer.write(' ');
                    writer.write(Long.toString(entry.p1));
                    writer.write(' ');
                    writer.write(Long.toString(entry.p2));
                    writer.write(' ');
                    writer.write(Long.toString(entry.p3));
                    if (entry.extra != null) {
                        writer.write(' ');
                        writer.write(entry.extra);
                    }
                    writer.write('\n');
                }
            }
            LOGGER.info("[SSOptimizer] RtTrace dump 完成: " + out + " events=" + (total - begin)
                    + " reason=" + reason);
        } catch (IOException e) {
            LOGGER.warn("[SSOptimizer] RtTrace dump 失败: " + out, e);
        }
    }

    public static String floatStats(final byte[] data) {
        if (data == null) {
            return "floats=0";
        }
        return floatStats(java.nio.ByteBuffer.wrap(data));
    }

    public static String floatStats(final java.nio.ByteBuffer data) {
        if (data == null || data.remaining() < 4) {
            return "floats=0";
        }
        final java.nio.FloatBuffer floats = data.duplicate()
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        final int n = floats.remaining();
        int zero = 0;
        float min = Float.POSITIVE_INFINITY;
        float max = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            final float v = floats.get(i);
            if (v == 0.0f) {
                zero++;
            }
            if (v < min) {
                min = v;
            }
            if (v > max) {
                max = v;
            }
        }
        return "floats=" + n + " zero=" + zero + " min=" + min + " max=" + max;
    }

    public static String hexPrefix(final byte[] data, final int maxBytes) {
        if (data == null || data.length == 0 || maxBytes <= 0) {
            return "hex=";
        }
        final char[] hex = "0123456789abcdef".toCharArray();
        final int n = Math.min(data.length, maxBytes);
        final StringBuilder sb = new StringBuilder(4 + n * 3);
        sb.append("hex=");
        for (int i = 0; i < n; i++) {
            final int b = data[i];
            sb.append(hex[(b >> 4) & 0xF]).append(hex[b & 0xF]);
            if (i + 1 < n) {
                sb.append(':');
            }
        }
        return sb.toString();
    }

    private static final class Entry {
        long seq = -1;
        long frame;
        long nanos;
        String thread;
        String tag;
        long p1;
        long p2;
        long p3;
        String extra;
    }
}
