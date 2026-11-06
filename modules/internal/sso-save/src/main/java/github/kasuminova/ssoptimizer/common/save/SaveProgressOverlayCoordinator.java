package github.kasuminova.ssoptimizer.common.save;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.campaign.save.CampaignSaveProgressDialog;
import github.kasuminova.ssoptimizer.api.render.RenderFramePump;
import github.kasuminova.ssoptimizer.bootstrap.ServiceRegistry;
import github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode;
import org.apache.log4j.Logger;
import org.json.JSONObject;
import org.lwjgl.LWJGLException;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GLContext;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/** 后台线程仅发布进度，原版 UI 回调仅在持有 GL 上下文的线程重放。 */
public final class SaveProgressOverlayCoordinator {
    private static final Logger LOGGER = Logger.getLogger(SaveProgressOverlayCoordinator.class);

    private static final long DEFAULT_MIN_RENDER_INTERVAL_NANOS = 33_000_000L;
    private static final long RENDER_INTERVAL_CACHE_NANOS = 1_000_000_000L;
    private static final int  MIN_TARGET_FPS = 30;
    private static final int  MAX_TARGET_FPS = 240;

    public static final String DISABLE_SAVE_OVERLAY_PROPERTY = "ssoptimizer.disable.save.progress.overlay";

    public static final String SAVE_OVERLAY_FPS_OVERRIDE_PROPERTY = "ssoptimizer.save.progress.fps";

    private static final long        PUMP_HINT_INTERVAL_MASK   = 0x7FL;
    private static final AtomicLong  UPDATE_SEQUENCE           = new AtomicLong();
    private static final AtomicLong  PUMP_HINT_COUNTER         = new AtomicLong();
    private static final ThreadLocal<Boolean> RENDER_GUARD     = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> REPLAY_GUARD     = ThreadLocal.withInitial(() -> false);
    private static final Object      CURRENT_REFRESH_LOCK      = new Object();

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
    private static volatile long    lastPumpHintNanos;
    private static volatile long    cachedRenderIntervalExpiresAtNanos = Long.MIN_VALUE;
    private static volatile long    cachedRenderIntervalNanos = DEFAULT_MIN_RENDER_INTERVAL_NANOS;

