package github.kasuminova.ssoptimizer.common.save;

import com.fs.starfarer.campaign.save.CampaignSaveProgressDialog;
import org.apache.log4j.Logger;
import org.lwjgl.opengl.GLContext;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 保存进度原版界面回放协调器。
 * <p>
 * 职责：在保存线程、后台 XML 写线程与持有 OpenGL 上下文的主线程之间中转保存进度状态，
 * 并在主线程上重放原版 {@link CampaignSaveProgressDialog} 的进度回调，以完整复用原版视觉与布局。<br>
 * 设计动机：原版将保存 UI 的 render 与 {@code OutputStream.write(...)} 紧耦合，导致一旦真实写出落到后台线程，
 * 就会在无 OpenGL 上下文的线程里调用渲染代码并崩溃。<br>
 * 兼容性策略：后台线程只更新状态，不直接触碰原版 UI；真正的原版界面回调只允许在当前线程持有 OpenGL
 * 上下文时发生，并且按固定帧率节流，避免重新落回“每写一点就重画一次”的旧模型。
 */
public final class SaveProgressOverlayCoordinator {
    private static final Logger LOGGER = Logger.getLogger(SaveProgressOverlayCoordinator.class);

    /**
    * 禁用保存进度原版界面回放的系统属性。
     */
    public static final String DISABLE_SAVE_OVERLAY_PROPERTY = "ssoptimizer.disable.save.progress.overlay";

    private static final long        MIN_RENDER_INTERVAL_NANOS = 33_000_000L;
    private static final long        PUMP_HINT_INTERVAL_MASK   = 0x7FL;
    private static final AtomicLong  UPDATE_SEQUENCE           = new AtomicLong();
    private static final AtomicLong  PUMP_HINT_COUNTER         = new AtomicLong();
    private static final ThreadLocal<Boolean> RENDER_GUARD     = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> REPLAY_GUARD     = ThreadLocal.withInitial(() -> false);

    private static volatile CampaignSaveProgressDialog dialog;
    private static volatile boolean active;
    private static volatile boolean completed;
    private static volatile boolean autoSave;
    private static volatile boolean lastProgressCallUsedText;
    private static volatile String  saveLabel;
    private static volatile String  statusText    = "保存中...";
    private static volatile long    totalBytes;
    private static volatile long    writtenBytes;
    private static volatile float   startProgress;
    private static volatile float   endProgress   = 1.0f;
    private static volatile float   explicitProgress = Float.NaN;
    private static volatile long    lastRenderedSequence;
    private static volatile long    lastRenderNanos;

    private SaveProgressOverlayCoordinator() {
    }

    /**
     * 开始一个新的保存写出阶段。
     *
     * @param expectedBytes  预计写出总字节数
     * @param phaseStart     该阶段在整体保存流程中的起始进度（0-1）
     * @param phaseEnd       该阶段在整体保存流程中的结束进度（0-1）
     */
    public static void beginStreamPhase(final CampaignSaveProgressDialog progressDialog,
                                        final long expectedBytes,
                                        final float phaseStart,
                                        final float phaseEnd) {
        if (Boolean.getBoolean(DISABLE_SAVE_OVERLAY_PROPERTY)) {
            return;
        }

        dialog = progressDialog != null ? progressDialog : dialog;
        active = true;
        completed = false;
        totalBytes = Math.max(1L, expectedBytes);
        writtenBytes = 0L;
        startProgress = clamp01(phaseStart);
        endProgress = Math.max(startProgress, clamp01(phaseEnd));
        explicitProgress = Float.NaN;
        if (statusText == null || statusText.isBlank()) {
            statusText = "保存中...";
        }
        UPDATE_SEQUENCE.incrementAndGet();
    }

    /**
     * 开始一个新的保存写出阶段。
     *
     * @param expectedBytes 预计写出总字节数
     * @param phaseStart    该阶段在整体保存流程中的起始进度（0-1）
     * @param phaseEnd      该阶段在整体保存流程中的结束进度（0-1）
     */
    public static void beginStreamPhase(final long expectedBytes,
                                        final float phaseStart,
                                        final float phaseEnd) {
        beginStreamPhase(null, expectedBytes, phaseStart, phaseEnd);
    }

    /**
     * 记录存档位标签与自动保存标记。
     *
     * @param label 原版保存 UI 传入的标签文本
     */
    public static void attachSaveLabel(final CampaignSaveProgressDialog progressDialog,
                                       final String label) {
        if (Boolean.getBoolean(DISABLE_SAVE_OVERLAY_PROPERTY)) {
            return;
        }

        dialog = progressDialog != null ? progressDialog : dialog;
        saveLabel = label;
        autoSave = label != null && label.toLowerCase(Locale.ROOT).contains("auto");
        UPDATE_SEQUENCE.incrementAndGet();
    }

