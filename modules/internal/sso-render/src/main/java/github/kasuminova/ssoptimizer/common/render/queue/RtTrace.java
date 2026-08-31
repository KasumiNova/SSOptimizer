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

/**
 * RT 命令流一次性诊断：环形缓冲记录「录制侧」关键事件（FBO 绑定、屏幕拷贝、blit、
 * 缓冲映射上传、instanced draw、fence 录制、帧边界、悬挂/续跑），用于对照
 * BoxUtil 多线程渲染管线的命令顺序与数据快照（调查扭曲反馈循环与 trail 节点错乱）。
 * <p>
 * 开关：{@code -Dssoptimizer.debug.rttrace=true}（默认关，关闭时每事件一次静态
 * boolean 检查，无分配无 IO）。取证时以信号文件触发 dump：每帧边界检查一次
 * {@code -Dssoptimizer.debug.rttrace.signal=<path>} 指向的文件 mtime，变化即把
 * 环形缓冲全量写到 {@code -Dssoptimizer.debug.rttrace.dumpDir=<dir>}（默认
 * {@code user.dir}）下的 {@code rttrace-<millis>.txt}，可多次触发。
 * <p>
 * 环形缓冲预分配 {@value #CAPACITY} 个槽位，记录时原位覆写（无逐事件分配）；
 * 条目序号为全局单调序，dump 时按序号还原时间线。
 */
public final class RtTrace {
    /** 总开关系统属性。 */
    public static final String ENABLED_PROPERTY = "ssoptimizer.debug.rttrace";
    /** 触发 dump 的信号文件系统属性。 */
    public static final String SIGNAL_FILE_PROPERTY = "ssoptimizer.debug.rttrace.signal";
    /** dump 输出目录系统属性。 */
    public static final String DUMP_DIR_PROPERTY = "ssoptimizer.debug.rttrace.dumpDir";
    /**
     * 定点监视的纹理 id 列表系统属性（逗号分隔，如 {@code 3588,3590}）。
     * {@code glBindTexture} 命中时额外记录一条 {@code BIND_WATCH} 事件，
     * extra 携带真实调用方摘要（跳过 java./ssoptimizer 框架帧）——用于定位
     * 「图集页纹理 id 被裸 UV 消费者绑定」的泄漏源（记录侧调用点即真实调用线程）。
     */
    public static final String WATCH_TEX_PROPERTY = "ssoptimizer.debug.rttrace.watchtex";

    private static final int CAPACITY = 1 << 19;
    private static final int SLOT_MASK = CAPACITY - 1;

    private static final Logger LOGGER = Logger.getLogger(RtTrace.class);

    private static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "false"));
    private static final String SIGNAL_FILE = System.getProperty(SIGNAL_FILE_PROPERTY, "").trim();
    private static final String DUMP_DIR = System.getProperty(DUMP_DIR_PROPERTY, "").trim();
    /** 定点监视的纹理 id 集合（空 = 不监视，watch 路径零开销）。 */
    private static final Set<Integer> WATCH_TEX =
            parseWatchTex(System.getProperty(WATCH_TEX_PROPERTY, ""));
    /** 已输出日志的 watch 命中（tex@caller 去重，防止日志刷屏）。 */
    private static final Set<String> WATCH_LOGGED = ConcurrentHashMap.newKeySet();

    private static final Entry[] RING = new Entry[CAPACITY];
    /** 全局事件序号（亦作 ring 游标）。 */
    private static final AtomicLong CURSOR = new AtomicLong();
    /** 帧序号（帧边界事件递增，事件条目携带以定位所属帧）。 */
    private static final AtomicLong FRAME = new AtomicLong();
    /** 上次触发 dump 的信号文件 mtime（0 = 未触发）。 */
    private static volatile long lastSignalMtime;

    /** 线程名缓存（事件记录热路径不做 Thread.getName 字符串拼接之外的反射/锁）。 */
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
            // 进程退出时兜底 dump 一次（烟测脚本 TERM 关停可走 shutdown hook）；
            // hook 有意保留平台线程（Wave 3 不迁移）：关停阶段虚拟线程调度器已停用
            Runtime.getRuntime().addShutdownHook(new Thread(() -> dump("shutdown"), "SSOptimizer-RtTrace-Dump"));
        }
    }

    private RtTrace() {
    }

    /** @return 诊断是否启用（关闭时调用点应立即返回，不得构造 payload） */
    public static boolean enabled() {
        return ENABLED;
    }

    /**
     * 记录一条事件。
     *
     * @param tag   事件类型（短串，如 BIND_FBO / COPY_TEX / BUF_UPLOAD）
     * @param p1    负载 1（如 target / vbo / framebuffer id）
     * @param p2    负载 2（如 offset / width / count）
     * @param p3    负载 3（如 length / height / primcount）
     * @param extra 附加文本（内容统计等；无则传 null）
     */
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

    /**
     * 定点纹理绑定监视：{@code texture} 命中 {@link #WATCH_TEX_PROPERTY} 列表时，
     * 记录一条 {@code BIND_WATCH} 事件（extra = 调用方摘要），并对每个
     * 「纹理 id × 调用方」组合输出一次 INFO 日志。
     * 关闭或未配置时仅一次集合空检查，立即返回。
     *
     * @param target  绑定目标（如 GL_TEXTURE_2D）
     * @param texture 被绑定的纹理 id
     */
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

    /**
     * 解析 {@link #WATCH_TEX_PROPERTY} 的逗号分隔 id 列表；非法项记 warn 跳过。
     * 包私有以便单测直接验证解析逻辑。
     */
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

    /** 调用方摘要：跳过 java./ssoptimizer 框架帧后的应用层调用链（长度受限）。 */
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

    /**
     * 帧边界：帧序号递增并轮询信号文件（每帧一次 {@link Files#getLastModifiedTime}
     * 级别开销，仅启用时发生）。由队列提交帧时调用。
     */
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

    /** 把环形缓冲内容按事件序 dump 到文本文件。 */
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
                        continue; // 槽位已被更新事件覆写（dump 与记录并发时跳过）
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

    /**
     * byte[] 快照按小端 float 解读的内容统计（缓冲上传取证：零值段/越界值探测）。
     *
     * @return {@code floats=<n> zero=<零值数> min=<最小> max=<最大>}，空数据返回 {@code floats=0}
     */
    public static String floatStats(final byte[] data) {
        if (data == null) {
            return "floats=0";
        }
        return floatStats(java.nio.ByteBuffer.wrap(data));
    }

    /**
     * {@link ByteBuffer} 原位（duplicate，不动 position/limit）按小端 float 解读的内容统计，
     * 语义同 {@link #floatStats(byte[])}；供 glBufferSubData 等直传 buffer 的上传点取证。
     */
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

    /**
     * byte[] 前缀的十六进制转储（取证：区分「数据未写入/字节错位/字节序翻转」）。
     *
     * @return {@code hex=<aa:bb:…>}，空数据返回 {@code hex=}；最多转 {@code maxBytes} 字节
     */
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

    /** 环形槽位（预分配，原位覆写）。 */
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