    private SaveProgressOverlayCoordinator() {
    }

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
        lastPumpHintNanos = 0L;
        if (statusText == null || statusText.isBlank()) {
            statusText = "保存中...";
        }
        UPDATE_SEQUENCE.incrementAndGet();
    }

    public static void beginStreamPhase(final long expectedBytes,
                                        final float phaseStart,
                                        final float phaseEnd) {
        beginStreamPhase(null, expectedBytes, phaseStart, phaseEnd);
    }

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

    public static void attachSaveLabel(final String label) {
        attachSaveLabel(null, label);
    }

    public static void onBytesWritten(final long bytesWritten) {
        if (!active || Boolean.getBoolean(DISABLE_SAVE_OVERLAY_PROPERTY)) {
            return;
        }

        writtenBytes = Math.max(writtenBytes, bytesWritten);
        UPDATE_SEQUENCE.incrementAndGet();
    }

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

    public static void reportProgress(final String text,
                                      final float progressValue) {
        reportProgress(null, text, progressValue);
    }

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

    public static void reportProgress(final float progressValue) {
        reportProgress((CampaignSaveProgressDialog) null, progressValue);
    }

    public static void hintMainThreadPump() {
        if (Boolean.getBoolean(DISABLE_SAVE_OVERLAY_PROPERTY) || (!active && !completed)) {
            return;
        }

        final long now = System.nanoTime();
        final long minRenderIntervalNanos = minimumRenderIntervalNanos();
        if (completed || now - lastPumpHintNanos >= minRenderIntervalNanos) {
            lastPumpHintNanos = now;
            maybePumpFrame();
            return;
        }

        if ((PUMP_HINT_COUNTER.incrementAndGet() & PUMP_HINT_INTERVAL_MASK) != 0L) {
            return;
        }
        lastPumpHintNanos = now;
        maybePumpFrame();
    }

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

    public static void maybePumpFrame() {
        if (Boolean.getBoolean(DISABLE_SAVE_OVERLAY_PROPERTY)) {
            return;
        }
        if ((!active && !completed) || RENDER_GUARD.get()) {
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
        final long minRenderIntervalNanos = minimumRenderIntervalNanos();
        if (!completed && now - lastRenderNanos < minRenderIntervalNanos) {
            return;
        }

        final SaveProgressSnapshot snapshot = snapshot();
        if (!snapshot.visible()) {
            return;
        }

        if (RenderThreadMode.isEnabled()) {
            // RT 的 GL 上下文与 ThreadLocal 防重入守卫都必须位于渲染线程。
            try {
                ServiceRegistry.require(RenderFramePump.class)
                        .runOnRenderThread(() -> replayFrame(currentDialog, snapshot, sequence, now));
            } catch (final RuntimeException throwable) {
                LOGGER.error("[SSOptimizer] 保存/读档进度渲染线程回放调度失败，已自动回退为无 UI 保底模式", throwable);
                clearState();
            }
            return;
        }

        if (!hasCurrentOpenGlContext()) {
            return;
        }
        replayFrame(currentDialog, snapshot, sequence, now);
    }

    private static void replayFrame(final CampaignSaveProgressDialog currentDialog,
                                    final SaveProgressSnapshot snapshot,
                                    final long sequence,
                                    final long now) {
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
        } catch (final RuntimeException throwable) {
            LOGGER.error("[SSOptimizer] 保存/读档进度重放原版界面失败，已自动回退为无 UI 保底模式", throwable);
            clearState();
        } finally {
            REPLAY_GUARD.set(false);
            RENDER_GUARD.set(false);
        }
    }

    public static boolean isReplayInProgress() {
        return REPLAY_GUARD.get();
    }

    /** RT 下 bridge 的 GLContext 查询不能代表当前线程持有上下文。 */
    public static boolean hasActiveOpenGlContext() {
        if (RenderThreadMode.isEnabled()) {
            return false;
        }
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
        resetRefreshRateCacheForTests();
    }

    static DisplayRefreshConfig readDisplayRefreshConfig() {
        try {
            final SettingsAPI settings = Global.getSettings();
            if (settings == null) {
                return DisplayRefreshConfig.DEFAULT;
            }

            final boolean vsync = settings.getBoolean("vsync");
            final int fps = settings.getInt("fps");
            final JSONObject settingsJson = settings.getSettingsJSON();
            final int refreshRateOverride = settingsJson != null
                    ? settingsJson.optInt("refreshRateOverride", 0)
                    : 0;
            return new DisplayRefreshConfig(vsync, fps, refreshRateOverride);
        } catch (final RuntimeException e) {
            LOGGER.error("[SSOptimizer] 读取保存进度界面刷新配置失败", e);
            throw e;
        }
    }

    static long resolveRenderIntervalNanos(final DisplayRefreshConfig config) {
        final int propertyOverride = Integer.getInteger(SAVE_OVERLAY_FPS_OVERRIDE_PROPERTY, 0);
        if (propertyOverride > 0) {
            return intervalForFramesPerSecond(propertyOverride);
        }

        if (config == null) {
            return DEFAULT_MIN_RENDER_INTERVAL_NANOS;
        }

        final int configuredRefreshRate = config.vsyncEnabled() && config.refreshRateOverride() > 0
                ? config.refreshRateOverride()
                : config.framesPerSecond();
        if (configuredRefreshRate <= 0) {
            return DEFAULT_MIN_RENDER_INTERVAL_NANOS;
        }

        return intervalForFramesPerSecond(configuredRefreshRate);
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

    private static long minimumRenderIntervalNanos() {
        final long now = System.nanoTime();
        if (now < cachedRenderIntervalExpiresAtNanos) {
            return cachedRenderIntervalNanos;
        }

        synchronized (CURRENT_REFRESH_LOCK) {
            final long refreshedNow = System.nanoTime();
            if (refreshedNow < cachedRenderIntervalExpiresAtNanos) {
                return cachedRenderIntervalNanos;
            }

            long resolvedIntervalNanos;
            try {
                resolvedIntervalNanos = resolveRenderIntervalNanos(readDisplayRefreshConfig());
            } catch (final RuntimeException e) {
                LOGGER.warn("[SSOptimizer] 保存进度界面刷新配置不可用，本周期使用默认刷新间隔", e);
                resolvedIntervalNanos = DEFAULT_MIN_RENDER_INTERVAL_NANOS;
            }
            cachedRenderIntervalNanos = resolvedIntervalNanos;
            cachedRenderIntervalExpiresAtNanos = refreshedNow + RENDER_INTERVAL_CACHE_NANOS;
            return cachedRenderIntervalNanos;
        }
    }

    private static boolean hasCurrentOpenGlContext() {
        if (!Display.isCreated()) {
            return false;
        }
        try {
            return Display.isCurrent() && GLContext.getCapabilities() != null;
        } catch (final LWJGLException | RuntimeException e) {
            LOGGER.warn("[SSOptimizer] 检查保存进度界面 OpenGL 上下文失败", e);
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

    private static long intervalForFramesPerSecond(final int framesPerSecond) {
        final int clampedFps = Math.max(MIN_TARGET_FPS, Math.min(framesPerSecond, MAX_TARGET_FPS));
        return Math.max(1L, 1_000_000_000L / clampedFps);
    }

    private static void resetRefreshRateCacheForTests() {
        cachedRenderIntervalExpiresAtNanos = Long.MIN_VALUE;
        cachedRenderIntervalNanos = DEFAULT_MIN_RENDER_INTERVAL_NANOS;
        lastPumpHintNanos = 0L;
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
        lastPumpHintNanos = 0L;
        PUMP_HINT_COUNTER.set(0L);
    }

    record DisplayRefreshConfig(boolean vsyncEnabled,
                                int framesPerSecond,
                                int refreshRateOverride) {
        private static final DisplayRefreshConfig DEFAULT = new DisplayRefreshConfig(false, 0, 0);
    }

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