    /**
     * 记录存档位标签与自动保存标记。
     *
     * @param label 原版保存 UI 传入的标签文本
     */
    public static void attachSaveLabel(final String label) {
        attachSaveLabel(null, label);
    }

    /**
     * 发布最新写出字节数。
     *
     * @param bytesWritten 截至当前已写出的字节数
     */
    public static void onBytesWritten(final long bytesWritten) {
        if (!active || Boolean.getBoolean(DISABLE_SAVE_OVERLAY_PROPERTY)) {
            return;
        }

        writtenBytes = Math.max(writtenBytes, bytesWritten);
        UPDATE_SEQUENCE.incrementAndGet();
    }

    /**
     * 发布原版保存 UI 提供的状态文本与进度值。
     *
     * @param text          状态文本，例如“保存中...”
     * @param progressValue 原版百分比值（0-100）
     */
    public static void reportProgress(final CampaignSaveProgressDialog progressDialog,
                                      final String text,
                                      final float progressValue) {
        if (Boolean.getBoolean(DISABLE_SAVE_OVERLAY_PROPERTY)) {
            return;
        }
        if (REPLAY_GUARD.get()) {
            return;
        }

        dialog = progressDialog != null ? progressDialog : dialog;
        active = true;
        completed = false;
        lastProgressCallUsedText = true;
        if (text != null && !text.isBlank()) {
            statusText = text;
        }
        explicitProgress = clamp01(progressValue / 100.0f);
        UPDATE_SEQUENCE.incrementAndGet();
    }

    /**
    * 从主线程热点调用点发出一次“可尝试刷新原版进度界面”的提示。
     * <p>
     * 该方法会做抽样节流，避免把每个 XML 事件都升级为一次真正的渲染尝试。
     */
    public static void reportProgress(final String text,
                                      final float progressValue) {
        reportProgress(null, text, progressValue);
    }

    /**
     * 发布原版保存 UI 提供的不带文本的进度值。
     *
     * @param progressDialog 原版对话框实例
     * @param progressValue  原版百分比值（0-100）
     */
    public static void reportProgress(final CampaignSaveProgressDialog progressDialog,
                                      final float progressValue) {
        if (Boolean.getBoolean(DISABLE_SAVE_OVERLAY_PROPERTY)) {
            return;
        }
        if (REPLAY_GUARD.get()) {
            return;
        }

        dialog = progressDialog != null ? progressDialog : dialog;
        active = true;
        completed = false;
        lastProgressCallUsedText = false;
        explicitProgress = clamp01(progressValue / 100.0f);
        UPDATE_SEQUENCE.incrementAndGet();
    }

    /**
     * 发布原版保存 UI 提供的不带文本的进度值。
     *
     * @param progressValue 原版百分比值（0-100）
     */
    public static void reportProgress(final float progressValue) {
        reportProgress((CampaignSaveProgressDialog) null, progressValue);
    }

    /**
    * 从主线程热点调用点发出一次“可尝试刷新原版进度界面”的提示。
     * <p>
     * 该方法会做抽样节流，避免把每个 XML 事件都升级为一次真正的渲染尝试。
     */
    public static void hintMainThreadPump() {
        if (Boolean.getBoolean(DISABLE_SAVE_OVERLAY_PROPERTY) || (!active && !completed)) {
            return;
        }
        if ((PUMP_HINT_COUNTER.incrementAndGet() & PUMP_HINT_INTERVAL_MASK) != 0L) {
            return;
        }
        maybePumpFrame();
    }

    /**
     * 标记当前保存阶段已完成，等待主线程绘制最后一帧后自动清理。
     */
    public static void complete() {
        if (Boolean.getBoolean(DISABLE_SAVE_OVERLAY_PROPERTY)) {
            return;
        }

        if (!active && !completed) {
            return;
        }

        writtenBytes = Math.max(writtenBytes, totalBytes);
        explicitProgress = 1.0f;
        completed = true;
        active = false;
        UPDATE_SEQUENCE.incrementAndGet();
    }

    /**
    * 若当前线程持有 OpenGL 上下文，则按节流策略重放一帧原版保存进度界面。
     */
    public static void maybePumpFrame() {
        if (Boolean.getBoolean(DISABLE_SAVE_OVERLAY_PROPERTY)) {
            return;
        }
        if ((!active && !completed) || !hasCurrentOpenGlContext() || RENDER_GUARD.get()) {
            return;
        }

        final CampaignSaveProgressDialog currentDialog = dialog;
        if (currentDialog == null) {
            return;
        }

        final long sequence = UPDATE_SEQUENCE.get();
        if (sequence == lastRenderedSequence && !completed) {
            return;
        }

        final long now = System.nanoTime();
        if (!completed && now - lastRenderNanos < MIN_RENDER_INTERVAL_NANOS) {
            return;
        }

        final SaveProgressSnapshot snapshot = snapshot();
        if (!snapshot.visible()) {
            return;
        }

        RENDER_GUARD.set(true);
        try {
            REPLAY_GUARD.set(true);
            final float originalProgress = snapshot.progress() * 100.0f;
            if (lastProgressCallUsedText) {
                currentDialog.reportProgress(snapshot.statusText(), originalProgress);
            } else {
                currentDialog.reportProgress(originalProgress);
            }
            lastRenderedSequence = sequence;
            lastRenderNanos = now;
            if (snapshot.completed()) {
                clearState();
            }
        } catch (final Throwable throwable) {
            LOGGER.error("[SSOptimizer] 保存/读档进度主线程重放原版界面失败，已自动回退为无 UI 保底模式", throwable);
            clearState();
        } finally {
            REPLAY_GUARD.set(false);
            RENDER_GUARD.set(false);
        }
    }

    /**
     * 判断当前线程是否正在重放原版保存/读档界面。
     *
     * @return 若当前线程处于原版对话框回放流程中则返回 {@code true}
     */
    public static boolean isReplayInProgress() {
        return REPLAY_GUARD.get();
    }

    /**
     * 判断当前线程是否持有可用于原版保存/读档界面渲染的 OpenGL 上下文。
     *
     * @return 若当前线程持有 OpenGL 上下文则返回 {@code true}
     */
    public static boolean hasActiveOpenGlContext() {
        return hasCurrentOpenGlContext();
    }

    static SaveProgressSnapshot snapshot() {
        final float resolvedProgress = resolveProgress();
        return new SaveProgressSnapshot(
                active || completed,
                completed,
                autoSave,
                saveLabel,
                statusText != null ? statusText : "保存中...",
                resolvedProgress,
                Math.max(0L, writtenBytes),
                Math.max(0L, totalBytes)
        );
    }

    static void resetForTests() {
        clearState();
    }

    private static float resolveProgress() {
        float progress = Float.isNaN(explicitProgress)
                ? interpolateByteProgress()
                : explicitProgress;
        final float byteProgress = interpolateByteProgress();
        if (!Float.isNaN(byteProgress)) {
            progress = Float.isNaN(progress) ? byteProgress : Math.max(progress, byteProgress);
        }
        return Float.isNaN(progress) ? 0.0f : clamp01(progress);
    }

    private static float interpolateByteProgress() {
        if (totalBytes <= 0L) {
            return Float.NaN;
        }
        final float ratio = clamp01((float) writtenBytes / (float) totalBytes);
        return startProgress + (endProgress - startProgress) * ratio;
    }

    private static boolean hasCurrentOpenGlContext() {
        try {
            return GLContext.getCapabilities() != null;
        } catch (final Throwable ignored) {
            return false;
        }
    }

    private static float clamp01(final float value) {
        if (value < 0.0f) {
            return 0.0f;
        }
        if (value > 1.0f) {
            return 1.0f;
        }
        return value;
    }

    private static void clearState() {
        active = false;
        completed = false;
        autoSave = false;
        lastProgressCallUsedText = false;
        dialog = null;
        saveLabel = null;
        statusText = "保存中...";
        totalBytes = 0L;
        writtenBytes = 0L;
        startProgress = 0.0f;
        endProgress = 1.0f;
        explicitProgress = Float.NaN;
        lastRenderedSequence = 0L;
        lastRenderNanos = 0L;
        PUMP_HINT_COUNTER.set(0L);
    }

    /**
    * 保存进度快照。
     *
    * @param visible      当前是否应显示保存/读档进度界面
     * @param completed    当前阶段是否已经完成并等待最后一帧收尾
     * @param autoSave     是否为自动保存
     * @param saveLabel    存档位标签
     * @param statusText   当前状态文本
     * @param progress     归一化进度（0-1）
     * @param writtenBytes 已写字节数
     * @param totalBytes   预计总字节数
     */
    record SaveProgressSnapshot(boolean visible,
                                boolean completed,
                                boolean autoSave,
                                String saveLabel,
                                String statusText,
                                float progress,
                                long writtenBytes,
                                long totalBytes) {
    }
}